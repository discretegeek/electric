(ns hyperfiddle.photon
  (:refer-clojure :exclude [eval def defn fn for empty?])
  (:require [clojure.core :as cc]
            [hyperfiddle.photon-impl.compiler :as c]
            [hyperfiddle.photon-impl.runtime :as r]
            [hyperfiddle.photon-impl.for :refer [map-by]]
            #?(:clj [hyperfiddle.rcf.analyzer :as ana])     ; todo remove
            [missionary.core :as m]
            [clojure.core.async :as a]
            #?(:cljs [hyperfiddle.photon-client])
            [hyperfiddle.photon-impl.io :as io]
            [hyperfiddle.photon.debug :as dbg])
  #?(:cljs (:require-macros [hyperfiddle.photon :refer [def defn fn vars boot for for-by local local-with run run-with forget debounce wrap]]))
  (:import #?(:clj (clojure.lang IDeref))
           (hyperfiddle.photon Pending Failure)
           (missionary Cancelled)))

;; Equality semantics for Failure and Pending
;; For JVM, defined in java class.
#?(:cljs
   (extend-type Pending
     IEquiv
     (-equiv [this other]
       (instance? Pending other))))

#?(:cljs
   (extend-type Failure
     IEquiv
     (-equiv [this other]
       (and (instance? Failure other)
            (= (.-error this) (.-error other))))))

