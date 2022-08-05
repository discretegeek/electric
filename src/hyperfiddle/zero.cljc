(ns hyperfiddle.zero
  "Photon standard library (experimental, idioms are not established yet).
  Badly named, should be moved into photon namespace."
  (:refer-clojure :exclude [empty? time])
  (:require [missionary.core :as m]
            [hyperfiddle.photon :as p]
            [hyperfiddle.rcf :refer [tests ! % with]])
  #?(:cljs (:require-macros [hyperfiddle.zero :refer [pick current]])))

(defn state [init-value]
  (let [!state (atom init-value)
        >state (m/eduction (dedupe) (m/watch !state))]
    (fn
      ([v] (reset! !state v))
      ([n t] (>state n t)))))



(defmacro pick "head for flows. return first or nothing. Note that in Clojure you can't
return nothing (you return nil) but in flows nothing is different than nil." [t]
  `(let [x# (m/? t)]
     (case x# ::empty (m/amb) x#)))


(defmacro current "Copy the current value (only) and then terminate" [x]
  ; what does Photon do on terminate? TBD
  ; L: terminating a continuous flow means the value won't change anymore, so that's OK
  `(new (m/eduction (take 1) (p/fn [] ~x))))

(comment
  "scratch related to continuous time events with slow consumers"
  (defn differences [rf]
    (let [state (volatile! 0)]
      (fn
        ([] (rf))
        ([r] (rf r))
        ([r x]
         (let [d (- x @state)]
           (assert (not (neg? d)))
           (vreset! state x)
           (rf r d))))))

  (tests
    "Five effects driven by CT counter where sampling is slow so discrete events were missed"
    (sequence differences [0 2 3 5]) := [0 2 1 2])

  (defn foreach-tick [<x f]
    (->> (m/ap
           (dotimes [x' (m/?> (m/eduction differences <x))]
             (f)))
         (m/reductions {} nil)                              ; run discrete flow for effect
         (m/relieve {})))

  (defmacro do-step [x & body]
    `(foreach-tick (p/fn [] ~x)
                   ~@body
                   #_(fn [] ~@body)))

  (def drive (comp differences (mapcat (fn [x] (repeat x nil))))) ; transducer
  (tests (sequence drive [0 2 3 5]) := [nil nil nil nil nil])

  (defn foreach-tick [<x f]
    (->> (m/ap
           (let [_ (m/?> (m/eduction z/drive <x))]
             (f)))
         (m/reductions {} nil)                              ; produce nil in discrete time for effect
         (m/relieve {}))))

#?(:cljs
   (deftype Clock [^:mutable ^number raf
                   ^:mutable callback
                   terminator]
     IFn                                                    ; cancel
     (-invoke [_]
       (if (zero? raf)
         (set! callback nil)
         (do (.cancelAnimationFrame js/window raf)
             (terminator))))
     IDeref                                                 ; sample
     (-deref [_]
       ; lazy clock, only resets once sampled
       (if (nil? callback)
         (terminator)
         (set! raf (.requestAnimationFrame js/window callback))) ; RAF not called until first sampling
       ::tick)))

(def <clock "lazy & efficient logical clock that schedules no work unless sampled."
  #?(:cljs (fn [n t]
             (let [cancel (->Clock 0 nil t)]
               (set! (.-callback cancel)
                     (fn [_] (set! (.-raf cancel) 0) (n)))
               (n) cancel))

     :clj  (m/ap (loop [] (m/amb ::tick (do (m/? (m/sleep 1)) (recur)))))))

(p/def clock #_"lazy logical clock" (new (identity <clock))) ; syntax gap - static def >clock could be a class on ClojureScript

(tests
  "logical clock"
  (let [dispose (p/run (! clock))]
    [% % %] := [::tick ::tick ::tick]
    (dispose)))

(defn system-time-ms [_] #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(p/def time #_"ms since Jan 1 1970" (new (m/sample system-time-ms <clock)))

(tests
  "millisecond time as a stable test"
  (let [dispose (p/run (! time))]
    [% % %] := [_ _ _]
    (map int? *1) := [true true true]
    (dispose)))

(comment
  ; This ticker will skew and therefore is not useful other than as an example
  ; L: this is dangerous because if you write a program that does (prn ticker) it will run forever
  (p/def ticker (new (->> <clock
                          (m/eduction (map (constantly 1)))
                          (m/reductions + 0))))

  (tests (with (p/run (! ticker)) [% % %] := [1 2 3])))
