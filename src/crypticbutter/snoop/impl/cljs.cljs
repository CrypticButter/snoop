(ns crypticbutter.snoop.impl.cljs)

(defn meta-fn
     ;; Taken from https://clojure.atlassian.net/browse/CLJS-3018
     ;; Because the current MetaFn implementation can cause quirky errors in CLJS
  [f m]
  (let [new-f (goog/bind f #js{})]
    (.assign js/Object new-f f)
    (specify! new-f IMeta #_:clj-kondo/ignore (-meta [_] m))
    new-f))
