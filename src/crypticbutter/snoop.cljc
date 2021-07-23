(ns crypticbutter.snoop
  #?(:cljs (:require-macros
            [crypticbutter.snoop :refer [current-ns]]
            [net.cgrand.macrovich :as macrovich :refer [deftime usetime]]))
  (:require
   #?@(:clj [[net.cgrand.macrovich :as macrovich :refer [deftime usetime]]])
   [clojure.pprint :as pp]
   [crypticbutter.snoop.impl.cljs :as patched-cljs]
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

(def InstrumentedParamsVec
  [:and vector?
   [:*
    [:altn
     [:instrumented [:and list?
                     [:catn
                      [:param :any]
                      [:schema [:? :any]]]]]
     [:param :any]]]])

(def ArityDecl
  [:alt
   [:catn
    [:params-decl InstrumentedParamsVec]
    [:prepost-map [:? map?]]
    [:schema [:? FnSchemaDecl]]
    [:body [:+ :any]]]
   [:catn
    [:params-decl InstrumentedParamsVec]
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
   @(or (-> fn-var #?(:cljs deref) meta ::config-atom)
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
  (defn- read-param-decl [parsed-arity]
    {:pre [(:params-decl parsed-arity)]}
    (let [{:keys [params schema schema-used?]}
          (reduce (fn [acc [alt decl]]
                    (let [[param schema] (case alt
                                           :instrumented
                                           [(:param decl) (:schema decl)]

                                           :param
                                           [decl nil])]
                      (-> acc
                          (update :params conj param)
                          (cond-> schema
                            (-> (update :schema conj schema)
                                (assoc :schema-used? true)))
                          (cond-> (not (or schema (= '& decl)))
                            (update :schema conj :any)))))
                  {:params []
                   :schema [:cat]
                   :schema-used? false}
                  (:params-decl parsed-arity))]
      {:params params
       :schema (when schema-used? schema)})))

(usetime
 (defn get-arity-schema [arityn form config]
   (let [schema+ (m/schema form)]
     (case (m/type schema+)
       :=> (select-keys (m/-function-info schema+)
                        #{:input :output})
       :function
       (let [singles (m/children schema+)
             log-error (:log-error-fn config)]
         (loop [idx (dec (count singles))
                match nil]
           (if (neg? idx)
             (if (nil? match)
               (log-error "Snoop Error: Could not find matching arity of" arityn "in" schema+)
               match)
             (let [{:keys [arity] :as info} (m/-function-info (nth singles idx))]
               (recur (dec idx)
                      (if (= arity arityn)
                        (select-keys info #{:input :output})
                        match))))))))))

(deftime
  (defn- modify-arity-rf
    "Reducing function that processes each arity declared by `>defn`"
    [acc {:keys [fn-name schema prepost-map body arityn max-fixed-arity params param-schema ns-name]}]
    (let [{:keys [params-vec
                  args]}      (if (= :varargs arityn)
                                (let [fixed-syms (vec (repeatedly max-fixed-arity gensym))
                                      rest-sym   (gensym)]
                                  {:params-vec (into fixed-syms ['& rest-sym])
                                   :args       `(into ~fixed-syms ~rest-sym)})
                                (let [v (vec (repeatedly arityn gensym))]
                                  {:params-vec v :args v}))
          given-schema        (or schema
                                  (some->> (:=> prepost-map)
                                           (m/parse FnSchemaDecl)))
          given-input-schema  (or (:input given-schema)
                                  (when (and (contains? given-schema :input-unwrapped)
                                             (seq (:input-unwrapped given-schema)))
                                    (into [:cat] (:input-unwrapped given-schema))))
          given-output-schema (:output given-schema)
          cfg-sym             (gensym)
          output-sym          (gensym)
          validation-ctx-sym  (gensym)
          modified-body       `(let [fn-var#             (var ~fn-name)
                                     ~cfg-sym            (get-snoop-config fn-var#)
                                     schemas#            ~(if given-schema
                                                            {:input  given-input-schema
                                                             :output given-output-schema}
                                                            `(some-> (get-in (m/function-schemas)
                                                                             ['~(current-ns) '~fn-name :schema])
                                                                     (m/form)
                                                                     (as-> form#
                                                                           (get-arity-schema ~arityn form# ~cfg-sym))))
                                     input-schema#       (:input schemas#)
                                     output-schema#      (:output schemas#)
                                     ~validation-ctx-sym {:fn-sym    '~(symbol (or (some-> ns-name name) (str *ns*)) (str fn-name))
                                                          :fn-params '~params
                                                          :config    ~cfg-sym}]
                                 ~(when param-schema
                                    `(validate :input ~param-schema ~args ~validation-ctx-sym))
                                 (when input-schema#
                                   (validate :input input-schema# ~args ~validation-ctx-sym))
                                 (let [~params     ~args
                                       ~output-sym (do ~@body)]
                                   (when output-schema#
                                     (validate :output output-schema# ~output-sym ~validation-ctx-sym))
                                   ~output-sym))]
      (-> acc
          (cond-> given-schema
            (assoc-in [:arities arityn] {:input  given-input-schema
                                         :output given-output-schema}))
          (update :raw-parts conj (list params-vec prepost-map modified-body))))))

(deftime
  (defn- eval-macro-config [macro-config base-config]
    (when (some nil? (map resolve ['clojure.core/def 'clojure.core/assoc 'clojure.core/merge]))
      (refer-clojure))
    (enc/catching (enc/merge base-config
                             (eval macro-config))
                  e
                  (let [log (resolve (:log-fn-sym base-config))]
                    (log "ERROR EXPANDING >defn: failed to eval the provided ::snoop/macro-config at compile-time."
                         "\nMake sure:"
                         "\n• You have not passed any locals."
                         "\n• All symbols have been bound at compile-time (eg with 'def')"
                         "\n"
                         "\nYou provided:")
                    (log (with-out-str (pp/pprint macro-config)))
                    (log "\nSurfacing error below:\n")
                    (throw e)))))

(def -defn-option-keys #{::config-atom ::macro-config})

(deftime
  (defn >defn*
    "Generates the output code for `>defn` from the declaration in `args`."
    [&env fn-name args]
    (let [{:keys  [docstring]
           [arity-type
            code] :code
           :as    parse-result} (m/parse InstrumentedDefnArgs args)
          input-attr-map        (enc/merge (:attr-map parse-result) (:attr-map code))
          opts                  (select-keys (enc/merge input-attr-map (meta fn-name))
                                             -defn-option-keys)
          parsed-arities        (into []
                                      (map (fn [a]
                                             (let [{:keys        [params]
                                                    param-schema :schema} (read-param-decl a)]
                                               (assoc a
                                                      :arityn (arity-of-params params)
                                                      :param-schema param-schema
                                                      :params params))))
                                      (case arity-type
                                        :single-arity (vector code)
                                        :multi-arity  (:defs code)))
          max-fixed-arity       (->> parsed-arities
                                     (map :arityn)
                                     (filter int?)
                                     (apply max 0))
          {:keys [enabled?]
           :as   macro-cfg}     (eval-macro-config (::macro-config opts)
                                                   (cfg/get-compiletime-config))
          sym-for-defn          (:defn-sym macro-cfg)
          attr-map              (cond->> input-attr-map (not enabled?)
                                         (enc/remove-keys -defn-option-keys))
          {:keys [raw-parts]}   (reduce (fn [acc {:keys [prepost-map body]
                                                  :as   parsed-arity}]
                                          (if enabled?
                                            (modify-arity-rf acc (assoc parsed-arity
                                                                        :fn-name fn-name
                                                                        :ns-name (some-> &env
                                                                                         :ns
                                                                                         :name)
                                                                        :max-fixed-arity max-fixed-arity))
                                            (update acc :raw-parts conj
                                                    (apply list (:params (read-param-decl parsed-arity))
                                                           prepost-map body))))
                                        {:raw-parts [] :arities {}}
                                        parsed-arities)
          args-for-defn         (into (enc/conj-some [] docstring attr-map)
                                      raw-parts)]
      (apply list
             `do
             (apply list sym-for-defn
                    (if enabled?
                      (vary-meta fn-name assoc ::instrumented? true)
                      (vary-meta fn-name #(enc/remove-keys -defn-option-keys %)))
                    args-for-defn)
             (when (and (:ns &env)  enabled?)
               [`(set! ~fn-name (patched-cljs/meta-fn ~fn-name (enc/assoc-some {} ::config-atom ~(::config-atom opts))))])))))

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
    (>defn* &env sym decls))

  (defmacro >defn-
    "Same as `>defn` but creates a privately scoped var."
    {:style/indent :defn}
    [sym & decls]
    (>defn* &env (vary-meta sym assoc :private true) decls)))

(comment

  (m/=> print [:function
               [:=> [:cat string?] nil?]
               [:=> [:cat string? string?] nil?]])

  (macroexpand-1
   (quote
    (>defn print
      ([first] (println first))
      ([first second] (println first second)))))

  (>defn print
    ([first]

     (println first))
    ([first second]
     [:=> [:cat string? string?] nil?]
     (println first second)))

  (print "Hello!")

  (m/explain nil? nil)

  (clojure.core/some->
   (clojure.core/get-in
    (malli.core/function-schemas)
    [(quote user) (quote print) :schema])
   (malli.core/form)
   (clojure.core/as->
    form__15071__auto__
    {:output (clojure.core/nth form__15071__auto__ 2),
     :input  (clojure.core/nth form__15071__auto__ 1)}))

  (m/validate [:=> {:registry {::small-int [:int {:min -100, :max 100}]}}
               [:cat ::small-int] :int] (fn []))

  (-> (m/function-schemas)
      (get-in ['user 'print])
      :schema
      m/form
      m/schema
      m/-function-info)

  (-> (m/function-schemas)
      (get-in ['user 'print])
      :schema
      m/form
      m/schema
      m/type)

  (-> (m/function-schemas)
      (get-in ['user 'print])
      :schema
      m/form
      m/schema
      m/type)
  (m/=> lol [:=> [:cat int?] int?])

  (-> (m/function-schemas)
      (get-in ['user 'lol])
      :schema
      m/form
      m/schema
      m/-function-info)

  (-> (m/function-schemas)
      (get-in ['user 'lol])
      :schema
      m/form
      m/schema
      m/-function-info)

  (m/children (m/schema [:function {} [:=> [:cat int?] int?]]))

  (m/=> x [:function
           [:=> [:cat int?] :any]
           [:=> [:cat int?] :any]])
  (m/properties [:function {:closed true} [:=> [:cat int?] :any]])

  (require '[malli.util :as mu])

  (m/children [:function {:registry {::small-int [:int {:min -100, :max 100}]}}
               [:=> [:cat ::small-int] :int]])

  (m/-function-info (m/schema [:=> [:cat :int [:* int?]] :int]))

  (m/=> function-schema-multi [:function
                               [:=> [:cat int?] int?]
                               [:=> [:cat map? :any] int?]
                               [:=> [:cat string? :any [:+ string?]] int?]])
  (>defn function-schema-multi
    ([_x]
     5)
    ([_x y]
     y)
    ([_x y & _zs]
     y))

  (-> (get-in (m/function-schemas) ['user 'function-schema-multi :schema])
      (m/form)
      (as-> f (s/get-arity-schema 1 f @s/*config))
      (as-> m
            (:output m)))

;;
  )
