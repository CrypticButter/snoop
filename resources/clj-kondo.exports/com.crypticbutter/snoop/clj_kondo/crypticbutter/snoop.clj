(ns clj-kondo.crypticbutter.snoop
  (:require [clj-kondo.hooks-api :as api]))

(defn- read-param-decl [params-node]
  (reduce (fn [acc param-decl]
            (let [list-children (when (api/list-node? param-decl)
                                  (:children param-decl))
                  schema-element (second list-children)]
              (-> acc
                  (update 0 conj (or (first list-children) param-decl))
                  (cond-> schema-element
                    (update 1 conj schema-element)))))
          [[] ;; params
           []] ;; schemas
          (:children params-node)))

(defn >defn
  [{:keys [node]}]
  (let [[macro-sym & body] (:children node)
        defn-node (api/token-node (case (name (api/sexpr macro-sym))
                                    ">defn" 'defn ">defn-" 'defn-))
        output-node (api/list-node
                     (loop [body' body
                            acc []]
                       (if-some [item (first body')]
                         (if (api/vector-node? item)
                           (let [[params schemas] (read-param-decl item)]
                             (list (api/token-node 'do) (api/vector-node schemas)
                                   (api/list-node
                                    (list* defn-node
                                           (into (conj acc (api/vector-node params))
                                                 (rest body'))))))
                           (recur (next body') (conj acc item)))
                         (list* defn-node acc))))]
    {:node output-node}))
