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
    (>defn _varargs [& _])
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
      {::snoop/macro-config {:enabled? false}}
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
                                    snoop/-defn-option-keys))))))
