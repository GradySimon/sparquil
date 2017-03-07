(ns sparquil.core
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [org.tobereplaced.mapply :refer [mapply]]
            [com.stuartsierra.component :as component]
            [sparquil.spec]
            [sparquil.kv-store :as kv]
            [sparquil.environment :as env]
            [sparquil.interface :as i]
            [sparquil.display :as d]
            [sparquil.sketch :as s]))

; TODO: Spec everything
; TODO: Add a proper logging library, get rid of printlns
; TODO: Externalize layer definition, make runtime-loadable, configurable

; TODO: Move global regions stuff elsewhere

(defn read-config [config-path]
  (let [raw-config (read-string (slurp config-path))
        [width height] (get-in raw-config [:sketch :size])]
    (assoc-in raw-config [:sketch :regions]
              (concat [{:id :global-bottom :bounds [0 0 width height]}]
                      (get-in raw-config [:sketch :regions])
                      [{:id :global-top :bounds [0 0 width height]}
                       {:id :global :bounds [0 0 width height]}])))) ; TODO: Remove :global

; ---- System definition ----

; TODO: LED shapes can use regions to specify offsets, sizes, etc.
; TODO: Give an explicit ordering to regions.
;       Maybe make into a map so it can have more params, like :background-color

(defn sparquil-system [{sketch-config :sketch
                        redis-config :redis
                        fc-config :fadecandy
                        web-interface-config :web-interface
                        :as config}]
  (component/system-map
    :sketch (component/using (s/new-sketch sketch-config)
              [:env :displayer :kv-store])
    :displayer (d/new-fadecandy-displayer (:host fc-config) (:port fc-config))
    :env (component/using (env/new-env)
                          [:kv-store])
    :kv-store (kv/new-redis-client (:host redis-config) (:port redis-config))
    :web-interface (component/using (i/new-interface-server web-interface-config)
                     [:sketch])))

;(defn -main [config-path]
;  (let [config (read-config config-path)]
;    (component/start (sparquil-system config))))
