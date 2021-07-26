(ns crypticbutter.snoop.config
  {:clj-kondo/config #_:clj-kondo/ignore
   '{:linters
     {:unresolved-namespace
      {:exclude [cljs.env]}}}}
  #?(:cljs (:require-macros
            [crypticbutter.snoop.config :refer [notify-enabled-state]]
            [net.cgrand.macrovich :as macrovich :refer [deftime usetime]]))
  (:require
   #?@(:clj [[net.cgrand.macrovich :as macrovich :refer [deftime usetime]]
             [clojure.edn :as edn]]
       :cljs [[cljs.env :as cljs.env]])
   [taoensso.encore :as enc]
   [malli.error :as me]))

#?(:clj (try
          (require 'cljs.env)
          (catch Exception _
            (require '[crypticbutter.snoop.stubs.cljs.env :as cljs.env]))))

(usetime
 (declare throw-validation-error)
 (def *config
   "The global runtime configuration atom for snoop's instrumented functions.

  Also accessible from `snoop.core/*config`

  Refer to the project README for option details."
   (atom {:on-instrument-fail    #(throw-validation-error % :input)
          :on-outstrument-fail   #(throw-validation-error % :output)
          :instrument?           true
          :outstrument?          true
          :malli-opts            {}
          :whitelist-by-default? true
          :whitelist-fn          {}
          :blacklist-fn          {}
          :blacklist-ns          #{}
          :whitelist-ns          #{}
          :log-error-fn          #?(:clj println :cljs js/console.error)})))

(usetime
 (defn throw-validation-error
   "Default function used to throw errors when in/outstrumentation fails."
   [{:keys [explainer-error] :as data} boundary]
   (let [boundary-name (case boundary
                         :input "Instrument"
                         :output "Outstrument")
         log-error (:log-error-fn @*config)
         data-str #?(:clj pr-str :cljs identity)]
     (log-error (str boundary-name " error for:") (symbol (:ns data) (:name data)))
     (enc/catching (let [hm (me/humanize explainer-error)]
                     (case boundary
                       :input (let [idx (-> hm count dec)]
                                (log-error "For param:" (nth (:params data) idx)
                                           "\nGot:" (data-str (get-in explainer-error [:value idx]))
                                           "\nError:" (data-str (nth hm idx))))
                       :output (log-error "Got:" (data-str (:value explainer-error))
                                          "\nError:" (data-str hm))))
                   _ (log-error "Humanize failed"
                                "\nGot:" (data-str (:value explainer-error))
                                "\nErrors:" (data-str (:errors explainer-error))))
     (throw (ex-info (str boundary-name " failed. See message printed above.") data)))))

(deftime
  (def ^:private production-cljs-compiler?
    (when cljs.env/*compiler*
      (not= :none (get-in @cljs.env/*compiler* [:options :optimizations] :none)))))

(deftime
  (defn- get-system-propery [#_:clj-kondo/ignore prop]
    #?(:clj (System/getProperty prop) :cljs nil)))

(deftime
  (defn- read-config-file []
    #?(:clj  (try
               (edn/read-string (slurp "snoop.edn"))
               (catch Exception _ nil))
       :cljs nil)))

(deftime
  (defn- get-cljs-compiler-config []
    (when cljs.env/*compiler*
      (get-in @cljs.env/*compiler* [:options :external-config :crypticbutter.snoop]))))

(deftime
  (def *compiletime-config-cache (atom {:by-id    {}
                                        :register (enc/queue)})))

(deftime
  (def compiletime-config-defaults {:defn-sym 'clojure.core/defn
                                    :log-fn-sym 'clojure.core/println}))

(deftime
  (defn- get-compiletime-config* []
    (when production-cljs-compiler?
      (throw (ex-info "Snoop enabled with production compiler options" {})))
    (let [file-config   (when (get-system-propery "snoop.enabled")
                          (or (read-config-file) {}))
          merged-config (enc/merge file-config (get-cljs-compiler-config))]
      (enc/merge compiletime-config-defaults
             (if (and (some? merged-config) (not (contains? merged-config :enabled?)))
               (assoc merged-config :enabled? true)
               merged-config)))))

(deftime
  (defn get-compiletime-config []
    (let [id  (when cljs.env/*compiler*
                (hash (get-in @cljs.env/*compiler* [:options :closure-defines])))
          now #?(:clj (System/currentTimeMillis) :cljs (js/Date.now))
          age (- now (get-in @*compiletime-config-cache [:by-id id :timestamp] 0))]
      (get-in (if (< age 3000)
                @*compiletime-config-cache
                (swap! *compiletime-config-cache
                       (fn [m]
                         (let [fresh-config (get-compiletime-config*)
                               oldest-id    (-> m :register peek)]
                           (-> m
                               (cond-> #__
                                (and id (-> m :register count (> 15))
                                     (< (* 1000 60 10) (- now (get-in m [:by-id oldest-id :timestamp]))))
                                 (-> (update :by-id dissoc oldest-id)
                                     (update :register pop)))
                               (cond-> id (update :register conj id))
                               (assoc-in [:by-id id] {:value     fresh-config
                                                      :timestamp now}))))))
              [:by-id id :value]))))

(deftime
  (defmacro ^:private notify-enabled-state []
    (let [config (get-compiletime-config)
          log (resolve (:log-fn-sym config))]
      (when (and (get-system-propery "snoop.enabled")
                 (not (map? (read-config-file))))
        (log "\u001B[31mWARNING: snoop.enabled is set but we could not find a map in a snoop.edn file.\u001B[m"))
      (if (:enabled? config)
        (log "\u001B[33mBeware: Snoop is snooping and performance may be affected.\u001B[m")
        (log "\u001B[32mSnoop is disabled\u001B[m")))))

(usetime
 (defonce ^:private *notified? (atom false))

 (when-not @*notified?
   (reset! *notified? true)
   (notify-enabled-state)))

(comment
  (reset! *compiletime-config-cache {:by-id    {}
                                     :register (enc/queue)})
  @*compiletime-config-cache

  ;;
  )
