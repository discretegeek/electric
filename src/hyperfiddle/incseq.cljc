(ns ^{:doc "
## Permutations

A [permutation](https://en.wikipedia.org/wiki/Permutation) describes a rearrangement of elements in a vector. It is
represented as a map associating each index to its image, where absence of entry implicitly defines a fixed point.
Permutations form a group with `compose`.
* closure : if `p` and `q` are permutations then `(compose p q)` is a permutation
* associativity : `(= (compose p (compose q r)) (compose (compose p q) r) (compose p q r))`
* identity : `(= p (compose p {}) (compose {} p))`
* invertibility : `(= {} (compose p (inverse p)) (compose (inverse p) p))`

## Sequence diffs

A sequence diff describes how to update a finite sequence of states. Each transformation performs 5 successive actions :
1. grow the vector by allocating new slots at the end
2. rearrange vector elements
3. shrink the vector by discarding slots at the end
4. change the state of some vector elements to a new value
5. mark the state of some vector elements as frozen

It is represented as a map with 6 entries :
* `:grow` : non-negative integer, the number of slots to append
* `:degree` : non-negative integer, the vector size after growing and before shrinking
* `:shrink` : non-negative integer, the number of slots to remove
* `:change` : a map from non-negative integers to arbitrary values, associating each position to its new state
* `:freeze` : a set of non-negative integers, the positions of frozen states
* `:permutation` : the permutation describing how to rearrange vector elements

Diffs form a semigroup with `combine`.
* closure : if `d` and `e` are diffs then `(combine d e)` is a diff
* associativity : `(= (combine d (combine e f)) (combine (combine d e) f) (combine d e f))`

## Incremental sequences

An incremental sequence describes a variable finite sequence of states. It is represented as a flow producing
successive sequence diffs. Incremental sequences are applicative functors with `latest-product` and monads with
`latest-concat`.
"} hyperfiddle.incseq
  (:refer-clojure :exclude [cycle int-array])
  (:require [hyperfiddle.rcf :refer [tests]])
  #?(:clj (:import (clojure.lang IFn IDeref))))


;; internal use

(defn nop [])


(defn int-array ^ints [n]
  #?(:clj (make-array Integer/TYPE n)
     :cljs (let [a (make-array n)]
             (dotimes [i n] (aset a i 0)) a)))


(defn acopy [source source-offset target target-offset length]
  #?(:clj (System/arraycopy source source-offset target target-offset length)
     :cljs (dotimes [i length]
             (aset target (+ target-offset i)
               (aget source (+ source-offset i))))))


(deftype Ps [state cancel transfer]
  IFn
  (#?(:clj invoke :cljs -invoke) [_]
    (cancel state))
  IDeref
  (#?(:clj deref :cljs -deref) [_]
    (transfer state)))


;; public API

(defn inverse "
Returns the inverse of permutation `p`.
" [p] (into {} (map (juxt val key)) p))


(defn cycle "
Returns the cyclic permutation denoted by given sequence of indices.
" ([_] {})
  ([i & js] (zipmap `(~i ~@js) `(~@js ~i))))


(defn rotation "
Returns the permutation moving an item from index `i` to index `j` and shifting items in-between.

```clojure
(= (rotation i j) (inverse (rotation j i)))
```
" [i j]
  (case (compare i j)
    -1 (apply cycle (range i (inc j) +1))
    0 {}
    +1 (apply cycle (range i (dec j) -1))))


(defn split-swap "
Returns the permutation swapping two contiguous blocks of respective sizes `l` and `r` at index `i`.

```clojure
(= (split-swap i l r) (inverse (split-swap i r l)))
```
" [i l r]
  (let [l (int l)
        r (int r)]
    (case l
      0 {}
      (case r
        0 {}
        (let [j (unchecked-add-int i l)
              k (unchecked-add-int j r)]
          (zipmap (range i k)
            (concat (range j k)
              (range i j))))))))


(defn arrange "
Arranges elements of `v` according to permutation `p`.
" [v p]
  (persistent!
    (reduce-kv
      (fn [r i j]
        (assoc! r i (nth v j)))
      (transient v) p)))


(defn decompose "
Decompose permutation `p` as a product of disjoint cycles, represented as a set of vectors. 1-cycles matching fixed
points are omitted, the size of each cycle is therefore at least 2.
" [p]
  (loop [p p
         cs #{}]
    (case p
      {} cs
      (let [[i j] (first p)]
        (let [c (loop [c [i]
                       j j]
                  (let [c (conj c j)
                        j (p j)]
                    (if (== i j)
                      c (recur c j))))]
          (recur (apply dissoc p c)
            (conj cs c)))))))


(defn compose "
Returns the composition of given permutations.
" ([] {})
  ([x] x)
  ([x y]
   (reduce-kv
     (fn [r i j]
       (let [k (y j j)]
         (if (== i k)
           (dissoc r i)
           (assoc r i k))))
     y x))
  ([x y & zs]
   (reduce compose (compose x y) zs)))


(defn order "
Returns the [order](https://en.wikipedia.org/wiki/Order_(group_theory)) of permutation `p`, i.e. the smallest positive
integer `n` such that `(= {} (apply compose (repeat n p)))`.
" [p]
  (loop [o 1, q p]
    (case q
      {} o
      (recur (unchecked-inc o)
        (compose p q)))))


(defn involution? "
Returns `true` if permutation `p` is an
[involution](https://en.wikipedia.org/wiki/Involution_(mathematics)#Group_theory), i.e. its order is 2.
" [p] (and (not= {} p) (= {} (compose p p))))


(defn transposition? "
Returns `true` if permutation `p` is a
[transposition](https://en.wikipedia.org/wiki/Cyclic_permutation#Transpositions), i.e. it is a 2-cycle.
" [p] (= 2 (count p)))


(defn recompose "
Reconstructs the permutation defined by given set of disjoint cycles.
" [cycles]
  (->> cycles
    (eduction (map (partial apply cycle)))
    (reduce compose (compose))))


(defn empty-diff "
Return the empty diff for `n`-item collection.
" [n] {:degree n :grow 0 :shrink 0 :permutation {} :change {} :freeze #{}})


(def ^{:doc "
Returns a flow producing the successive diffs of given continuous flow of collections, stabilized by given key function.
"} diff-by
  (let [slot-notifier 0
        slot-terminator 1
        slot-stepfn 2
        slot-busy 3
        slot-done 4
        slot-process 5
        slot-value 6
        slots 7]
    (letfn [(cancel [^objects state]
              ((aget state slot-process)))
            (transfer [^objects state]
              ((locking state
                 (try (if (aget state slot-done)
                        (do (aset state slot-value ((aget state slot-stepfn)))
                            (aget state slot-terminator))
                        (do (aset state slot-value ((aget state slot-stepfn) @(aget state slot-process)))
                            (if (aset state slot-busy (not (aget state slot-busy)))
                              (aget state slot-notifier) nop)))
                      (catch #?(:clj Throwable :cljs :default) e
                        (aset state slot-notifier nil)
                        (aset state slot-value e)
                        (cancel state)
                        (loop []
                          (if (aget state slot-done)
                            (aget state slot-terminator)
                            (if (aset state slot-busy (not (aget state slot-busy)))
                              (do (try @(aget state slot-process)
                                       (catch #?(:clj Throwable :cljs :default) _))
                                  (recur)) nop)))))))
              (let [x (aget state slot-value)]
                (aset state slot-value nil)
                (if (nil? (aget state slot-notifier))
                  (throw x) x)))
            (ready [^objects state]
              ((locking state
                 (if (aset state slot-busy (not (aget state slot-busy)))
                   (if-some [cb (aget state slot-notifier)]
                     cb (loop []
                          (if (aget state slot-done)
                            (aget state slot-terminator)
                            (do (try @(aget state slot-process)
                                     (catch #?(:clj Throwable :cljs :default) _))
                                (if (aset state slot-busy (not (aget state slot-busy)))
                                  (recur) nop))))) nop))))
            (scan [ctor flow]
              (fn [n t]
                (let [state (object-array slots)]
                  (aset state slot-notifier n)
                  (aset state slot-terminator t)
                  (aset state slot-stepfn (ctor))
                  (aset state slot-busy false)
                  (aset state slot-done false)
                  (aset state slot-process
                    (flow #(ready state)
                      #(do (aset state slot-done true)
                           (ready state))))
                  (->Ps state cancel transfer))))
            (differ [kf]
              #(let [state (doto (object-array 2)
                             (aset 0 [])
                             (aset 1 {}))]
                 (fn
                   ([]
                    (let [degree (count (aget state 0))]
                      (aset state 0 nil)
                      (aset state 1 nil)
                      {:grow 0 :shrink 0 :permutation {} :change {} :degree degree :freeze (set (range degree))}))
                   ([xs]
                    (let [prev-vec (aget state 0)
                          prev-idx (aget state 1)
                          _ (aset state 0 [])
                          _ (aset state 1 {})
                          size-before (count prev-vec)
                          [degree permutation change]
                          (reduce
                            (fn [[degree permutation change] x]
                              (let [curr-vec (aget state 0)
                                    curr-idx (aget state 1)
                                    i (count curr-vec)
                                    k (kf x)
                                    [d y j]
                                    (or (some
                                          (fn [n]
                                            (let [j (permutation n n)]
                                              (when-not (< j i)
                                                [degree (nth prev-vec n) j])))
                                          (prev-idx k)) [(inc degree) state degree])]
                                (aset state 0 (conj curr-vec x))
                                (aset state 1 (assoc curr-idx k (conj (curr-idx k []) i)))
                                [d (compose permutation (rotation i j))
                                 (if (= x y) change (assoc change i x))]))
                            [size-before {} {}] xs)
                          size-after (count (aget state 0))]
                      (assoc (empty-diff degree)
                        :grow (unchecked-subtract-int degree size-before)
                        :shrink (unchecked-subtract-int degree size-after)
                        :permutation (inverse permutation)
                        :change change))))))]
      (fn [kf flow] (scan (differ kf) flow)))))


(def ^{:doc "
Returns the application of diff `d` to vector `v`.
"} patch-vec
  (let [grow! (fn [v n]
                (reduce conj! v (repeat n nil)))
        shrink! (fn [v n]
                  (loop [i 0, v v]
                    (if (< i n)
                      (recur (inc i)
                        (pop! v)) v)))
        change! (fn [r c]
                  (reduce-kv assoc! r c))
        cycles! (partial reduce
                  (fn [v c]
                    (let [i (nth c 0)
                          x (nth v i)]
                      (loop [v v
                             i i
                             k 1]
                        (let [j (nth c k)
                              v (assoc! v i (nth v j))
                              k (unchecked-inc-int k)]
                          (if (< k (count c))
                            (recur v j k)
                            (assoc! v j x)))))))]
    (fn
      ([] [])
      ([v d]
       (-> v
         (transient)
         (grow! (:grow d))
         (cycles! (decompose (:permutation d)))
         (shrink! (:shrink d))
         (change! (:change d))
         (persistent!))))))


(defn ^{:doc "
Returns the diff applying given diffs successively.
"} combine
  ([x] x)
  ([x y]
   (let [px (:permutation x)
         py (:permutation y)
         dx (:degree x)
         dy (:degree y)
         cx (:change x)
         cy (:change y)
         fx (:freeze x)
         fy (:freeze y)
         degree (unchecked-add dy (:shrink x))
         size-before (unchecked-subtract dx (:grow x))
         size-between (unchecked-subtract dy (:grow y))
         size-after (unchecked-subtract dy (:shrink y))]
     (loop [i size-after
            d degree
            p (compose py
                (split-swap size-between
                  (unchecked-subtract degree dy)
                  (unchecked-subtract degree dx)) px)
            c (reduce-kv assoc!
                (reduce-kv
                  (fn [r i j]
                    (if (contains? cx j)
                      (assoc! r i (cx j)) r))
                  (reduce dissoc! (transient cx)
                    (vals py)) py) cy)
            f (reduce conj!
                (reduce-kv
                  (fn [r i j]
                    (if (contains? fx j)
                      (conj! r i) r))
                  (reduce disj! (transient fx)
                    (vals py)) py) fy)]
       (if (< i d)
         (let [j (p i i)
               c (dissoc! c i)
               f (disj! f i)]
           (if (< j size-before)
             (recur (unchecked-inc i) d p c f)
             (recur i (unchecked-dec d)
               (compose (rotation i d)
                 p (rotation d j)) c f)))
         {:degree      d
          :permutation p
          :grow        (unchecked-subtract d size-before)
          :shrink      (unchecked-subtract d size-after)
          :change      (persistent! c)
          :freeze      (persistent! f)}))))
  ([x y & zs] (reduce combine (combine x y) zs)))


(def ^{:doc "
Returns the incremental sequence defined by the fixed collection of given continuous flows.
A collection is fixed iff its size is invariant and its items are immobile.
"} fixed
  (let [slot-notifier 0
        slot-terminator 1
        slot-processes 2
        slot-ready 3
        slot-push 4
        slot-live 5
        slot-value 6
        slots 7]
    (letfn [(empty-cancel [_])
            (empty-transfer [t]
              (t) {:grow 0
                   :shrink 0
                   :degree 0
                   :permutation {}
                   :change {}
                   :freeze #{}})
            (empty-coll [n t]
              (n) (->Ps t empty-cancel empty-transfer))
            (input-ready [^objects state item]
              ((locking state
                 (let [^objects processes (aget state slot-processes)
                       ^ints ready (aget state slot-ready)
                       arity (alength processes)
                       item (int item)]
                   (if-some [i (aget state slot-push)]
                     (do (aset state slot-push (identity (rem (unchecked-inc-int i) arity)))
                         (aset ready i item) nop)
                     (do (aset state slot-push (identity (rem 1 arity)))
                         (if-some [cb (aget state slot-notifier)]
                           (do (aset ready 0 item) cb)
                           (loop [item item
                                  i (rem 1 arity)]
                             (if (neg? item)
                               (aset state slot-live (dec (aget state slot-live)))
                               (try @(aget processes item) (catch #?(:clj Throwable :cljs :default) _)))
                             (let [item (aget ready i)]
                               (if (== arity item)
                                 (do (aset state slot-push nil)
                                     (if (zero? (aget state slot-live))
                                       (aget state slot-terminator) nop))
                                 (do (aset ready i arity)
                                     (recur item (rem (unchecked-inc-int i) arity)))))))))))))
            (item-spawn [^objects state item flow]
              (let [^objects processes (aget state slot-processes)
                    arity (alength processes)]
                (aset processes item
                  (flow #(input-ready state item)
                    #(input-ready state (unchecked-subtract-int item arity)))))
              state)
            (cancel [^objects state]
              (let [^objects processes (aget state slot-processes)]
                (dotimes [item (alength processes)] ((aget processes item)))))
            (transfer [^objects state]
              (let [^objects processes (aget state slot-processes)
                    ^ints ready (aget state slot-ready)
                    arity (alength processes)
                    item (aget ready 0)]
                (aset ready 0 arity)
                ((locking state
                   (loop [item item
                          i (rem 1 arity)]
                     (if (nil? (aget state slot-notifier))
                       (if (neg? item)
                         (aset state slot-live (dec (aget state slot-live)))
                         (try @(aget processes item) (catch #?(:clj Throwable :cljs :default) _)))
                       (let [diff (aget state slot-value)]
                         (aset state slot-value
                           (if (neg? item)
                             (do (aset state slot-live (dec (aget state slot-live)))
                                 (update diff :freeze conj (unchecked-add-int arity item)))
                             (try (update diff :change assoc item @(aget processes item))
                                  (catch #?(:clj Throwable :cljs :default) e
                                    (aset state slot-notifier nil)
                                    (cancel state) e))))))
                     (let [item (aget ready i)]
                       (if (== arity item)
                         (do (aset state slot-push nil)
                             (if (zero? (aget state slot-live))
                               (aget state slot-terminator) nop))
                         (do (aset ready i arity)
                             (recur item (rem (unchecked-inc-int i) arity))))))))
                (let [x (aget state slot-value)]
                  (aset state slot-value
                    {:grow 0
                     :shrink 0
                     :degree arity
                     :permutation {}
                     :change {}
                     :freeze #{}})
                  (if (nil? (aget state slot-notifier))
                    (throw x) x))))]
      (fn
        ([] empty-coll)
        ([item & items]
         (let [items (into [item] items)]
           (fn [n t]
             (let [state (object-array slots)
                   arity (count items)
                   ready (int-array arity)]
               (dotimes [i arity] (aset ready i arity))
               (aset state slot-notifier n)
               (aset state slot-terminator t)
               (aset state slot-processes (object-array arity))
               (aset state slot-ready ready)
               (aset state slot-live (identity arity))
               (aset state slot-value
                 {:grow        arity
                  :degree      arity
                  :shrink      0
                  :permutation {}
                  :change      {}
                  :freeze      #{}})
               (reduce-kv item-spawn state items)
               (->Ps state cancel transfer)))))))))


(def ^{:arglists '([f & incseqs])
       :doc "
Returns the incremental sequence defined by applying the cartesian product of items in given incremental sequences,
combined with given function.
"} latest-product
  (let [slot-notifier 0
        slot-terminator 1
        slot-combinator 2
        slot-processes 3
        slot-buffers 4
        slot-freezers 5
        slot-counts 6
        slot-ready 7
        slot-push 8
        slot-live 9
        slot-args 10
        slot-value 11
        slots 12]
    (letfn [(call [f ^objects args]
              (case (alength args)
                0  (f)
                1  (f (aget args 0))
                2  (f (aget args 0) (aget args 1))
                3  (f (aget args 0) (aget args 1) (aget args 2))
                4  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3))
                5  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4))
                6  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5))
                7  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6))
                8  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7))
                9  (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8))
                10 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9))
                11 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10))
                12 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11))
                13 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12))
                14 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13))
                15 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14))
                16 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14) (aget args 15))
                17 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14) (aget args 15) (aget args 16))
                18 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14) (aget args 15) (aget args 16) (aget args 17))
                19 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14) (aget args 15) (aget args 16) (aget args 17) (aget args 18))
                20 (f (aget args 0) (aget args 1) (aget args 2) (aget args 3) (aget args 4) (aget args 5) (aget args 6) (aget args 7) (aget args 8) (aget args 9) (aget args 10) (aget args 11) (aget args 12) (aget args 13) (aget args 14) (aget args 15) (aget args 16) (aget args 17) (aget args 18) (aget args 19))
                (apply f (aclone args))))
            (combine-indices [total-card degree r j]
              (eduction
                (mapcat (fn [k] (range k (unchecked-add-int k r))))
                (range (unchecked-multiply-int j r) total-card
                  (unchecked-multiply-int degree r))))
            (ensure-capacity [^objects freezers ^objects buffers item grow degree]
              (let [^ints freezer (aget freezers item)
                    n (bit-shift-left (alength freezer) 5)]
                (when (< n degree)
                  (loop [n n]
                    (let [n (bit-shift-left n 1)]
                      (if (< n degree)
                        (recur n)
                        (let [a (int-array (bit-shift-right n 5))
                              s (-> (unchecked-subtract-int degree grow)
                                  (bit-shift-right 5)
                                  (unchecked-inc-int))]
                          #?(:clj (System/arraycopy freezer 0 a 0 s)
                             :cljs (dotimes [i s] (aset a i (aget freezer i))))
                          (aset freezers item a)))))))
              (let [^objects buffer (aget buffers item)
                    n (alength buffer)]
                (when (< n degree)
                  (loop [n n]
                    (let [n (bit-shift-left n 1)]
                      (if (< n degree)
                        (recur n)
                        (let [a (object-array n)
                              s (unchecked-subtract-int degree grow)]
                          #?(:clj (System/arraycopy buffer 0 a 0 s)
                             :cljs (dotimes [i s] (aset a i (aget buffer i))))
                          (aset buffers item a))))))))
            (compute-permutation [l r grow degree shrink permutation]
              (let [lr (unchecked-multiply l r)
                    size-after (unchecked-subtract degree shrink)
                    size-before (unchecked-subtract degree grow)
                    r-create (unchecked-multiply r grow)
                    r-degree (unchecked-multiply r degree)
                    r-remove (unchecked-multiply r shrink)
                    r-size-before (unchecked-multiply r size-before)
                    r-size-after (unchecked-multiply r size-after)
                    lr-size-after (unchecked-multiply lr size-after)
                    lr-degree (unchecked-multiply lr degree)
                    create-offset (unchecked-subtract lr-degree r-create)
                    remove-offset (unchecked-subtract lr-size-after r-size-after)]
                (compose
                  (reduce compose {}
                    (eduction
                      (map (fn [k]
                             (split-swap
                               (+ r-size-after (* k r-degree)) r-remove
                               (- remove-offset (* k r-size-after)))))
                      (range l)))
                  permutation
                  (reduce compose {}
                    (eduction
                      (map (fn [k]
                             (split-swap
                               (- create-offset (* k r-degree))
                               (* k r-size-before) r-create)))
                      (range l))))))
            (freeze! [^ints freezer i]
              (let [j (int (bit-shift-right i 5))
                    k (int (bit-and i (unchecked-dec (bit-shift-left 1 5))))]
                (aset freezer j (int (bit-set (aget freezer j) k)))))
            (unfreeze! [^ints freezer i]
              (let [j (int (bit-shift-right i 5))
                    k (int (bit-and i (unchecked-dec (bit-shift-left 1 5))))]
                (aset freezer j (int (bit-clear (aget freezer j) k)))))
            (frozen? [^ints freezer i]
              (let [j (bit-shift-right i 5)
                    k (bit-and i (unchecked-dec (bit-shift-left 1 5)))]
                (bit-test (aget freezer j) k)))
            (cancel [^objects state]
              (let [^objects processes (aget state slot-processes)]
                (dotimes [item (alength processes)] ((aget processes item)))))
            (transfer [^objects state]
              (let [^objects processes (aget state slot-processes)
                    ^objects freezers (aget state slot-freezers)
                    ^objects buffers (aget state slot-buffers)
                    ^objects args (aget state slot-args)
                    ^ints counts (aget state slot-counts)
                    ^ints ready (aget state slot-ready)
                    offset (bit-shift-right (alength counts) 1)
                    arity (alength processes)
                    f (aget state slot-combinator)
                    item (aget ready 0)]
                (aset ready 0 arity)
                ((locking state
                   (loop [item item
                          i (rem 1 arity)]
                     (if (nil? (aget state slot-notifier))
                       (try @(aget processes item) (catch #?(:clj Throwable :cljs :default) _))
                       (aset state slot-value
                         (try (let [diff (aget state slot-value)
                                    count-index (unchecked-add-int offset item)
                                    {:keys [grow shrink degree permutation change freeze]} @(aget processes item)]
                                (ensure-capacity freezers buffers item grow degree)
                                (let [^ints freezer (aget freezers item)
                                      ^objects buffer (aget buffers item)
                                      size-before (unchecked-subtract-int degree grow)
                                      size-after (unchecked-subtract-int degree shrink)]
                                  (aset counts count-index size-after)
                                  (loop [i size-before]
                                    (when (< i degree)
                                      (aset buffer i buffer)
                                      (recur (unchecked-inc-int i))))
                                  (loop [l 1, r 1, i count-index]
                                    (case i
                                      1 (let [lr-size-after (aget counts 1)
                                              foreign-degree (unchecked-multiply-int l r)
                                              product-degree (unchecked-multiply-int degree foreign-degree)
                                              product-cycles (into #{}
                                                               (mapcat
                                                                 (fn [cycle]
                                                                   (let [k (nth cycle 0)
                                                                         x (aget buffer k)
                                                                         f (frozen? freezer k)
                                                                         l (reduce
                                                                             (fn [k l]
                                                                               (aset buffer k (aget buffer l))
                                                                               ((if (frozen? freezer l)
                                                                                  freeze! unfreeze!)
                                                                                freezer k) l)
                                                                             k (subvec cycle 1))]
                                                                     (aset buffer l x)
                                                                     ((if f freeze! unfreeze!)
                                                                      freezer k))
                                                                   (->> cycle
                                                                     (map (partial combine-indices product-degree degree r))
                                                                     (apply map vector))))
                                                               (decompose permutation))]
                                          (loop [i size-after]
                                            (when (< i degree)
                                              (unfreeze! freezer i)
                                              (aset buffer i nil)
                                              (recur (unchecked-inc-int i))))
                                          (combine diff
                                            (hash-map
                                              :grow (unchecked-multiply-int grow foreign-degree)
                                              :degree product-degree
                                              :permutation (compute-permutation l r grow degree shrink
                                                             (recompose product-cycles))
                                              :shrink (unchecked-multiply-int shrink foreign-degree)
                                              :change (persistent!
                                                        (reduce-kv
                                                          (fn [m k v]
                                                            (let [^objects buffer (aget buffers item)]
                                                              (if (= (aget buffer k) (aset buffer k v))
                                                                m (reduce (fn [m i]
                                                                            (loop [n i
                                                                                   j (alength buffers)]
                                                                              (let [j (unchecked-dec-int j)
                                                                                    c (aget counts (unchecked-add-int offset j))]
                                                                                (aset args j (aget ^objects (aget buffers j) (rem n c)))
                                                                                (if (pos? j)
                                                                                  (recur (quot n c) j)
                                                                                  (assoc! m i (call f args))))))
                                                                    m (combine-indices lr-size-after size-after r k)))))
                                                          (transient {}) change))
                                              :freeze (persistent!
                                                        (reduce
                                                          (fn [s k]
                                                            (freeze! (aget freezers item) k)
                                                            (reduce (fn [s i]
                                                                      (loop [n i
                                                                             j (alength freezers)]
                                                                        (let [j (unchecked-dec-int j)
                                                                              c (aget counts (unchecked-add-int offset j))]
                                                                          (if (frozen? (aget freezers j) (rem n c))
                                                                            (conj! s i)
                                                                            (if (pos? j)
                                                                              (recur (quot n c) j)
                                                                              s)))))
                                                              s (combine-indices lr-size-after size-after r k)))
                                                          (transient #{}) freeze)))))
                                      (let [j (bit-shift-right i 1)]
                                        (if (odd? i)
                                          (let [x (aget counts (unchecked-dec-int i))]
                                            (aset counts j (unchecked-multiply-int x (aget counts i)))
                                            (recur (unchecked-multiply-int x l) r j))
                                          (let [x (aget counts (unchecked-inc-int i))]
                                            (aset counts j (unchecked-multiply-int x (aget counts i)))
                                            (recur l (unchecked-multiply-int x r) j))))))))
                              (catch #?(:clj Throwable :cljs :default) e
                                (aset state slot-notifier nil)
                                (cancel state) e))))
                     (let [item (aget ready i)]
                       (if (== arity item)
                         (do (aset state slot-push nil)
                             (if (zero? (aget state slot-live))
                               (aget state slot-terminator) nop))
                         (do (aset ready i arity)
                             (recur item (rem (unchecked-inc-int i) arity))))))))
                (let [x (aget state slot-value)]
                  (aset state slot-value (empty-diff (aget counts 1)))
                  (if (nil? (aget state slot-notifier)) (throw x) x))))
            (terminated [^objects state]
              ((locking state
                 (if (zero? (aset state slot-live (dec (aget state slot-live))))
                   (if (zero? (aget state slot-push)) (aget state slot-terminator) nop) nop))))
            (input-ready [^objects state item]
              ((locking state
                 (let [^objects processes (aget state slot-processes)
                       ^ints ready (aget state slot-ready)
                       arity (alength processes)
                       item (int item)]
                   (if-some [i (aget state slot-push)]
                     (do (aset state slot-push (identity (rem (unchecked-inc-int i) arity)))
                         (aset ready i item) nop)
                     (do (aset state slot-push (identity (rem 1 arity)))
                         (if-some [cb (aget state slot-notifier)]
                           (do (aset ready 0 item) cb)
                           (loop [item item
                                  i (rem 1 arity)]
                             (try @(aget processes item) (catch #?(:clj Throwable :cljs :default) _))
                             (let [item (aget ready i)]
                               (if (== arity item)
                                 (do (aset state slot-push nil)
                                     (if (zero? (aget state slot-live))
                                       (aget state slot-terminator) nop))
                                 (do (aset ready i arity)
                                     (recur item (rem (unchecked-inc-int i) arity)))))))))))))
            (input-spawn [^objects state item flow]
              (let [^objects freezers (aget state slot-freezers)
                    ^objects buffers (aget state slot-buffers)
                    ^objects processes (aget state slot-processes)]
                (aset freezers item (int-array 1))
                (aset buffers item (object-array 1))
                (aset processes item
                  (flow #(input-ready state item)
                    #(terminated state))))
              state)]
      (fn [f & diffs]
        (let [diffs (vec diffs)]
          (fn [n t]
            (let [state (object-array slots)
                  arity (count diffs)
                  ready (int-array arity)]
              (dotimes [i arity] (aset ready i arity))
              (aset state slot-notifier n)
              (aset state slot-terminator t)
              (aset state slot-combinator f)
              (aset state slot-args (object-array arity))
              (aset state slot-buffers (object-array arity))
              (aset state slot-freezers (object-array arity))
              (aset state slot-processes (object-array arity))
              (aset state slot-ready ready)
              (aset state slot-counts
                (loop [i 1]
                  (let [n (bit-shift-left i 1)]
                    (if (< i arity)
                      (recur n)
                      (let [arr (int-array n)]
                        (loop [i (unchecked-add-int i arity)]
                          (when (< i n)
                            (aset arr i 1)
                            (recur (unchecked-inc-int i))))
                        arr)))))
              (aset state slot-live (identity arity))
              (aset state slot-value (empty-diff 0))
              (reduce-kv input-spawn state diffs)
              (->Ps state cancel transfer))))))))


(def ^{:arglists '([incseq-of-incseqs])
       :doc "
Returns the incremental sequence defined by the concatenation of incremental sequences defined by given incremental
sequence.
"} latest-concat
  (let [slot-notifier 0
        slot-terminator 1
        slot-process 2
        slot-buffer 3
        slot-counts 4
        slot-ready 5
        slot-push 6
        slot-live 7
        slot-value 8
        slots 9
        inner-slot-process 0
        inner-slot-index 1
        inner-slots 2]
    (letfn [(ready [^objects state ^objects input]
              ((locking state
                 (let [^objects q (aget state slot-ready)
                       c (alength q)]
                   (if-some [i (aget state slot-push)]
                     (do (aset state slot-push
                           (identity
                             (if (nil? (aget q i))
                               (do (aset q i input)
                                   (rem (unchecked-inc-int i) c))
                               (let [n (bit-shift-left c 1)
                                     a (object-array n)]
                                 (aset state slot-ready a)
                                 (acopy q i a i
                                   (unchecked-subtract-int c i))
                                 (acopy q 0 a c i)
                                 (let [p (unchecked-add-int i c)]
                                   (aset a p input)
                                   (rem (unchecked-inc-int p) n)))))) nop)
                     (do (aset state slot-push (identity (rem 1 c)))
                         (if-some [cb (aget state slot-notifier)]
                           (do (aset q 0 input) cb)
                           (loop [input input
                                  i (rem 1 c)]
                             (try @(if (identical? state input)
                                     (aget state slot-process)
                                     (aget input inner-slot-process))
                                  (catch #?(:clj Throwable :cljs :default) _))
                             (let [^objects q (aget state slot-ready)]
                               (if-some [^objects input (aget q i)]
                                 (do (aset q i nil)
                                     (recur input (rem (unchecked-inc-int i) (alength q))))
                                 (do (aset state slot-push nil)
                                     (if (zero? (aget state slot-live))
                                       (aget state slot-terminator) nop))))))))))))
            (terminated [^objects state]
              ((locking state
                 (if (zero? (aset state slot-live (dec (aget state slot-live))))
                   (if (nil? (aget state slot-push)) (aget state slot-terminator) nop) nop))))
            (cancel [^objects state]
              (locking state
                ((aget state slot-process))
                (let [^objects buffer (aget state slot-buffer)
                      ^ints counts (aget state slot-counts)]
                  (dotimes [i (aget counts 0)]
                    (let [^objects inner (aget buffer i)]
                      ((aget inner inner-slot-process)))))))
            (index-in-counts [^ints counts index]
              (unchecked-add (bit-shift-right (alength counts) 1) index))
            (compute-offset [^ints counts index l]
              (let [delta (unchecked-subtract-int l (aget counts index))]
                (loop [o 0, i (int index)]
                  (aset counts i (unchecked-add-int (aget counts i) delta))
                  (case i
                    1 o
                    (recur (if (even? i)
                             o (unchecked-add o
                                 (aget counts (unchecked-dec-int i))))
                      (bit-shift-right i 1))))))
            (split-long-swap [o l c r]
              (->> (range o (+ o (min l r)))
                (eduction (map (fn [i] (cycle i (+ l c i)))))
                (reduce compose {})
                (compose
                  (case (compare l r)
                    -1 (split-swap (+ o l) (+ l c) (- r l))
                    0 {}
                    +1 (split-swap (+ o r) (- l r) (+ c r))))))
            (ensure-capacity [^objects state grow degree shrink]
              (loop []
                (let [counts ^ints (aget state slot-counts)
                      buffer ^objects (aget state slot-buffer)
                      length (alength buffer)]
                  (if (< length degree)
                    (let [new-length (alength counts)
                          new-counts (int-array (bit-shift-left new-length 1))]
                      (acopy buffer 0 (aset state slot-buffer (object-array new-length)) 0 length)
                      (aset new-counts 1 (aget counts 1))
                      (loop [i 1]
                        (let [j (bit-shift-left i 1)]
                          (acopy counts i new-counts j i)
                          (when-not (== j new-length)
                            (recur j))))
                      (aset state slot-counts new-counts)
                      (recur))
                    (loop [i (unchecked-subtract-int degree grow)]
                      (if (< i degree)
                        (let [inner (object-array inner-slots)]
                          (aset buffer i inner)
                          (aset inner inner-slot-index (identity i))
                          (recur (unchecked-inc-int i)))
                        (aset counts 0 (unchecked-subtract-int degree shrink))))))))
            (swap-buffer [^objects buffer i j]
              (let [xi ^objects (aget buffer i)
                    xj ^objects (aget buffer j)]
                (aset xi inner-slot-index j)
                (aset xj inner-slot-index i)
                (aset buffer i xj)
                (aset buffer j xi)))
            (transfer [^objects state]
              ((locking state
                 (let [^objects q (aget state slot-ready)
                       input (aget q 0)]
                   (aset q 0 nil)
                   (loop [^objects input input
                          i (rem 1 (alength q))]
                     (if (nil? (aget state slot-notifier))
                       (try @(if (identical? state input)
                               (aget state slot-process)
                               (aget input inner-slot-process))
                            (catch #?(:clj Throwable :cljs :default) _))
                       (try (if (identical? state input)
                              (let [{:keys [grow degree shrink permutation change]} @(aget state slot-process)]
                                (ensure-capacity state grow degree shrink)
                                (let [^objects buffer (aget state slot-buffer)
                                      ^ints counts (aget state slot-counts)
                                      global-degree (aget counts 1)
                                      perm (loop [p permutation
                                                  q {}]
                                             (case p
                                               {} (reduce
                                                    (fn [q index]
                                                      (let [^objects inner (aget buffer index)
                                                            ^objects inner (if-some [ps (aget inner inner-slot-process)]
                                                                             (let [clone (object-array inner-slots)]
                                                                               (aset clone inner-slot-index index)
                                                                               (aset inner inner-slot-index nil)
                                                                               (aset buffer index clone)
                                                                               (ps) clone) inner)]
                                                        (aset state slot-live (inc (aget state slot-live)))
                                                        (aset inner inner-slot-process
                                                          ((change index) #(ready state inner) #(terminated state)))
                                                        (let [k (index-in-counts counts index)
                                                              l (aget counts k)
                                                              o (compute-offset counts k 0)
                                                              s (aget counts 1)]
                                                          (compose (->> (range o (unchecked-add-int o l))
                                                                     (eduction (map (fn [i] (cycle i (unchecked-add-int s i)))))
                                                                     (reduce compose {})) q))))
                                                    q (sort (keys change)))
                                               (let [[i j] (first p)
                                                     k2 (index-in-counts counts (max i j))
                                                     k1 (index-in-counts counts (min i j))
                                                     l2 (aget counts k2)
                                                     l1 (aget counts k1)
                                                     o2 (compute-offset counts k2 l1)
                                                     o1 (compute-offset counts k1 l2)]
                                                 (swap-buffer buffer i j)
                                                 (recur (compose p (cycle i j))
                                                   (compose (split-long-swap o1 l1
                                                              (unchecked-subtract-int
                                                                (unchecked-subtract-int o2 o1)
                                                                l1) l2) q)))))]
                                  (dotimes [i shrink]
                                    (let [index (unchecked-dec-int (unchecked-subtract-int degree i))
                                          ^objects inner (aget buffer index)]
                                      (aset buffer index nil)
                                      (aset inner inner-slot-index nil)
                                      ((aget inner inner-slot-process))
                                      (compute-offset counts (index-in-counts counts index) 0)))
                                  (aset state slot-value
                                    (combine (aget state slot-value)
                                      {:grow 0
                                       :degree global-degree
                                       :permutation perm
                                       :shrink (unchecked-subtract global-degree (aget counts 1))
                                       :change {}
                                       :freeze #{}}))))
                              (if-some [index (aget input inner-slot-index)]
                                (let [{:keys [grow degree shrink permutation change freeze]} @(aget input inner-slot-process)
                                      ^ints counts (aget state slot-counts)
                                      global-degree (unchecked-add-int (aget counts 1) grow)
                                      size-before (unchecked-subtract-int degree grow)
                                      size-after (unchecked-subtract-int degree shrink)
                                      offset (compute-offset counts (index-in-counts counts index) size-after)
                                      shift (unchecked-subtract-int global-degree (unchecked-add-int degree offset))
                                      +offset (partial + offset)]
                                  (aset state slot-value
                                    (combine (aget state slot-value)
                                      {:grow grow
                                       :shrink shrink
                                       :degree global-degree
                                       :permutation (compose
                                                      (split-swap (unchecked-add-int offset size-after) shift shrink)
                                                      (into {} (map (juxt (comp +offset key) (comp +offset val))) permutation)
                                                      (split-swap (unchecked-add-int offset size-before) shift grow))
                                       :change (into {} (map (juxt (comp +offset key) val)) change)
                                       :freeze (into #{} (map +offset) freeze)})))
                                (try @(aget input inner-slot-process)
                                     (catch #?(:clj Throwable :cljs :default) _))))
                            (catch #?(:clj Throwable :cljs :default) e
                              (aset state slot-notifier nil)
                              (aset state slot-value e)
                              (cancel state))))
                     (let [^objects q (aget state slot-ready)]
                       (if-some [input (aget q i)]
                         (do (aset q i nil)
                             (recur input (rem (unchecked-inc-int i) (alength q))))
                         (do (aset state slot-push nil)
                             (if (zero? (aget state slot-live))
                               (aget state slot-terminator) nop))))))))
              (let [x (aget state slot-value)]
                (aset state slot-value (empty-diff (aget ^ints (aget state slot-counts) 1)))
                (if (nil? (aget state slot-notifier)) (throw x) x)))]
      (fn [input]
        (fn [n t]
          (let [state (object-array slots)]
            (aset state slot-notifier n)
            (aset state slot-terminator t)
            (aset state slot-buffer (object-array 1))
            (aset state slot-counts (int-array 2))
            (aset state slot-ready (object-array 1))
            (aset state slot-live (identity 1))
            (aset state slot-value (empty-diff 0))
            (aset state slot-process (input #(ready state state) #(terminated state)))
            (->Ps state cancel transfer)))))))


;; unit tests

(tests "permutations"
  (decompose {0 1, 1 4, 2 3, 3 2, 4 0}) :=
  #{[0 1 4] [2 3]}

  (recompose #{[0 1 4] [2 3]}) :=
  {0 1, 1 4, 2 3, 3 2, 4 0}

  (decompose (inverse {0 1, 1 4, 2 3, 3 2, 4 0})) :=
  #{[1 0 4] [3 2]}

  (recompose #{[1 0 4] [3 2]}) :=
  {0 4, 1 0, 2 3, 3 2, 4 1}

  (arrange [0 1 2 3 4] {0 1, 1 4, 2 3, 3 2, 4 0}) :=
  [1 4 3 2 0]

  (arrange [:a :b :c :d :e] {0 1, 1 4, 2 3, 3 2, 4 0}) :=
  [:b :e :d :c :a]

  (compose
    (cycle 1 3 2 4)
    (cycle 1 4 2 3)) := {}

  (inverse (split-swap 4 2 3)) := (split-swap 4 3 2)

  (order (cycle 2)) := 1
  (order (cycle 2 3)) := 2
  (order (cycle 2 3 4)) := 3
  (order (compose (cycle 0 1) (cycle 2 3 4))) := 6

  (involution? (cycle 2)) := false
  (involution? (cycle 2 3)) := true
  (involution? (cycle 2 3 4)) := false

  (transposition? (cycle 2 3)) := true
  (transposition? (cycle 2 3 4)) := false)

(tests "sequence diffs"
  (patch-vec [:a :b :c]
    {:grow 1
     :degree 4
     :permutation (rotation 3 1)
     :shrink 2
     :change {1 :e}}) :=
  [:a :e]
  (patch-vec [:a :e]
    {:grow 2
     :degree 4
     :permutation (rotation 1 3)
     :shrink 1
     :change {0 :f 1 :g 2 :h}}) :=
  [:f :g :h]

  (patch-vec [:a :b :c]
    {:grow 1
     :degree 4
     :permutation {}
     :shrink 1
     :change {0 :f, 1 :g, 2 :h}}) :=
  [:f :g :h]

  (combine
    {:degree 1 :grow 1 :permutation {} :shrink 0 :change {0 :a} :freeze #{}}
    {:degree 1 :grow 0 :permutation {} :shrink 1 :change {} :freeze #{}}) :=
  {:degree 0 :grow 0 :permutation {} :shrink 0 :change {} :freeze #{}}

  (combine
    {:grow 1 :degree 4 :permutation (rotation 3 1) :shrink 2 :change {1 :e} :freeze #{}}
    {:grow 2 :degree 4 :permutation (rotation 1 3) :shrink 1 :change {0 :f 1 :g 2 :h} :freeze #{}}) :=
  {:degree 5 :grow 2 :shrink 2 :permutation (compose (cycle 2 4) (cycle 1 3)) :change {0 :f, 1 :g, 2 :h} :freeze #{}})

(tests "incremental sequences"
  (letfn [(queue []
            #?(:clj (let [q (java.util.LinkedList.)]
                      (fn
                        ([] (.remove q))
                        ([x] (.add q x) nil)))
               :cljs (let [q (make-array 0)]
                       (fn
                         ([]
                          (when (zero? (alength q))
                            (throw (js/Error. "No such element.")))
                          (.shift q))
                         ([x] (.push q x) nil)))))]
    (let [q (queue)
          ps ((fixed) #(q :step) #(q :done))]
      (q) := :step
      @ps := {:degree 0, :grow 0, :shrink 0, :permutation {}, :change {}, :freeze #{}})

    (let [q (queue)
          ps ((fixed (fn [n t] (q n) (n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n (q)]
      (q) := :step
      (q 0)
      @ps := {:grow 1, :degree 1, :shrink 0, :permutation {}, :change {0 0}, :freeze #{}}
      (n)
      (q) := :step
      (q 1)
      @ps := {:grow 0, :shrink 0, :degree 1, :permutation {}, :change {0 1}, :freeze #{}})

    (let [q (queue)
          ps ((fixed
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%)))
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n1 (q)
          n2 (q)]
      (n1)
      (q) := :step
      (n2)
      (q 0)
      (q :a)
      @ps := {:grow 2, :degree 2, :shrink 0, :permutation {}, :change {0 0, 1 :a}, :freeze #{}}
      (n1)
      (q) := :step
      (n2)
      (q 1)
      (q :b)
      @ps := {:grow 0, :shrink 0, :degree 2, :permutation {}, :change {0 1, 1 :b}, :freeze #{}})

    (let [q (queue)
          ps ((latest-product identity (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n (q)]
      (n)
      (q) := :step
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :a} :freeze #{}})
      @ps := {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :a} :freeze #{}})

    (let [q (queue)
          ps ((latest-product vector
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%)))
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n1 (q)
          n2 (q)]
      (n1)
      (q) := :step
      (n2)
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :a} :freeze #{}})
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :b} :freeze #{}})
      @ps := {:degree 1, :permutation {}, :grow 1, :shrink 0, :change {0 [:a :b]}, :freeze #{}})

    (let [q (queue)
          ps ((latest-product vector
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%)))
                (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n1 (q)
          n2 (q)]
      (n1)
      (q) := :step
      (q {:grow 2 :degree 2 :shrink 0 :permutation {} :change {0 :a 1 :b} :freeze #{}})
      @ps := {:degree 0, :permutation {}, :grow 0, :shrink 0, :change {}, :freeze #{}}
      (n2)
      (q) := :step
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 "a"} :freeze #{}})
      @ps := {:degree 2, :permutation {}, :grow 2, :shrink 0, :change {0 [:a "a"], 1 [:b "a"]}, :freeze #{}}
      (n2)
      (q) := :step
      (q {:grow 1 :degree 2 :shrink 0 :permutation {} :change {1 "b"} :freeze #{}})
      @ps := {:degree 4, :permutation {1 2, 2 1}, :grow 2, :shrink 0, :change {1 [:a "b"], 3 [:b "b"]}, :freeze #{}}
      (n2)
      (q) := :step
      (q {:grow 0 :degree 2 :shrink 1 :permutation {} :change {} :freeze #{}})
      @ps := {:degree 4 :grow 0 :shrink 2 :permutation {1 2, 2 1} :change {} :freeze #{}})

    (let [q (queue)
          ps ((latest-concat (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n (q)]
      (n)
      (q) := :step
      (q {:grow 2 :degree 2 :shrink 0 :permutation {} :freeze #{}
          :change {0 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))
                   1 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 3 :shrink 0 :degree 3 :permutation {} :change {0 :x0 1 :y0 2 :z0} :freeze #{}})
      (q {:grow 2 :shrink 0 :degree 2 :permutation {} :change {0 :x1 1 :y1} :freeze #{}})
      @ps := {:degree 5, :permutation {}, :grow 5, :shrink 0, :change {0 :x0, 1 :y0, 2 :z0, 3 :x1, 4 :y1}, :freeze #{}})

    (let [q (queue)
          ps ((latest-concat (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n (q)]
      (n)
      (q) := :step
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :freeze #{}
          :change {0 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :foo} :freeze #{}})
      @ps := {:degree 1, :permutation {}, :grow 1, :shrink 0, :change {0 :foo}, :freeze #{}}
      (n)
      (q) := :step
      (q {:grow 0 :degree 1 :shrink 0 :permutation {} :freeze #{}
          :change {0 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 :foo} :freeze #{}})
      @ps := {:degree 2, :permutation {0 1, 1 0}, :grow 1, :shrink 1, :change {0 :foo}, :freeze #{}}
      (q) := :cancel)

    (let [q (queue)
          ps ((latest-concat (fn [n t] (q n) (->Ps q #(% :cancel) #(%))))
              #(q :step) #(q :done))
          n (q)]
      (n)
      (q) := :step
      (q {:grow 2 :degree 2 :shrink 0 :permutation {} :freeze #{}
          :change {0 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))
                   1 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 0 :shrink 0 :degree 0 :permutation {} :change {} :freeze #{}})
      (q {:grow 0 :shrink 0 :degree 0 :permutation {} :change {} :freeze #{}})
      @ps := {:degree 0, :permutation {}, :grow 0, :shrink 0, :change {}, :freeze #{}}
      (n)
      (q) := :step
      (q {:grow 0 :degree 2 :shrink 0 :permutation {} :freeze #{}
          :change {1 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 "hello"} :freeze #{}})
      @ps := {:degree 1 :permutation {} :grow 1 :shrink 0 :change {0 "hello"} :freeze #{}}
      (q) := :cancel
      (n)
      (q) := :step
      (q {:grow 0 :degree 2 :shrink 0 :permutation {} :freeze #{}
          :change {0 (fn [n t] (n) (->Ps q #(% :cancel) #(%)))}})
      (q {:grow 1 :degree 1 :shrink 0 :permutation {} :change {0 "hello"} :freeze #{}})
      @ps := {:degree 2 :permutation {0 1, 1 0} :grow 1 :shrink 0 :change {0 "hello"} :freeze #{}}
      (q) := :cancel)))