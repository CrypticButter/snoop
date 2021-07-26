(ns main
  #?(:cljs
     (:require-macros
      [main :refer [mac]]))
  (:require
   [crypticbutter.snoop :refer [>defn]]))

(defn -main [])

#?(:clj
   (defn backend []
     (str *ns*)))

#?(:clj
   (defmacro mac []
     `~(backend)))

(>defn f []
  [:=> int?]
  "")

#?(:cljs
   (do
     (mac)))

#?(:cljs
   (macroexpand-1 '(>defn _f [])))
#?(:cljs
   (f))
#?(:cljs
   (let [x (quote main/_f)]
     (namespace x)))
