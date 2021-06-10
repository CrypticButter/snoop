(ns crypticbutter.snoop
  #?(:cljs (:require-macros
            [crypticbutter.snoop :refer [current-ns]]
            [net.cgrand.macrovich :as macrovich :refer [deftime usetime]]))
  (:require
   #?@(:clj [[net.cgrand.macrovich :as macrovich :refer [deftime usetime]]])
   [crypticbutter.snoop.config :as cfg]
   [taoensso.encore :as enc]
   [malli.core :as m]))

(def => '=>)

(defn arity-of-params [params]
  (let [arg-count (count params)
        variadic? (= '& (get params (- arg-count 2)))]
    (if variadic? :varargs arg-count)))

(def FnSchemaDecl
  [:and vector?
   [:or
    [:catn
     [:input-unwrapped [:* :any]]
     [:ret-divider [:enum :=> `=> '=> (quote '=>) :ret]]
     [:output :any]]
    [:catn
     [:prefix [:? [:= :=>]]]
     [:input :any]
     [:output :any]]]])

(def ArityDecl
  [:alt
   [:catn
    [:params vector?]
    [:prepost-map [:? map?]]
    [:schema [:? FnSchemaDecl]]
    [:body [:+ :any]]]
   [:catn
    [:params vector?]
    [:prepost-map [:? map?]]
    [:schema [:? FnSchemaDecl]]
    [:body [:* :any]]]])

(def InstrumentedDefnArgs
  [:catn
   [:docstring [:? string?]]
   [:attr-map [:? map?]]
   [:code
    [:altn
     [:multi-arity
      [:catn
       [:defs [:+ [:and list? ArityDecl]]]
       [:attr-map [:? map?]]]]
     [:single-arity ArityDecl]]]])

(usetime
 (def *config
   "The global runtime configuration atom for snoop's instrumented functions.

  Refer to the project README for option details."
   cfg/*config)

 (defn get-snoop-config [fn-var]
   @(or (-> fn-var meta ::config-atom)
        cfg/*config)))

(usetime
 (defn validation-allowed?
   "Returns whether the `boundary` of the function is allowed to be validated
    with the active config."
   ([boundary fn-ns fn-name cfg]
    (let [enabled?         ((case boundary
                              :input  :instrument?
                              :output :outstrument?) cfg)
          ns-blacklisted?  (contains? (:blacklist-ns cfg) fn-ns)
          ns-whitelisted?  (contains? (:whitelist-ns cfg) fn-ns)
          fn-blacklisted?  (contains? (get-in cfg [:blacklist-fn fn-ns]) fn-name)
          fn-whitelisted?  (contains? (get-in cfg [:whitelist-fn fn-ns]) fn-name)]
      (->> (:whitelist-by-default? cfg)
           (and (not ns-blacklisted?))
           (or ns-whitelisted?)
           (and (not fn-blacklisted?))
           (or fn-whitelisted?)
           (and enabled?))))
   ([boundary fn-var]
    (let [fn-name (-> fn-var meta :name)
          fn-ns (-> fn-var meta :ns str symbol)]
      (validation-allowed? boundary fn-ns fn-name (get-snoop-config fn-var))))))

(usetime
 (defn validate
   "Calls the corresponding error fn when the input/output fails schema validation"
   [boundary schema args {:keys [fn-sym fn-params config]}]
   (let [cfg               (or config @cfg/*config)
         fn-name           (symbol (name fn-sym))
         fn-ns             (symbol (namespace fn-sym))
         {:keys [on-fail]} (case boundary
                             :input  {:on-fail :on-instrument-fail}
                             :output {:on-fail :on-outstrument-fail})
         err               (and (validation-allowed? boundary fn-ns fn-name cfg)
                                (m/explain schema args (:malli-opts cfg)))]
     (when err
       ((on-fail cfg) {:explainer-error err
                       :ns              fn-ns
                       :name            fn-name
                       :params          fn-params})))))

(deftime
  (defn- current-ns []
    (symbol (str *ns*))))

(deftime
  (defn- modify-arity-rf
    "Reducing function that processes each arity declared by `>defn`"
    [acc {:keys [fn-name params schema prepost-map body arity max-fixed-arity]}]
    (let [{:keys [params-vec
                  args]}      (if (= :varargs arity)
                                (let [fixed-syms (vec (repeatedly max-fixed-arity gensym))
                                      rest-sym   (gensym)]
                                  {:params-vec (into fixed-syms ['& rest-sym])
                                   :args       `(into ~fixed-syms ~rest-sym)})
                                (let [v (vec (repeatedly arity gensym))]
                                  {:params-vec v :args v}))
          given-schema        (or schema
                                  (some->> (:=> prepost-map)
                                           (m/parse FnSchemaDecl)))
          given-input-schema  (or (:input given-schema)
                                  (when (contains? given-schema :input-unwrapped)
                                    (if (empty? (:input-unwrapped given-schema))
                                      :cat
                                      (into [:tuple] (:input-unwrapped given-schema)))))
          given-output-schema (:output given-schema)
          cfg-sym             (gensym)
          output-sym          (gensym)
          modified-body       `(let [fn-var#        (var ~fn-name)
                                     ~cfg-sym       (get-snoop-config fn-var#)
                                     schemas#       ~(if given-schema
                                                       {:input  given-input-schema
                                                        :output given-output-schema}
                                                       `(some-> (get-in (m/function-schemas)
                                                                        ['~(current-ns) '~fn-name :schema])
                                                                (m/form)
                                                                (as-> form#
                                                                      {:input  (nth form# 1)
                                                                       :output (nth form# 2)})))
                                     input-schema#  (:input schemas#)
                                     output-schema# (:output schemas#)
                                     validation-ctx# {:fn-sym    '~(symbol (str *ns*) (str fn-name))
                                                      :fn-params '~params
                                                      :config ~cfg-sym}]
                                 (prn (m/function-schemas))
                                 (prn ['~(current-ns) '~fn-name :schema])
                                 (when input-schema#
                                   (validate :input input-schema# ~args validation-ctx#))
                                 (let [~params     ~args
                                       ~output-sym (do ~@body)]
                                   (when output-schema#
                                     (validate :output output-schema# ~output-sym validation-ctx#))
                                   ~output-sym))]
      (-> acc
          (cond-> given-schema
            (assoc-in [:arities arity] {:input given-input-schema :output given-output-schema}))
          (update :raw-parts conj (list params-vec prepost-map modified-body))))))

(def -defn-option-keys #{::config-atom ::macro-config})

(deftime
  (defn >defn*
    "Generates the output code for `>defn` from the declaration in `args`."
    [fn-name args]
    (let [{:keys  [docstring]
           [arity-type
            code] :code
           :as    parse-result} (m/parse InstrumentedDefnArgs args)
          input-attr-map        (merge (:attr-map parse-result) (:attr-map code))
          opts                  (select-keys (merge input-attr-map (meta fn-name))
                                             -defn-option-keys)
          attr-map              (enc/remove-keys -defn-option-keys input-attr-map)
          parsed-arities        (into []
                                      (map (fn [a]
                                             (assoc a :arity (arity-of-params (:params a)))))
                                      (case arity-type
                                        :single-arity (vector code)
                                        :multi-arity  (:defs code)))
          max-fixed-arity (->> parsed-arities
                               (map :arity)
                               (filter int?)
                               (apply max 0))
          {:keys [enabled?]
           :as   macro-cfg}     (merge (cfg/get-compiletime-config)
                                       (::macro-config opts))
          sym-for-defn          (:defn-sym macro-cfg)
          {:keys [raw-parts]}   (reduce (fn [acc {:keys [params prepost-map body]
                                                  :as   parsed-part}]
                                          (if enabled?
                                            (modify-arity-rf acc (assoc parsed-part
                                                                        :fn-name fn-name
                                                                        :max-fixed-arity max-fixed-arity))
                                            (update acc :raw-parts conj
                                                    (apply list params prepost-map body))))
                                        {:raw-parts [] :arities {}}
                                        parsed-arities)
          args-for-defn         (into (enc/conj-some [] docstring attr-map)
                                      raw-parts)]
      (apply list sym-for-defn
             (if enabled?
               (vary-meta fn-name assoc ::instrumented? true)
               (vary-meta fn-name #(enc/remove-keys -defn-option-keys %)))
             args-for-defn))))

(deftime
  (defmacro >defn
    "Wraps `defn` with instrumentation features if enabled.

  A malli schema can be specified in these places:
  - The first thing in the body
  - The `:=>` key of the prepost map
  - malli's function schema registry (using `m/=>`)

  Additional options can be provided via metadata or an attr-map:
  - `::snoop/macro-config` - a map used to override the global compile-time configuration for this function.
  - `::snoop/config-atom` - a runtime config atom to use in place of the default `*config` atom.
  "
    {:style/indent :defn}
    [sym & decls]
    (>defn* sym decls))

  (defmacro >defn-
    "Same as `>defn` but creates a privately scoped var."
    {:style/indent :defn}
    [sym & decls]
    (>defn* (vary-meta sym assoc :private true) decls)))

(comment
  (defmacro xsonboy {:style/indent :defn}
    [a b c])

  (xsonboy []
           435
           34)

  (m/parse InstrumentedDefnArgs ["some" [2 3 4] [2 4] 4])

  (->> '({} ([] 4))
       (m/parse InstrumentedDefnArgs)
       (m/unparse InstrumentedDefnArgs))

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

  (m/parse FnSchemaDecl (::=> {}))

  (type (get-in (m/function-schemas)
                ['fin-man.data.edn-ledger.reader
                 'parse-ledger]))

  (m/=> xf [:=> [:cat int?] int?])

  (macroexpand-1 '(crypticbutter.snoop.core/>defn xf []
                    ;; [=> int?]
                    i))

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

  (>defn add [x y]
    [int? int? => int?]
    8)

  (add 4 "🍉")

  (-> (get-in (m/function-schemas) [(symbol (str *ns*)) 'xf :schema])
      m/form)

;;
  )
