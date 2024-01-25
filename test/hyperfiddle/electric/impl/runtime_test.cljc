(ns hyperfiddle.electric.impl.runtime-test
  (:require [missionary.core :as m]
            [hyperfiddle.incseq :as i]
            [hyperfiddle.electric :as-alias e]
            #?(:clj [hyperfiddle.electric.impl.lang-de2 :as l])
            [hyperfiddle.electric.impl.runtime-de :as r]
            [hyperfiddle.rcf :as rcf :refer [tests %]]))

(defn on-diff! [cb incseq]
  ((m/reduce (fn [_ d] (cb d) nil) nil incseq)
   cb (fn [e] #?(:clj (.printStackTrace ^Throwable e)
                 :cljs (.error js/console e)))))

(defmacro root-frame [form]
  `(r/root-frame {::Main ~(l/compile ::Main form
                            (assoc (l/normalize-env &env)
                              ::l/peers {:client :clj, :server :clj}))}
     ::Main))

(tests
  (on-diff! rcf/tap (root-frame "hello electric"))
  % := {:grow 1, :degree 1, :shrink 0, :permutation {}, :change {0 "hello electric"}, :freeze #{0}}
  % := nil)

(tests
  (def !x (atom :foo))
  (on-diff! rcf/tap (root-frame (::l/join (i/fixed (m/watch !x)))))
  % := {:degree 1, :permutation {}, :grow 1, :shrink 0, :change {0 :foo}, :freeze #{}}
  (reset! !x :bar)
  % := {:degree 1, :permutation {}, :grow 0, :shrink 0, :change {0 :bar}, :freeze #{}})