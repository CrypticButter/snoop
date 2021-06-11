(ns crypticbutter.snoop-test
  {:clj-kondo/config #_:clj-kondo/ignore
   '{:linters
     {:inline-def
      {:level :off}}}}
  (:require
   [clojure.test :refer [deftest is testing]]
   [taoensso.encore :as enc]
   [malli.core :as m]
   [crypticbutter.snoop :as snoop :refer [>defn =>]]))

(defn throws? [f & args]
  (enc/catching (do (apply f args)
                      false)
                  _ true))

(deftest >defn-test
  (testing "no schema"
    (>defn f [_ _]
      [])
    (is (= [] (f nil nil))))

  (testing "instrument - malli style schema"
    (>defn add [_ _]
      [:=> [:cat int? int?] string?]
      "0")
    (is (= "0" (add 1 2)))
    (is (true? (throws? add "" ""))))

  (testing "instrument - ghostwheel style schema"
    (>defn g-var [_]
      [int? => string?]
      "0")
    (is (= "0" (g-var 5)))
    (is (true? (throws? g-var "")))

    (>defn g-sym [_]
      [int? '=> string?]
      "0")
    (is (= "0" (g-sym 5)))
    (is (true? (throws? g-sym "")))

    (>defn g-kw [_]
      [int? :=> string?]
      "0")
    (is (= "0" (g-kw 5)))
    (is (true? (throws? g-kw "")))

    (>defn g-kw-ret [_]
      [int? :ret string?]
      "0")
    (is (= "0" (g-kw-ret 5)))
    (is (true? (throws? g-kw-ret ""))))

  (testing "outstrument - 0-parameter functions - malli style"
    (>defn f0-m-good []
      [:cat int?]
      5)
    (is (= 5 (f0-m-good)))

    (>defn f0-m-bad []
      [:cat string?]
      5)
    (is (true? (throws? f0-m-bad))))

  (testing "outstrument - 0-parameter functions - ghostwheel style"
    (>defn f0-g-good []
      [=> int?]
      5)
    (is (= 5 (f0-g-good)))

    (>defn f0-g-bad []
      [=> string?]
      5)
    (is (true? (throws? f0-g-bad))))

  (testing "schema via prepost map"
    (>defn prepost
      [x _]
      {:=> [:any int? => int?]}
      x)
    (is (= 5 (prepost 5 4)))
    (is (true? (throws? prepost "x" 4)))
    (is (true? (throws? prepost 5 "y"))))

  (testing "schema via m/=>"
    (>defn malli-sch [x _y]
      x)
    (m/=> malli-sch [:=> [:cat :any int?] int?])
    (is (= 5 (malli-sch 5 4)))
    (is (true? (throws? malli-sch "x" 4)))
    (is (true? (throws? malli-sch 5 "y"))))

  (testing "multi-arity and variable-arity"
    (is (ifn? (>defn _varargs [& _])))
    (>defn multi-arity
      ([]
       [=> int?]
       5)
      ([x]
       [int? => int?]
       x)
      ([x & _]
       [[:+ int?] int?]
       x))
    (is (= 5 (multi-arity)))
    (is (= 4 (multi-arity 4)))
    (is (true? (throws? multi-arity "x")))
    (is (= 3 (multi-arity 3 4 4)))
    (is (true? (throws? multi-arity 3 "x" "y"))))

  (testing "disable via meta and attr-map"
    (>defn ^{::snoop/macro-config {:enabled? false}}
      d-m []
      [int? => :nil]
      5)
    (is (= 5 (d-m)))
    (>defn d-a1
      {::snoop/macro-config
       ;; Putting def here as it ensures the var is bound in both the cljs and clj
       ;; unit tests, apparently.
       (do (def macro-config-with-disable {:enabled? false})
           (assoc macro-config-with-disable 4 20))}
      []
      [int? => :nil]
      5)
    (is (= 5 (d-a1)))
    (>defn d-a2
      ([]
       [int? => :nil]
       5)
      #_:clj-kondo/ignore
      {::snoop/macro-config {:enabled? false}})
    (is (= 5 (d-a2)))
    (is (true? (empty? (select-keys (into {} (map meta)
                                          [(var d-m) (var d-a1) (var d-a2)])
                                    snoop/-defn-option-keys)))))

  (testing "custom runtime atom passes through"
    (>defn passthrough
      {::snoop/config-atom (atom (merge @snoop/*config
                                        {:melons true}))}
      []
      [=> string?]
      "melon")
    (is (true? (contains? (some-> (var passthrough) #?(:cljs deref)
                                  meta ::snoop/config-atom deref)
                          :melons))))

  (testing "override runtime atom"
    ;; important to keep around whitelisting settings
    (>defn custom-atom
      {::snoop/config-atom (atom (merge @snoop/*config
                                        {:outstrument? false}))}
      []
      [=> int?]
      "melon")
    (is (false? (throws? custom-atom)))
    (>defn custom-atom-out
      {::snoop/config-atom (atom (merge @snoop/*config
                                        {:outstrument? true}))}
      []
      [=> int?]
      "melon")
    (is (true? (throws? custom-atom-out)))))

(comment
  (-> (meta (var custom-atom))
      ::snoop/config-atom
      type)

  (custom-atom)

  (macroexpand-1 '(crypticbutter.snoop/>defn custom-atom
                    {::snoop/config-atom (atom (merge @snoop/*config
                                                      {:outstrument? false}))}
                    []
                    [=> int?]
                    "melon"))

  @(atom (merge @snoop/*config
                {:outstrument? false}))

  (snoop/get-snoop-config (var custom-atom))

  (defn x {:a (inc 0)} [])
  (defn ^{:a (inc 0)} x [])
  (meta (var x))

  (def foo :foo-val)
  (def xs {:a 8} [])
  (meta xs)
  (alter-meta! xs assoc 8 8)

  (set! ^:butter x (vary-meta x (fn [y] {:some 8})))

  (meta x)

  (>defn passthrough
    {::snoop/config-atom (atom (merge @snoop/*config
                                      {:melons true}))}
    []
    [=> string?]
    "melon")

  (some-> (var passthrough) #?(:cljs deref) meta ::snoop/config-atom deref)

  (macroexpand-1
   (crypticbutter.snoop/>defn f [_ _]
     []))
  (meta f)

  (f 8 8)



  (defn x {:a (inc 0)} [])

  (def xp nil)
  (set! xp (meta-fn x {:a 8}))

  (meta xp)
;;
  )