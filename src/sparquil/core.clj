(ns sparquil.core
  (:require [clojure.spec :as spec]
            [org.tobereplaced.mapply :refer [mapply]]
            [com.stuartsierra.component :as component]
            [quil.core :as q]
            [quil.middleware :as m]
            [taoensso.carmine :as carm :refer [wcar]]
            [sparquil.spec]))

; TODO: Spec everything
; TODO: Add a proper logging library, get rid of printlns

;; ---- Redis ----

; TODO: set up a "key mirroring" abstraction? Provide a set of key patternss,
;       it keeps any updates to keys matching those patterns in sync with redis

(defprotocol KVStore
  "A protocol for key-value stores like Redis."
  (get-pattern [kv-store pattern]
    "Return a map containing all keys from kv-store starting with prefix")
  (subscribe-pattern [kv-store pattern callback]
    "Calls (callback key new-value) to any key matching pattern"))

(defrecord RedisClient [host port conn]

  component/Lifecycle
  (start [redis-client]
    (assoc redis-client :conn {:pool {} :spec {:host host :port port}}))

  (stop [redis-client]
    (dissoc redis-client :conn))

  KVStore
  (get-pattern [redis-client pattern]
    (let [keys (wcar conn (carm/keys pattern))]
      (into {} (map (fn [k] {k (wcar conn (carm/get k))})
                    keys))))

  (subscribe-pattern [redis-client pattern callback]
    (let [channel (str "__keyspace@0__:" pattern)]
      (carm/with-new-pubsub-listener (:spec conn)
        {channel (fn [[type _ chan-name _ :as msg]]
                  (when (= type "pmessage")
                    (let [k (second (re-find #"^__keyspace@0__:(.*)$" chan-name))]
                      (callback k (wcar conn (carm/get k))))))}
        (carm/psubscribe channel)))))

(defn new-redis-client [host port]
  (->RedisClient host port nil))

;; ---- External environment state ----

(spec/fdef valid-env-key?
           :args (spec/cat :key string?))

(defn valid-env-key? [key]
  "Returns whether the string key is a valid env key."
  (re-matches #"^env(?:\.[\w-]+)*/[\w-]+$" key))

(defn update-env-cache! [cache key value]
  "If key is valid, sets that key in env atom to value."
  (if (valid-env-key? key)
    (do (println "Updating env:" key "->" value)
        (swap! cache assoc (keyword key) value))
    (println "Ingoring invalid env key:" key)))

(defn env-get
  ([env key]
   (env-get env key nil))
  ([env key not-found]
   (get @(:cache env) key not-found)))

(defrecord Env [cache kv-store]

  component/Lifecycle
  (start [env]
    (dorun (map #(apply update-env-cache! cache %) (get-pattern kv-store "env*")))
    (subscribe-pattern kv-store "env*" #(update-env-cache! cache %1 %2))
    env)

  (stop [env]
    env))

(defn new-env []
  (map->Env {:cache (atom {})
             :kv-store nil}))

;; ---- Quil ----

(defrecord Sketch [opts applet env]

  component/Lifecycle
  (start [sketch]
    ; TODO: Force fun-mode middleware
    (let [wrapped-opts (update opts :update #(partial % env))]
      (assoc sketch :applet (mapply q/sketch wrapped-opts))))

  (stop [sketch]
    (. applet exit)
    env))

(defn new-sketch
  "Sketch component constructor. Opts will be passed to quil/sketch. See
   quil/defsketch for documentation of possible options. :setup, :update, and
   :draw functions as well as fun-mode middleware must be supplied. :update
   function should take two args: env, a component representing the current
   environemnt, and state, which is the normal quil fun-mode state."
  [opts]
  (let [safe-opts (spec/conform :sketch/opts opts)]
    (if (= safe-opts :clojure.spec/invalid)
      (throw (Exception. (str "Invalid sketch options: "
                              (spec/explain-str :sketch/opts opts))))
      (map->Sketch {:opts safe-opts}))))

(defn setup []
  ; Set frame rate to 30 frames per second.
  (q/frame-rate 30)
  ; Set color mode to HSB (HSV) instead of default RGB.
  (q/color-mode :hsb)
  ; Get all existing env keys from redis
  ; setup function returns initial state. It contains
  ; circle color and position.
  {:color 0
   :angle 0})

(spec/fdef parse-number
           :args (spec/cat :s (spec/nilable string?))
           :ret (spec/nilable number?))

; From http://stackoverflow.com/a/12285023/1028969
(defn parse-number
  "Reads a number from a string. Returns nil if not a number."
  [s]
  (when s
    (if (re-find #"^-?\d+\.?\d*$" s)
      (read-string s))))

(defn update-state-with-env [env state]
  ; Update sketch state by changing circle color and position.
  {:color (or (parse-number (env-get env :env/color))
              (mod (+ (:color state) 0.7) 255))
   :angle (+ (:angle state) 0.1)})

(defn draw-state [state]
  ; Clear the sketch by filling it with light-grey color.
  (q/background 240)
  ; Set circle color.
  (q/fill (:color state) 255 255)
  ; Calculate x and y coordinates of the circle.
  (let [angle (:angle state)
        x (* 150 (q/cos angle))
        y (* 150 (q/sin angle))]
    ; Move origin point to the center of the sketch.
    (q/with-translation [(/ (q/width) 2)
                         (/ (q/height) 2)]
      ; Draw the circle.
      (q/ellipse x y 100 100))))

(defn sparquil-system []
  (component/system-map
    :sketch (component/using
              (new-sketch
                {:title "You spin my circle right round"
                 :size [500 500]
                 :setup setup
                 :update update-state-with-env
                 :draw draw-state
                 :middleware [m/fun-mode]})
              [:env])
    :env (component/using (new-env)
                          [:kv-store])
    :kv-store (new-redis-client "127.0.0.1" 6379)))
