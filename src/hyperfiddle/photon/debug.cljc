(ns hyperfiddle.photon.debug
  (:require [hyperfiddle.photon-impl.runtime :as-alias r]
            [clojure.string :as str])
  (:import (hyperfiddle.photon Failure Pending)
           (missionary Cancelled)
           #?(:clj (clojure.lang ExceptionInfo))))

(defonce ^{:doc "A random unique ID generated for each photon runtime instance (browser tab, jvm). Used to identify origin of a transfered value."}
  PEER-ID
  ;; UUID v4 collision probability assumed insignificant for this use case
  #?(:clj  (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn add-stack-frame [frame ex] ; TODO use Throwable.setStackTrace if possible instead of allocating a new ExInfo for each frame
  (ex-info (ex-message ex)
    (-> (update (ex-data ex) ::trace (fnil conj []) (assoc frame ::origin PEER-ID))
      (assoc :hyperfiddle.photon/type ::trace))
    (or (ex-cause ex) ex)))

(defn error
  ([^ExceptionInfo ex]
   (Failure. (ex-info (ex-message ex) (assoc (ex-data ex) :hyperfiddle.photon/type ::trace) (ex-cause ex))))
  ([debug-info ^Failure failure]
   (let [err (.-error failure)]
     (if (or (instance? Pending err)
           (instance? Cancelled err))
       failure
       (Failure. (add-stack-frame debug-info err))))))

(defn render-arg [arg]
  (cond
    (string? arg) arg
    (ident? arg)  arg

    (or (instance? hyperfiddle.photon.Failure arg)
      #?(:clj (instance? Throwable arg)
         :cljs (instance? js/Error arg)))
    (symbol "<exception>")

    :else
    (binding [*print-level*  1
              *print-length* 4]
      (pr-str arg))))

(defn serializable-frame [frame]
  (if (::serializable frame)
    frame
    (-> (update frame ::args (partial mapv render-arg))
        (assoc ::serializable true))))

(defn serializable [map]
  (if (contains? map ::trace)
    (update map ::trace (partial mapv serializable-frame))
    error))

(defn stack-trace [err]
  (some->> (::trace (ex-data err))
    (remove (fn [frame] (= {} (::name frame)))) ; (do a b) => ({} a b)
    (reduce (fn [r frame]
              (if (string? frame)
                (conj r frame)
                (let [{::keys [origin type name args macro]} frame]
                  (conj r
                    (into [(when (and (not= PEER-ID origin)
                                   (not (#{:transfer :toggle} type))
                                   "remote"))]
                      (into
                        (case type
                          :apply         `["(" ~(case name
                                                  hyperfiddle.photon-impl.runtime/fail 'throw
                                                  name)
                                           ~@(map render-arg args) ")"]
                          :eval (let [{::keys [action target method args]} frame]
                                  (case action
                                    :field-access ["(" (str ".-" method) target ")"]
                                    :static-call  `["(" ~(str target "/" method) ~@(map render-arg (rest args)) ")"]
                                    :call         `["(" ~(str "." method) ~target ~@(map render-arg (rest args))")"]
                                    ["<unknown interop>" frame]))
                          :reactive-fn   ["reactive" (if (some? name)
                                                       `(~'fn ~name ~args ~'...)
                                                       `(~'fn ~args ~'...))]
                          :reactive-defn ["reactive" `(~'defn ~name ~args ~'...)]
                          :try           ["(try ...)" ]
                          :catch         [`(~'catch ~@args ~'...)]
                          :finally       ["(finally ...)"]
                          :case-clause   [`(~'case ~@args ~'...)]
                          :case-default  ["case default branch"]
                          :transfer      ["transfer to" (clojure.core/name name)]
                          :toggle        ["transfer"]
                          `["<unknow frame>" ~frame])
                        [(when macro (str "from macro " macro))
                         (some->> (:file frame) (str "in "))
                         (some->> (:line frame) (str "line "))]))))))
      [])
    (mapv (fn [frame] (if (string? frame) frame (str " in " (str/join " " (remove nil? frame))))))
    (str/join "\n")))

(defn unwrap [exception]
  (if (= ::trace (:hyperfiddle.photon/type (ex-data exception)))
    (ex-cause exception)
    exception))
