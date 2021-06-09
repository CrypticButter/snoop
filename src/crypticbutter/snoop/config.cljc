(ns crypticbutter.snoop.config
  {:clj-kondo/config #_:clj-kondo/ignore
   '{:linters
     {:unresolved-namespace
      {:exclude [cljs.env]}}}}
  #?(:cljs (:require-macros
            [crypticbutter.snoop.config]
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
 (defn throw-validation-error [{:keys [explainer-error] :as data} boundary]
   (let [boundary-name (case boundary
                         :input "Instrument"
                         :output "Outstrument")
         print-msg #?(:clj println :cljs js/console.error)]
     (print-msg (str boundary-name " error for")
                (:fn-sym data))
     (try (let [hm (me/humanize explainer-error)]
            (case boundary
              :input (let [idx (-> hm count dec)]
                       (print-msg "For param:" (nth (:params data) idx)
                                  "\nGot:" (get-in explainer-error [:value idx])
                                  "\nError:" (nth hm idx)))
              :output (print-msg "Got:" (:value explainer-error)
                                 "\nError:" hm)))
          (catch :default _
            (print-msg "Humanize failed"
                       "\nGot:" (:value explainer-error)
                       "\nErrors:" (:errors explainer-error))))
     (throw (ex-info (str boundary-name " failed. See message printed above.") data)))))

(usetime
 (def *config (atom {:on-instrument-fail  #(throw-validation-error % :input)
                     :on-outstrument-fail #(throw-validation-error % :output)
                     :instrument?         true
                     :outstrument?        true
                     :malli-opts          {}
                     :whitelist-by-default true
                     :whitelist-fn {}
                     :blacklist-fn {}
                     :blacklist-ns #{}
                     :whitelist-ns #{}})))

(deftime
  (def production-cljs-compiler?
    (when cljs.env/*compiler*
      (not= :none (get-in @cljs.env/*compiler* [:options :optimizations] :none)))))

(deftime
  (defn get-system-propery [#_:clj-kondo/ignore prop]
    #?(:clj (System/getProperty prop) :cljs nil)))

(deftime
  (defn read-config-file []
    #?(:clj  (try
               (edn/read-string (slurp "snoop.edn"))
               (catch Exception _ nil))
       :cljs nil)))

(deftime
  (defn get-cljs-compiler-config []
    (when cljs.env/*compiler*
      (get-in @cljs.env/*compiler* [:options :external-config :crypticbutter.snoop]))))

(deftime
  (def *macrotime-config-cache (atom {:by-id    {}
                                      :register (enc/queue)})))

(deftime
  (defn get-macrotime-config* []
    (when production-cljs-compiler?
      (throw (ex-info "Snoop enabled with production compiler options" {})))
    (let [file-config   (when (get-system-propery "snoop.enabled")
                          (or (read-config-file) {}))
          merged-config (merge file-config (get-cljs-compiler-config))]
      (if (and (some? merged-config) (not (contains? merged-config :enabled?)))
        (assoc merged-config :enabled? true)
        merged-config))))

(deftime
  (defn get-macrotime-config []
    (let [id  (when cljs.env/*compiler*
                (hash (get-in @cljs.env/*compiler* [:options :closure-defines])))
          now #?(:clj (System/currentTimeMillis) :cljs (js/Date.now))
          age (- now (get-in @*macrotime-config-cache [:by-id id :timestamp] 0))]
      (get-in (if (< age 3000)
                @*macrotime-config-cache
                (swap! *macrotime-config-cache
                       (fn [m]
                         (let [fresh-config (get-macrotime-config*)
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

;; (deftime
;;   (defn default-ns-config))

(comment
  (reset! *macrotime-config-cache{:by-id    {}
                                  :register (enc/queue)})
  @*macrotime-config-cache

  (swap! *config assoc :on-instrument-fail
         (fn [err]
           (throw (ex-info "Instrument failed for"))))

  ;;
  )
