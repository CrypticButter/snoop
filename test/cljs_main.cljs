(ns cljs-main
  (:require
   [crypticbutter.snoop :refer [>defn]]))

(>defn x []
  [:=> [:cat int?] int?])

(defn -main [])