#?(:clj
   (do
                                        ; Optionally, tell RCF not to rewrite Photon programs.
     (defmethod ana/macroexpand-hook `hyperfiddle.photon/run [the-var form env args] `(hyperfiddle.photon/run ~@args)) ; optional
                                        ;(defmethod ana/macroexpand-hook `hyperfiddle.photon/run2 [_the-var _form _env args] `(hyperfiddle.photon/run2 ~@args))

                                        ; Don't expand cc/binding (prevent infinite loop). Explicit implicit do
     (defmethod ana/macroexpand-hook 'clojure.core/binding [_the-var _form _env [bindings & body]] (reduced `(binding ~bindings (do ~@body))))
     (defmethod ana/macroexpand-hook 'cljs.core/binding [_the-var _form _env [bindings & body]] (reduced `(binding ~bindings (do ~@body))))))



(defmacro boot "
Takes a photon program and returns a task setting up the full system with client part running locally and server part
running on a remote host.
" [& body]
  (assert (:js-globals &env))
  (let [[client server] (c/analyze (assoc &env ::c/peers-config {::c/local :cljs ::c/remote :clj}) `(do ~@body))]
    `(hyperfiddle.photon-client/client
       ~(r/emit (gensym) client)
       (quote ~server))))

(defmacro vars "
  Turns an arbitrary number of symbols resolving to vars into a map associating the fully qualified symbol
  of this var to the value currently bound to this var.
  " [& forms] (c/vars &env forms))

(cc/defn merge-vars
  ([fa fb]
   (cc/fn [not-found ident]
     (let [a (fa not-found ident)]
       (if (= not-found a)
         (fb not-found ident)
         a))))
  ([fa fb & fs]
   (reduce merge-vars (merge-vars fa fb) fs)))


(def eval "Takes a resolve map and a program, returns a booting function.
  The booting function takes
  * as first argument a function Any->Task[Unit] returned task writes the value on the wire.
  * as second argument a flow producing the values read on the wire.
  and returning a task that runs the local reactor."
  r/eval)

(def hook r/hook)
(def bind r/bind)
(def with r/with)

(defmacro ^:deprecated main "
  Takes a photon program and returns a pair
  * the first item is the local booting function (cf eval)
  * the second item is the remote program.
  " [& body]
  (-> (c/analyze &env (cons 'do body))
    (update 0 (partial r/emit (gensym)))
    (update 1 (partial list 'quote))))

(cc/defn pair [c s]
  (let [c->s (m/rdv)
        s->c (m/rdv)]
    (m/join {}
      (s (comp s->c io/encode) (m/join io/decode c->s))
      (c (comp c->s io/encode) (m/join io/decode s->c)))))

(defmacro local
  "Single peer loopback system without whitelist. Returns boot task."
  [& body]
                                        ; use compiler (client) because no need for exports
  (let [[client server] (c/analyze &env `(do ~@body))]
    `(pair ~(r/emit (gensym) client) ~(r/emit (gensym) server))))

(defmacro local-with
  "Single peer loopback system with whitelist. Returns boot task."
  [vars & body]
  (let [[client server] (c/analyze &env `(do ~@body))]
    `(pair ~(r/emit (gensym) client) (r/eval ~vars (quote ~server)))))

(defmacro run "test entrypoint without whitelist." [& body]
  `((local ~@body) (cc/fn [_#]) (cc/fn [_#])))

(defmacro run-with "test entrypoint with whitelist." [vars & body]
  `((local-with ~vars ~@body) (cc/fn [_#]) (cc/fn [_#])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;         EXPERIMENTAL ZONE             ;;
;;                                       ;;
;; Everything below should be considered ;;
;; guilty until proven innocent          ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(cc/defn ^:no-doc continuous "EXPERIMENTAL"
  ([>x] (continuous nil >x))
  ([init >x] (m/relieve {} (m/reductions {} init >x))))

(defmacro ^:no-doc forget
  "EXPERIMENTAL
  Like `do` but returs `nil` once, then never return again."
  [& body]
  `(new (->> (hyperfiddle.photon/fn [] ~@body)
             (m/eduction (map (constantly nil)) (dedupe))
             (m/reductions {} nil)
             (m/relieve {}))))

(cc/defn failure? [x] (instance? Failure x))

(cc/defn bypass-on "Return a transducer feeding values into `xf` only if they match `pred`, return them unchanged otherwise."
  ([pred xf]
   (cc/fn [rf]
     (let [xf (xf rf)]
       (cc/fn
         ([] (xf))
         ([result] (xf result))
         ([result input]
          (if (pred input)
            (rf result input)
            (xf result input)))))))
  ([pred xf coll] (sequence (bypass-on pred xf) coll)))

(cc/defn ^:no-doc newest "EXPERIMENTAL" [>left >right] (m/ap (m/?< (m/amb= >left >right))))

(def current* (partial m/eduction (take 1)))
(defmacro current "Copy the current value (only) and then terminate" [x]  ; TODO rename `constant`, `stable`?
  ;; what does Photon do on terminate? TBD
  ;; L: terminating a continuous flow means the value won't change anymore, so that's OK
  `(new (current* (p/fn [] ~x))))

(cc/defn wrap* [f & args]
  #?(:clj
     (->> (m/ap (m/? (m/via m/blk (apply f args))))
          (m/reductions {} (Failure. (Pending.)))
          (m/relieve {}))))

(defmacro wrap "Run blocking function (io-bound) on a threadpool"
  [f & args]
  `(new (wrap* ~f ~@args)))

(cc/defn ^:no-doc empty?
  "A task completing with true on first successful transfer of given flow, or false
if it completes without producing any value."
  [& args]
  (apply m/reduce (constantly (reduced false)) true args))

(cc/defn ^:no-doc first-or "A task completing with the value of the first successful transfer of given flow,
or a provided value if it completes without producing any value."
  [& args]
  (apply m/reduce (comp reduced {}) args))

(cc/defn ^:no-doc fsm
  "A continuous time impulse as a discreet flow. This is a state machine. It first
  emit `init`, then the first value of the `>values` discreet flow, called the
  impulse. The impulse is expected to be acknowledge soon by a new value in
  `>control`, at which point it restart emitting `init`.

   Start ———> 1. emit `init`
          |   2. listen to `>values`, wait for a value
          |
          |   3. emit first value of `>values`           |
          |    . stop listening to `>values`             | Toggles
          |    . listen to `>control`, wait for a value  |
          |
           —— 4. stop listening to `>control`
               . discard value
               . GOTO 1.

   Time ——————— 0 ———— 1 ———— 2 ————3——————————>
                |
               -|       ————————————
   >values      |      |            |
               -|——————              ——————————
               -|               —————————
   >control     |              |         |
               -|——————————————           —————
             v -|       ———————      ————
   result       |      |       |    |    |
          init -|——————         ————      —————
                |
  "
  [init >control >values]
  (m/ap
    (loop []
      (m/amb init
        (if-some [e (m/? >values)]
          (m/amb e (if (m/? >control) (m/amb) (recur)))
          (m/amb))))))

(cc/defn ^:no-doc impulse* [down-value tier >ack >xs]
  (fsm down-value
    (empty? (m/eduction (drop 1) (with tier >ack)))
    (first-or down-value >xs)))

(defmacro impulse
  "Translates a discrete event stream `>xs` into an equivalent continuous signal of impulses. Each impulse will stay
   'up' until it is sampled and acknowledged by signal `ack`. (Thus the duration of the impulse depends on sampling
   rate.) Upon ack, the impulse restarts from nil.

   Useful for modeling discrete events in Photon's continuous time model."
  ([ack >xs]
   `(impulse nil ~ack ~>xs))
  ([down-value ack >xs]
   `(new (bind (partial impulse* ~down-value) (hyperfiddle.photon/fn [] ~ack) ~>xs))))

;; Core.async interop.
;; Photon doesn't depend on core.async, this interop should move to a separate namespace or repo.
;; Keeping it here for simple, straight-to-the-point user demos.

(cc/defn chan-read
  "Return a task taking a value from `chan`. Retrun nil if chan is closed. Does
   not close chan, and stop reading from it when cancelled."
  [chan]
  (cc/fn [success failure] ; a task is a 2-args function, success and failure are callbacks.
    (let [cancel-chan (a/chan)] ; we will put a value on this chan to cancel reading from `chan`
      (a/go (let [[v port] (a/alts! [chan cancel-chan])] ; race between two chans
              (if (= port cancel-chan) ; if the winning chan is the cancelation one, then task has been cancelled
                (failure (Cancelled.)) ; task has been cancelled, must produce a failure state
                (success v) ; complete task with value from chan
                )))
      ;; if this task is cancelled by its parent process, close the cancel-chan
      ;; which will make cancel-chan produce `nil` and cause cancellation of read on `chan`.
      #(a/close! cancel-chan))))

(cc/defn chan->flow
  "Produces a discreet flow from a core.async `channel`"
  [channel]
  (m/ap ; returns a discreet flow
    (loop []
      (if-some [x (m/? (chan-read channel))] ; read one value from `channel`, waiting until `channel` produces it
        ;; We succesfully read a non-nil value, we use `m/amb` with two
        ;; branches. m/amb will fork the current process (ap) and do two things
        ;; sequencially, in two branches:
        ;; - return x, meaning `loop` ends and return x, ap will produce x
        ;; - recur to read the next value from chan
        (m/amb x (recur))
        ;; `channel` producing `nil` means it's been closed. We want to
        ;; terminate this flow without producing any value (not even nil), we
        ;; use (m/amb) which produces nothing and terminates immediately. The
        ;; parent m/ap block has nothing to produce anymore and will also
        ;; terminate.
        (m/amb)))))

(defmacro use-channel ;; TODO rename
  ([chan] `(use-channel nil ~chan))
  ([init chan] `(new (m/reductions {} ~init (chan->flow ~chan)))))

;; --------------------------------------

(defmacro def
  ([symbol] `(hyperfiddle.photon/def ~symbol ::c/unbound))
  ([symbol docstring init]
   (assert (string? docstring))
   (#'def &form &env (vary-meta symbol assoc :doc docstring) init))
  ([symbol init]
   ;; GG: Expand to an unbound var with body stored in ::c/node meta.
   ;;     Clojure compiler will analyze vars metas, which would analyze form as clojure, so we quote it.
   ;;     ClojureScript do not have vars at runtime and will not analyze or emit vars meta. No need to quote.
   `(def ~(vary-meta symbol assoc ::c/node (if (:js-globals &env) init `(quote ~init))))))

;; TODO self-refer
(defmacro fn [name? & [args & body]]
  (let [[name? args body] (if (symbol? name?) [name? args body]
                              [nil name? (cons args body)])]
    `(::c/closure
      (let [~@(interleave args (next c/arg-sym))]
        ~@body)
      ~(merge {::dbg/name name?, ::dbg/args args, ::dbg/type :reactive-fn}
         (select-keys (meta &form) [:file :line])
         (select-keys (meta name?) [::dbg/type :file :line])))))

; syntax quote doesn't qualify special forms like 'def
(defmacro defn [sym & fdecl]
  (let [[_defn sym' & _] (macroexpand `(cc/defn ~sym ~@fdecl))] ; GG: Support IDE documentation on hover
    `(hyperfiddle.photon/def ~sym' (hyperfiddle.photon/fn ~(vary-meta sym' merge {::dbg/type :reactive-defn}
                                                             (meta &form)
                                                             (meta sym))
                                     ~@(if (string? (first fdecl)) ; GG: skip docstring
                                         (rest fdecl)
                                         fdecl)))))

(defmacro for-by [kf bindings & body]
  (if-some [[s v & bindings] (seq bindings)]
    `(let [xs# ~v]
       (new (r/bind map-by ~kf
              ~(->> body
                 (list* `for-by kf bindings)
                 (list `let [s (second c/arg-sym)])
                 (list `fn [])
                 (list `partial (list 'def (second c/arg-sym))))
              (::c/lift xs#))))
    (cons `do body)))

(defmacro for [bindings & body]
  `(hyperfiddle.photon/for-by identity ~bindings ~@body))

(cc/defn ^:no-doc watchable? [x]
  #?(:clj (instance? clojure.lang.IRef x)
     :cljs (satisfies? IWatchable x)))

(cc/defn ^:no-doc checked-watch [!x]
  (assert (watchable? !x) "Provided argument is not Watchable.")
  (m/watch !x))

(defn Watch [!x]
  (new (checked-watch !x)))

(defmacro watch "for tutorials (to delay teaching constructor syntax); m/watch is also idiomatic"
  [!x] `(new (checked-watch ~!x)))

(cc/defn debounce-discreet
  ([delay flow] (debounce-discreet delay nil flow))
  ([delay init flow] (m/reductions {} init (m/ap (let [x (m/?< flow)]
                                                   (try (m/? (m/sleep delay x))
                                                        (catch Cancelled _ (m/amb))))))) )

(defmacro debounce
  "Debounce a continous flow by `delay` milliseconds."
  [delay flow]
  `(new (->> (fn [] ~flow)
             (debounce-discreet ~delay)
             (m/relieve {}))))

(defmacro remote [& body]
  (if (= 1 (count body))
    `(unquote-splicing ~@body)
    `(unquote-splicing (do ~@body))))

(defmacro client [& body]
  `(::c/client (do ~@body) ~(assoc (meta &form) ::dbg/type :transfer, ::dbg/name `client)))

(defmacro server [& body]
  `(::c/server (do ~@body) ~(assoc (meta &form) ::dbg/type :transfer, ::dbg/name `server)))

(hyperfiddle.photon/def trace "In a `catch` block, bound by the runtime to the current stacktrace. A photon stacktrace is an ExceptionInfo. Use `hyperfiddle.photon.debug/stack-trace` to get a string representation." nil)
