(ns test-runner.hooks
  (:require
   [clojure.java.browse :as browse]
   [shadow.cljs.devtools.api :as shadow-api]
   [shadow.cljs.devtools.server :as shadow-server]))

(defn compile-and-launch [suite _]
  (shadow-server/start!)
  (shadow-api/compile! (:shadow/build-id suite) {})
  (some-> (:browse-url suite) browse/browse-url)
  suite)
