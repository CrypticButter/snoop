(ns crypticbutter.snoop.core
  #?(:cljs (:require-macros
            [crypticbutter.snoop.core :refer [current-ns]]
            [net.cgrand.macrovich :as macrovich :refer [deftime usetime]]))
  (:require
   #?@(:clj [[net.cgrand.macrovich :as macrovich :refer [deftime usetime]]
             [clojure.edn :as edn]]
       :cljs [[cljs.env :as cljs.env]])
   [crypticbutter.snoop.configs :as cfg]
   [taoensso.encore :as enc]
   [malli.core :as m]))

(def => '=>)

(defn arity-of-params [params]
  (let [arg-count (count params)
        variadic? (= '& (get params (- arg-count 2)))]
    (if variadic? :varargs arg-count)))

(def CustomFnSchemaVec [:and vector?
                        [:or
                         [:catn
                          [:input-unwrapped [:* :any]]
                          [:ret-divider [:enum :=> 'crypticbutter.snoop/=> '=> (quote '=>) :ret]]
                          [:output :any]]
                         [:catn
                          [:prefix [:? [:= :=>]]]
                          [:input :any]
                          [:output :any]]]])

(def FnPartSchema [:alt
                   [:catn
                    [:params vector?]
                    [:prepost-map [:? map?]]
                    [:schema [:? CustomFnSchemaVec]]
                    [:body [:+ :any]]]
                   [:catn
                    [:params vector?]
                    [:prepost-map [:? map?]]
                    [:schema [:? CustomFnSchemaVec]]
                    [:body [:* :any]]]])

(def >defn-arg-schema [:catn
                       [:docstring [:? string?]]
                       [:attr-map [:? map?]]
                       [:code
                        [:altn
                         [:multi-arity
                          [:catn
                           [:defs [:+ [:and list? FnPartSchema]]]
                           [:attr-map [:? map?]]]]
                         [:single-arity FnPartSchema]]]])

(deftime
  (defn current-ns []
    (symbol (str *ns*))))

(usetime
 (defn active-validator? [fn-ns fn-sym boundary]
   (let [cfg              @cfg/*config
         enabled?         ((case boundary
                             :input  :instrument?
                             :output :outstrument?) cfg)
         ns-blacklisted?  (contains? (:blacklist-ns cfg) fn-ns)
         ns-whitelisted?  (contains? (:whitelist-ns cfg) fn-ns)
         fn-blacklisted?  (contains? (get-in cfg [:blacklist-fn fn-ns]) fn-sym)
         fn-whitelisted?  (contains? (get-in cfg [:whitelist-fn fn-ns]) fn-sym)]
     (->> (:whitelist-by-default? cfg)
          (and (not ns-blacklisted?))
          (or ns-whitelisted?)
          (and (not fn-blacklisted?))
          (or fn-whitelisted?)
          (and enabled?)))))

(usetime
 (defn validate-input [boundary schema args {:keys [fn-sym fn-ns fn-params]}]
   (let [{:keys [on-fail]} (case boundary
                             :input  {:on-fail :on-instrument-fail}
                             :output {:on-fail :on-outstrument-fail})
         cfg               @cfg/*config
         err               (and (active-validator? fn-ns fn-sym boundary)
                                (m/explain schema args (:malli-opts cfg)))]
     (when err
       ((on-fail cfg) {:explainer-error err
                       :fn-sym          fn-sym
                       :params          fn-params
                       :ns              fn-ns})))))

(deftime
  (defn modify-fn-part-rf [acc {:keys [f-sym params schema prepost-map body]}]
    (let [arity         (arity-of-params params)
          param-sub     (if (:varargs arity)
                          ['& (gensym)]
                          (mapv (fn [_] (gensym)) (range arity)))
          args          (if (= '& (first param-sub)) (second param-sub) param-sub)
          given-schema  (or schema
                            (some->> (::=> prepost-map)
                                     (m/parse CustomFnSchemaVec)))
          given-input-schema (or (:input given-schema)
                                 (some->> (:input-unwrapped given-schema)
                                          (into [:tuple])))
          given-output-schema (:output given-schema)
          cfg-sym       (gensym)
          output-sym    (gensym)
          validation-ctx `{:fn-sym '~f-sym
                           :fn-ns '~(current-ns)
                           :fn-params '~params}
          modified-body `(let [~cfg-sym @cfg/*config
                               schemas# ~(if given-schema
                                           {:input given-input-schema
                                            :output given-output-schema}
                                           `(some-> (get-in (m/function-schemas)
                                                            ['~(current-ns) '~f-sym :schema])
                                                    (m/form)
                                                    (as-> form#
                                                          {:input (nth form# 1)
                                                           :output (nth form# 2)})))
                               input-schema# (:input schemas#)
                               output-schema# (:output schemas#)]
                           (when input-schema#
                             (validate-input :input input-schema# ~args ~validation-ctx))
                           (let [~params     ~args
                                 ~output-sym (do ~@body)]
                             (when output-schema#
                               (validate-input :output output-schema# ~output-sym ~validation-ctx))
                             ~output-sym))]
      (-> acc
          (cond-> given-schema
            (assoc-in [:arities arity] {:input given-input-schema :output given-output-schema}))
          (update :raw-parts conj (list param-sub prepost-map modified-body))))))

(deftime
  (defn >defn*
    [f-sym args]
    (let [{:keys [enabled?] :as macro-cfg} (cfg/get-macrotime-config)
          sym-for-defn                     (or (:sym-for-defn macro-cfg) 'defn)
          {:keys             [docstring]
           [arity-type code] :code
           :as               parse-result} (m/parse >defn-arg-schema args)
          attr-map                         (merge (:attr-map parse-result) (:attr-map code))
          parts                            (if (= :single-arity arity-type)
                                             (vector code)
                                             (:defs code))
          {:keys [raw-parts]}              (reduce (fn [acc {:keys [params prepost-map body]
                                                             :as parsed-part}]
                                                     (if enabled?
                                                       (modify-fn-part-rf acc (assoc parsed-part
                                                                                     :f-sym f-sym))
                                                       (update acc :raw-parts conj
                                                               (apply list params prepost-map body))))
                                                   {:raw-parts [] :arities {}}
                                                   parts)
          args-for-defn                    (into (enc/conj-some [] docstring attr-map)
                                                 raw-parts)]
      (apply list sym-for-defn f-sym args-for-defn))))

(deftime
  (defmacro >defn
    {:style/indent :defn}
    [sym & body]
    (>defn* sym body)))

(deftime
  (defmacro >defn-
    {:style/indent :defn}
    [sym & body]
    (>defn* (vary-meta sym assoc :private true) body)))

(comment
  (defmacro xsonboy {:style/indent :defn}
    [a b c])

  (xsonboy []
           435
           34)

  (m/parse >defn-arg-schema ["some" [2 3 4] [2 4] 4])

  (->> '({} ([] 4))
       (m/parse >defn-arg-schema)
       (m/unparse >defn-arg-schema))

  (arity-of-params [3 3 3 '&])
  (m/=> idksomefnsomewhere {:arities {1 {:input  [:cat int?]
                                         :output int?}
                                      2 {:input  [:cat int? int?]
                                         :output int?}}})

  (crypticbutter.snoop.core/>defn
    bob
    ([x y]
     [[:cat int? int?] => pos-int?]
     (+ x y))
    ([]
     {:pre []}))

  (try (bob 4 -5)
       (catch js/Error e
         (prn (e))))

  (m/validate (get-in (m/function-schemas)
                      ['fin-man.data.edn-ledger.reader.tx-templates
                       'template-apply-fn
                       :schema])
              (fn []))

  (-> (get-in (m/function-schemas)
              ['fin-man.data.edn-ledger.reader
               'parse-ledger
               :schema])
      m/form
      (nth 2)
      str
      (subs 0 20))

  (m/parse CustomFnSchemaVec (::=> {}))

  (type (get-in (m/function-schemas)
                ['fin-man.data.edn-ledger.reader
                 'parse-ledger]))

  (m/=> xf [:=> [:cat int?] int?])

  (macroexpand-1 '(crypticbutter.snoop.core/>defn xf [i]
                    i))
  (crypticbutter.snoop.core/>defn xf [i]
    i
    "")

  (clojure.core/some->
   (clojure.core/get-in
    (malli.core/function-schemas)
    ['crypticbutter.snoop.core 'xf :schema])
   (malli.core/form)
   (clojure.core/as->
    form__75413__auto__
    {:output (clojure.core/nth form__75413__auto__ 2),
     :input (clojure.core/nth form__75413__auto__ 1)}))

  (xf 4)

  ;;
  )
