(ns ^:no-doc hyperfiddle.photon-impl.runtime
  (:refer-clojure :exclude [eval compile])
  (:require [hyperfiddle.photon-impl.yield :refer [yield]]
            [hyperfiddle.photon-impl.eventually :refer [eventually]]
            [hyperfiddle.photon-impl.local :as l]
            [missionary.core :as m]
            [hyperfiddle.rcf :refer [tests]]
            [clojure.string :as str]
            [hyperfiddle.logger :as log])
  (:import missionary.Cancelled
           (hyperfiddle.photon Failure Pending Remote)
           #?(:clj (clojure.lang IFn IDeref Atom))))

;; A photon program is a tree, which structure is dynamically maintained.
;; Two peers are synchronized (through a protocol) such that the tree structure is identicaly on both peers.
;; Two type of nodes:
;; [Frames] : A piece of DAG with a static structure. Won't be rearanged at runtime. (AKA Static Frame)
;;            - A set of compiled s-expressions + a set of signals weaving these expressions + N inputs + N outputs
;;            - A frame has 2 instances, one on client, one on server.
;;            - Server's outputs are client's inputs and vice-versa.
;;            - Frames are processes.
;;            - Image: a stackframe but for a DAG. A stackframe is allocated to compute the result of a function.
;;              It is volatile (disposable) in a stack-based program. Since photon is reactive, the frame is not disposable.
;;              «ReactiveFrame» «Distributed Reactive Frame»
;; [Tiers] : For each `new` in a frame, a managed process is created. Tiers are child processes of frames. (AKA Dynamic Frame)
;;             - Parent process of a tier is always a frame.
;;             - Parent process of a frame is alawys a tier.
;;             - Specificity: Frames have a fixed set of children, tiers have a dynamic set of children, they can spawn new frames anytime.
;;                            Child frames of a tier are positioned (there is a well defined traversal order)
;;                            Node order (positions) can change at runtime because tiers can spawn dynamically (e.g.: p/for).
;;             - Some tiers don't have child frames : e.g. (new (m/watch .)), no child frames, no input, no output
;;             - Some frames don't have child tiers : e.g. a frame without any `new`, no variability.
;;             - (image: an ordered tree with different kind of nodes at each generation)

;; There is a virtual machine. Its instructions are numbers, they go by pairs.
;; [ Frame Identifier , Argument ]
;; If argument is a negative number, it means we are moving the frame.
;; If argument is a positive number, it identifies a slot in the frame.
;; Slot are stically known places, exposed to the remote frame over network, it can be :
;; 1. A frame input address, (when the remote frame produces an output, an instruction will identify then set the addressed local frame input)
;; 2. The address of p/fn in the remote frame (aka target)
;; 3. The address of `new` in the remote frame (aka source)

;; Creating a frame takes 2 instructions: the receipe, then the place where it should be created.
;; 1. Location (address) of p/fn (which frame receipe do we want to instantiate)
;; 2. Where (address) do we want the instance of p/fn to be instantiated (which tier should get a new child frame )


;; # network protocol
;; After boot, both peers are allowed to send messages to each other at any point in time without coordination.
;; A message is a vector of arbitrary values (the data frames) ending with a vector of integers (the control frame).
;; Each peer must process messages sequentially. Decoding a message requires a queue (input values, initially 
;; empty) and two registers (the current frame and the current target, initially undefined).
;; 1. push data frame values to the queue
;; 2. process control frame integers as a sequence of instructions (for each):
;;   * if current frame is undefined :
;;     1. lookup frame identified by instruction :
;;       * zero identifies the root frame
;;       * strictly positive numbers identify frames created by local sources, by birth index
;;       * strictly negative numbers identify frames created by local variables, by negation of birth index
;;     2. define it as current frame
;;   * if current frame is defined :
;;     * if instruction (argument) is strictly negative :
;;       1. move the current frame to position (+ instruction size-of-source), where
;;          - instruction is the argument
;;          - size-of-source: count of frame's siblings + 1 (how many frame children does the parent tier have)
;;          if target position is the same as current, cancel the frame.
;;       2. undefine current frame
;;     * if instruction is positive or zero :
;;       1. lookup the current frame slot identified by instruction (argument)
;;         * if slot is an input :
;;           * if queue is non empty, pop a value and assign this value to the input
;;           * if queue is empty, terminate the input
;;         * if slot is a source :
;;           1. construct a new frame from this target and insert it at the last child of source (a tier)
;;           2. undefine current target
;;         * if slot is a target : define it as current target
;;         * otherwise, remove frame from index
;;       2. undefine current frame

(defn fail [x] (throw x))

(def failure (some-fn #(when (instance? Failure %) %)))

(def pending (Failure. (Pending.)))

(defn error [^String msg]
  (#?(:clj Error. :cljs js/Error.) msg))

(defn failer [e n t]
  (n) (reify
        IFn (#?(:clj invoke :cljs -invoke) [_])
        IDeref (#?(:clj deref :cljs -deref) [_] (t) (throw e))))

(def latest-apply
  (partial m/latest
    (fn [f & args]
      (or (apply failure f args)
        (try (apply f args)
             (catch #?(:clj Throwable :cljs :default) e
               (Failure. e)))))))

(def latest-first
  (partial m/latest
    (fn [x y] (if (instance? Failure y) y x))))

(def latest-last
  (partial m/latest
    (fn [x y] (if (instance? Failure x) x y))))

(defn pure [x] (m/cp x))

(def lift-cancelled
  (partial comp
    (fn [it]
      (reify
        IFn
        (#?(:clj invoke :cljs -invoke) [_] (it))
        IDeref
        (#?(:clj deref :cljs -deref) [_]
          (try @it (catch Cancelled e (Failure. e))))))))

(defn signal [<x]
  (m/signal! (lift-cancelled <x)))

(def this (l/local))

(defn next-local [^objects ctx] ; frames are identified by an integer. Creating a frame will increment this counter.
  (aset ctx (int 1) (inc (aget ctx (int 1)))))

(defn next-remote [^objects ctx] ; frames are identified by an integer. Creating a frame will increment this counter.
  (aset ctx (int 2) (dec (aget ctx (int 2)))))

; add a frame in the context index, identified by id
(defn assoc-frame-id [^objects ctx id frame]
  (aset ctx (int 3) (assoc! (aget ctx (int 3)) id frame)))

(defn lookup-frame-id [^objects ctx id]
  (get (aget ctx (int 3)) id))

(defn dissoc-frame-id [^objects ctx id]
  (aset ctx (int 3) (dissoc! (aget ctx (int 3)) id)))

(defn set-remote [^objects ctx callback]
  (aset ctx (int 4) callback))

(defn get-remote [^objects ctx]
  (aget ctx (int 4)))

(defn get-output [^objects ctx]
  (aget ctx (int 5)))

(defn set-output [^objects ctx callback]
  (aset ctx (int 5) callback))

(defn empty-queue? [^objects ctx]
  (nil? (aget ctx (int 6))))

(defn set-queue [^objects ctx xs]
  (aset ctx (int 6) (seq xs)) ctx)

(defn pop-queue [^objects ctx]
  (let [[x & xs] (aget ctx (int 6))]
    (aset ctx (int 6) xs) x))

(defn set-frame-register [^objects ctx x]
  (aset ctx (int 7) x))

(defn get-frame-register [^objects ctx]
  (aget ctx (int 7)))

(defn set-target-register [^objects ctx x]
  (aset ctx (int 8) x))

(defn get-target-register [^objects ctx]
  (aget ctx (int 8)))

(defn tier-parent [^objects t]
  (aget t (int 0)))

(defn tier-position [^objects t]
  (aget t (int 1)))

(defn get-tier-buffer ^objects [^objects t]
  (aget t (int 2)))

(defn set-tier-buffer ^objects [^objects t x]
  (aset t (int 2) x))

(defn get-tier-size [^objects t]
  (aget t (int 3)))

(defn set-tier-size [^objects t x]
  (aset t (int 3) x))

(defn get-tier-foreigns [^objects t]
  (aget t (int 4)))

(defn set-tier-foreigns [^objects t ps]
  (aset t (int 4) ps))

(defn get-tier-hooks [^objects t]
  (aget t (int 5)))

(defn set-tier-hooks [^objects t x]
  (aset t (int 5) x))

(defn get-tier-vars ^objects [^objects t]
  (aget t (int 6)))

(defn set-tier-vars ^objects [^objects t x]
  (aset t (int 6) x))

(defn get-tier-remote [^objects t]
  (aget t (int 7)))

(defn set-tier-remote [^objects t x]
  (aset t (int 7) x))

(defn get-frame-position [^objects f]
  (aget f (int 0)))

(defn set-frame-position [^objects f pos]
  (aset f (int 0) pos))

(defn frame-id [^objects f]
  (aget f (int 1)))

(defn frame-parent [^objects f]
  (aget f (int 2)))

(defn frame-context [^objects f]
  (aget f (int 3)))

(defn frame-foreign ^objects [^objects f]
  (aget f (int 4)))

(defn frame-static ^objects [^objects f]
  (aget f (int 5)))

(defn frame-dynamic ^objects [^objects f]
  (aget f (int 6)))

(defn frame-variables ^objects [^objects f]
  (aget f (int 7)))

(defn frame-sources ^objects [^objects f]
  (aget f (int 8)))

(defn frame-targets ^objects [^objects f]
  (aget f (int 9)))

(defn frame-inputs ^objects [^objects f]
  (aget f (int 10)))

(defn frame-tiers ^objects [^objects f]
  (aget f (int 11)))

(defn frame-constants ^objects [^objects f]
  (aget f (int 12)))

(defn frame-output-count [^objects f]
  (aget f (int 13)))

(defn doto-aset [^objects arr k v]
  (doto arr (aset (int k) v)))

(defn make-context "
0: root frame
1: local counter id
2: remote counter id
3: id -> frame
4: remote callback
5: output callback
6: decoder stack
7: decoder frame register
8: decoder target register
" []
  (doto (object-array 9)
    (aset (int 1) (identity 0))
    (aset (int 2) (identity 0))
    (aset (int 3) (transient {}))))

(defn make-tier "
0: parent frame
1: tier position (a frame has many child tiers, as much as `new` statements, this is the static position of the current tier in the parent frame’s child array)
2: buffer of child frame list
3: size of child frame list
4: hook map
5: foreign flow map
6: dynamic vars
7: remote slot (if this tier is a variable, we must reference the corresponding remote source)
" [parent position]
  (aset (frame-tiers parent) (int position)
    (doto (object-array 8)
      (aset (int 0) parent)
      (aset (int 1) position)
      (aset (int 2) (object-array 8))
      (aset (int 3) (identity (int 0)))
      (aset (int 4) {})
      (aset (int 5) {}))))

(defn make-frame "
0 : position. The index of the frame among its siblings.
1 : context. Immutable.
2 : id. Immutable. Zero if root frame, a strictly positive number if the frame was created by a variable, a strictly
    negative number if the frame was created by a source.
3 : parent tier. Immutable. nil iff root frame.
4 : static flow array. Immutable.
5 : dynamic flow array. Immutable.
6 : foreign flow array. Immutable.
7 : variable flow array. Immutable.
8 : source tier position array. Immutable.
9 : target frame constructor array. Immutable.
10: input state array. Immutable.
11: tier array. Immutable.
12: constant count. Immutable.
13: output count. Immutable
" [buffer position id parent context ^objects vars foreign static dynamic variable-count source-count constant-count target-count output-count input-count boot]
  (let [tier-count (+ variable-count source-count)
        frame (doto (object-array 14)
                (aset (int 0) position)
                (aset (int 1) id)
                (aset (int 2) parent)
                (aset (int 3) context)
                (aset (int 4) (object-array (count foreign)))
                (aset (int 5) (object-array (count static)))
                (aset (int 6) (object-array (count dynamic)))
                (aset (int 7) (object-array variable-count))
                (aset (int 8) (object-array source-count))
                (aset (int 9) (object-array target-count))
                (aset (int 10) (object-array input-count))
                (aset (int 11) (object-array tier-count))
                (aset (int 12) (object-array constant-count))
                (aset (int 13) output-count))]
    (dotimes [i tier-count] (make-tier frame i))
    (aset buffer (int position) frame)
    (assoc-frame-id context id frame)
    (let [prevs (reduce-kv
                  (fn [m v <x]
                    (let [prev (aget vars (int v))
                          proc (signal <x)]
                      (aset (frame-foreign frame) (count m) proc)
                      (aset vars (int v) proc)
                      (assoc m v prev)))
                  {} foreign)]
      (reduce-kv (fn [^objects arr i <x]
                   (aset arr (int i) (signal <x)) arr)
        (frame-static frame) static)
      (reduce-kv (fn [^objects arr i v]
                   (aset arr (int i) (signal (aget vars (int v)))) arr)
        (frame-dynamic frame) dynamic)
      (let [result (boot frame vars)]
        (reduce-kv doto-aset vars prevs)
        result))))

(deftype InputIterator [^objects i]
  IFn
  (#?(:clj invoke :cljs -invoke) [_])
  IDeref
  (#?(:clj deref :cljs -deref) [_]
    (let [x (aget i (int 2))]
      (aset i (int 2) i)
      (when (nil? (aget i (int 0)))
        ((aget i (int 1)))) x)))

(defn input [frame slot]
  (fn [n t]
    (n) (->InputIterator
          (aset (frame-inputs frame) (int slot)
            (doto (object-array 3)
              (aset (int 0) n)
              (aset (int 1) t)
              (aset (int 2) pending))))))

(defn input-change [^objects i x]
  (when-some [n (aget i (int 0))]
    (let [y (aget i (int 2))]
      (aset i (int 2) x)
      (when (identical? i y) (n)))))

(defn input-close [^objects i]
  (when-not (nil? (aget i (int 0)))
    (aset i (int 0) nil)
    (when (identical? i (aget i (int 2)))
      ((aget i (int 1))))))

(defn remote [frame slot]
  (when-some [callback (get-remote (frame-context frame))]
    (callback [(frame-id frame) slot])))

(defn output [frame slot <x]
  (when-some [callback (get-output (frame-context frame))]
    (callback {[(frame-id frame) slot] (signal <x)})))

(defn static [frame slot]
  (aget (frame-static frame) (int slot)))

(defn dynamic [frame slot]
  (aget (frame-dynamic frame) (int slot)))

(defn tree
  "A snapshot of the tree below given frame, as nested vectors. Frame vectors start with their id."
  [f]
  (let [tiers (frame-tiers f)]
    (loop [v []
           i (int 0)]
      (if (== i (alength tiers))
        {:id (frame-id f)
         :inputs (alength (frame-inputs f))
         :targets (alength (frame-targets f))
         :sources (alength (frame-sources f))
         :tiers v}
        (recur
          (conj v
            (let [tier (aget tiers i)
                  buf (get-tier-buffer tier)]
              (loop [v []
                     i (int 0)]
                (if (== i (get-tier-size tier))
                  v (recur (conj v (tree (aget buf i)))
                      (inc i))))))
          (inc i))))))

(defn find-scope [f]
  (loop [f f
         s #{}]
    (if-some [tier (frame-parent f)]
      (recur (tier-parent tier)
        (into s (keys (get-tier-hooks tier)))) s)))

(declare tier-walk-frames)
(defn frame-walk-tiers [frame step k cb start]
  (let [tiers (frame-tiers frame)
        size (alength tiers)
        back (bit-shift-right (- 1 step) 1)
        back-inc-size (* back (inc size))
        stop (- size back-inc-size)]
    (loop [i (if (nil? start) (dec back-inc-size) start)]
      (let [i (+ i step)]
        (when-not (== i stop)
          (if-some [x (let [tier (aget tiers i)]
                        (if-some [v (get (get-tier-hooks tier) k)]
                          (cb v) (tier-walk-frames tier step k cb nil)))]
            x (recur i)))))))

(defn tier-walk-frames [tier step k cb start]
  (let [buf (get-tier-buffer tier)
        size (get-tier-size tier)
        back (bit-shift-right (- 1 step) 1)
        back-inc-size (* back (inc size))
        stop (- size back-inc-size)]
    (loop [i (if (nil? start) (dec back-inc-size) start)]
      (let [i (+ i step)]
        (when-not (== i stop)
          (if-some [x (frame-walk-tiers (aget buf i) step k cb nil)]
            x (recur i)))))))

(defn notify-rotate [f k]
  (let [anchor (loop [f f]
                 (let [tier (frame-parent f)]
                   (when-not (contains? (get-tier-hooks tier) k)
                     (if-some [a (tier-walk-frames tier 1 k identity
                                   (get-frame-position f))]
                       a (recur (tier-parent tier))))))]
    (frame-walk-tiers f 1 k (fn [target] (k target anchor)) nil) f))

(defn array-call [^objects arr]
  (dotimes [i (alength arr)]
    ((aget arr i))))

(defn frame-rotate [f to]
  (let [from (get-frame-position f)
        step (compare to from)
        tier (frame-parent f)
        buf (get-tier-buffer tier)]
    (if (== to from)
      (let [size (dec (get-tier-size tier))]
        (loop [i to]
          (when-not (== i size)
            (let [j (inc i)
                  y (aget buf (int j))]
              (set-frame-position y i)
              (aset buf (int i) y)
              (recur j))))
        (set-frame-position f nil)
        (set-tier-size tier size)
        (aset buf (int size) nil)
        (array-call (frame-static f))
        (array-call (frame-dynamic f))
        (array-call (frame-foreign f))
        (array-call (frame-variables f))
        (array-call (frame-constants f)))
      (do (loop [i from]
            (let [j (+ i step)
                  y (aget buf (int j))]
              (set-frame-position y i)
              (aset buf (int i) y)
              (when-not (== to j) (recur j))))
          (set-frame-position f to)
          (aset buf (int to) f)
          (reduce notify-rotate f
            (find-scope f))))))

(defn move
  "Move a frame. If origin position is equal to target position, frame is removed. Will search and call `hook`."
  ([tier from to]
   (let [f (aget (get-tier-buffer tier) (int from))]
     (remote f (- to (get-tier-size tier)))
     (frame-rotate f to))))

(defn frame-cancel [f]
  (when-some [pos (get-frame-position f)]
    (remote f (- pos (get-tier-size (frame-parent f))))
    (frame-rotate f pos)))

(defn decode-inst [ctx inst]
  (if-some [id (get-frame-register ctx)]
    (let [f (lookup-frame-id ctx id)]
      (set-frame-register ctx nil)
      (if (neg? inst)
        (frame-rotate f (+ (get-tier-size (frame-parent f)) inst))
        (let [inputs (frame-inputs f)
              offset (alength inputs)]
          (if (< inst offset)
            (let [input (aget inputs (int inst))]
              (if (empty-queue? ctx)
                (input-close input)
                (input-change input
                  (pop-queue ctx))))
            (let [inst (- inst offset)
                  targets (frame-targets f)
                  offset (alength targets)]
              (if (< inst offset)
                (set-target-register ctx (aget targets (int inst)))
                (let [inst (- inst offset)
                      sources (frame-sources f)
                      offset (alength sources)]
                  (if (< inst offset)
                    (let [source (aget sources (int inst))
                          target (get-target-register ctx)]
                      (set-target-register ctx nil)
                      (target source (next-remote ctx)))
                    (dissoc-frame-id ctx id)))))))))
    (set-frame-register ctx (- inst))) ctx)

(defn acopy [dest src size]
  #?(:clj (System/arraycopy src 0 dest 0 size))
  #?(:cljs (dotimes [i size] (aset dest i (aget src i)))))

(defn constructor [static dynamic variable-count source-count constant-count target-count output-count input-count boot]
  (fn [tier id]
    (let [pos (get-tier-size tier)
          buf (get-tier-buffer tier)
          cap (alength buf)
          buf (if (< pos cap)
                buf (set-tier-buffer tier
                      (doto (object-array (bit-shift-left cap 1))
                        (acopy buf cap))))]
      (set-tier-size tier (inc pos))
      (make-frame buf pos id tier
        (frame-context (tier-parent tier))
        (get-tier-vars tier)
        (get-tier-foreigns tier)
        static dynamic
        variable-count source-count
        constant-count target-count
        output-count input-count boot))))

(deftype FrameIterator [f it]
  IFn
  (#?(:clj invoke :cljs -invoke) [_] (frame-cancel f))
  IDeref
  (#?(:clj deref :cljs -deref) [_] @it))

;; Takes an instruction identifying a target and a frame-constructor.
;; Return a flow instantiating the frame.
(defn constant [frame slot ctor]
  (aset (frame-constants frame) (int slot)
    (signal
      (m/watch
        (atom
          (fn [n t]
            (if-some [tier (l/get-local this)]
              (let [par (tier-parent tier)
                    ctx (frame-context par)
                    id (next-local ctx)]
                (remote frame
                  (+ (frame-output-count frame) slot))    ; notify remote peer of frame creation
                (remote par
                  (+ (frame-output-count par)
                    (alength (frame-constants par))
                    (get-tier-remote tier)))              ; notify remote peer of frame mount point (on which tier we want to create this frame, its parent)
                (let [<x (ctor tier id)
                      f (lookup-frame-id ctx id)]
                  (->FrameIterator f
                    (<x n #(do (frame-cancel f)
                               (remote f
                                 (+ (frame-output-count f)
                                   (alength (frame-constants f))
                                   (alength (frame-variables f))))
                               (dissoc-frame-id ctx id) (t))))))
              (failer (error "Unable to build frame - not an object.") n t))))))))

(defn inject [v]
  (fn [<x <y]
    (fn [n t]
      ;; TODO make result depend on <y to catch failures, in case binding is ignored
      (if-some [tier (l/get-local this)]
        (let [foreigns (get-tier-foreigns tier)]
          (set-tier-foreigns tier (assoc foreigns v <y))
          (try (<x n t) (finally (set-tier-foreigns tier foreigns))))
        (failer (error "Unable to inject - not an object.") n t)))))

(defn bind [f & args]
  (fn [n t]
    (if-some [tier (l/get-local this)]
      (try ((apply f tier args) n t) ; hook tier and pass to userland !
           (catch #?(:clj Throwable :cljs :default) e
             (failer e n t)))
      (failer (error "Unable to bind - not an object.") n t))))

(defn with [tier <x]
  (fn [n t]
    (let [prev (l/get-local this)]
      (l/set-local this tier)
      (try (<x n t) (finally (l/set-local this prev))))))

(defn clause
  ([f] (fn [e] (f (pure e))))
  ([f c] (fn [e] (when (instance? c e) (f (pure e))))))

(defn recover [tier catch <x]
  (yield (fn [x]
           (when (instance? Failure x)
             (when-some [<c (catch (.-error ^Failure x))]
               (with tier <c)))) <x))

(defn variable [frame vars position slot <<x]
  (let [tier (aget (frame-tiers frame) (int position))]
    (set-tier-remote tier slot)
    (set-tier-vars tier (aclone vars))
    (aset (frame-variables frame) (int slot)
      (m/signal!
        (m/cp (try (let [<x (m/?< <<x)]
                     (if (failure <x)
                       <x (m/?< (with tier <x))))
                   (catch Cancelled e
                     (Failure. e))))))))

(defn source [frame vars position slot]
  (aset (frame-sources frame) (int slot)
    (doto (aget (frame-tiers frame) (int position))
      (set-tier-vars (aclone vars)))))

(defn target [frame slot ctor]
  (aset (frame-targets frame) (int slot) ctor))

(defn hook [k v <x]
  (assert (some? v) "hook value must be non-nil.")
  (fn [n t]
    (if-some [tier (l/get-local this)]
      (do
        (loop [tier tier]
          (let [f (tier-parent tier)]
            (if-some [a (frame-walk-tiers f 1 k identity (tier-position tier))]
              (k v a)
              (when-some [tier (frame-parent f)]
                (if-some [a (tier-walk-frames tier 1 k identity (get-frame-position f))]
                  (k v a)
                  (if (contains? (get-tier-hooks tier) k)
                    (k v nil) (recur tier)))))))
        (set-tier-hooks tier (assoc (get-tier-hooks tier) k v))
        (<x n #(do (set-tier-hooks tier (dissoc (get-tier-hooks tier) k))
                   (k v) (t))))
      (failer (error "Unable to hook - not an object.") n t))))

(def unbound (pure (Failure. (error "Unbound var."))))

(defn pst [e]
  #?(:clj (.printStackTrace ^Throwable e)
     :cljs (js/console.error e)))

(def merge-events (partial mapv into))

(defn crash-failure [x]
  (if (instance? Failure x)
    (throw (.-error ^Failure x)) x))

(defn peer [var-count dynamic variable-count source-count constant-count target-count output-count input-count ctor]
  (fn [write ?read]
    (m/reactor
      (let [context (make-context)
            >remote (->> (m/observe
                           (fn [!]
                             (set-remote context !)
                             #(set-remote context nil)))
                      (m/relieve into)
                      (m/stream!))
            >output (->> (m/observe
                           (fn [!]
                             (set-output context !)
                             #(set-output context nil)))
                      (m/relieve merge)
                      (m/stream!))]
        (m/stream!
          (m/latest crash-failure
            (make-frame context 0 0 nil context (object-array (repeat var-count unbound)) {} [] dynamic
              variable-count source-count constant-count target-count output-count input-count ctor)))
        (->> (m/ap (m/? (m/?> (m/seed (repeat ?read)))))
          (m/stream!)
          (m/eduction
            (map (fn [msg]
                   (reduce decode-inst
                     (set-queue context (pop msg))
                     (peek msg)))))
          (m/stream!))
        (let [>events (->> (m/ap                            ; FIXME outputs are sampled too fast, should be in sync with the websocket. Should sample everytime we are done writing on the socket.
                             (m/amb=
                               [(m/?> >remote) {} #{}]
                               (let [m (m/?> ##Inf >output)
                                     [path <out] (m/?> (count m) (m/seed m))
                                     x (m/?> (eventually context <out))]
                                 (if (identical? x context)
                                   [[] {} #{path}]
                                   [[] {path x} #{}]))))
                        (m/relieve merge-events)
                        (m/stream!))]
          (m/stream!
            (m/ap (let [[inst data done] (m/?> >events)]
                    (-> []
                      (into (vals data))
                      (conj (-> inst
                              (into cat (keys data))
                              (into cat done)))
                      (write) (m/?))))))))))

(defn collapse [s n f & args]
  (->> (iterate pop s)
    (transduce (comp (map peek) (take n)) conj args)
    (apply f)
    (conj (nth (iterate pop s) n))))

(defn snapshot [env f & args]
  (update env :stack conj (apply f env args)))

(defn reverse-index [m]
  (reduce-kv (fn [v x i] (assoc v i x))
    (vec (repeat (count m) nil)) m))

(def empty-frame
  {:variable 0
   :source   0
   :constant 0
   :target   0
   :output   0
   :input    0
   :static   {}
   :dynamic  {}})

;; TODO move me
;; `new` creates a local variable and a remote source
;; `p/fn` creates a local constant and a remote target
;; Same duality with input and output, if there is 3 inputs locally, there is 3 outputs remotely.
;; There is no instruction to create inputs and outputs, they are infered from unquote-splicing.

(defn compile [inst {:keys [nop sub pub inject lift vget bind invoke input output static dynamic
                            global literal variable source constant target main] :as fns}]
  (-> ((fn walk [env off idx dyn [op & args]]
         (case op
           :nop (update env :stack conj nop)
           :sub (let [[s] args
                      p (- idx s)]
                  (if (< p off)
                    (let [f (:static env)
                          i (f p (count f))]
                      (-> env
                        (assoc :static (assoc f p i))
                        (update :stack conj (static i))))
                    (update env :stack conj (sub p))))
           :pub (let [[p c] args]
                  (-> env
                    (walk off idx dyn p)
                    (walk off (inc idx) dyn c)
                    (update :stack collapse 2 pub idx)))
           :def (let [[v] args]
                  (-> env
                    (update :vars max v)
                    (update :stack conj (inject v))))
           :lift (let [[inst] args]
                   (-> env
                     (walk off idx dyn inst)
                     (update :stack collapse 1 lift)))
           :eval (let [[f] args]
                   (update env :stack conj ((:eval fns) f))) ; can't shadow eval in advanced CLJS compilation
           :node (let [[v] args
                       env (update env :vars max v)]
                   (if (dyn v)
                     (update env :stack conj (vget v))
                     (let [d (:dynamic env)
                           i (d v (count d))]
                       (-> env
                         (assoc :dynamic (assoc d v i))
                         (update :stack conj (dynamic i))))))
           :bind (let [[v s inst] args]
                   (-> env
                     (update :vars max v)
                     (walk off idx (conj dyn v) inst)
                     (update :stack collapse 1 bind v (- idx s))))
           :apply (-> (reduce (fn [env arg] (walk env off idx dyn arg)) env args)
                    (update :stack collapse (count args) invoke))
           :input (-> env
                    (snapshot :input)
                    (update :input inc)
                    (update :stack collapse 1 input))
           :output (let [[inst cont] args]
                     (-> env
                       (walk off idx dyn inst)
                       (snapshot :output)
                       (update :output inc)
                       (walk off idx dyn cont)
                       (update :stack collapse 3 output)))
           :global (let [[x] args]
                     (update env :stack conj (global x)))
           :literal (let [[x] args]
                      (update env :stack conj (literal x)))
           :variable (let [[inst] args]
                       (-> env
                         (walk off idx dyn inst)
                         (snapshot :source)
                         (snapshot :variable)
                         (update :variable inc)
                         (update :stack collapse 3 variable)))
           :source (let [[cont] args]
                     (-> env
                       (snapshot :variable)
                       (snapshot :source)
                       (update :source inc)
                       (walk off idx dyn cont)
                       (update :stack collapse 3 source)))
           :constant (let [[inst] args]
                       (-> env
                         (merge empty-frame)
                         (walk idx idx #{} inst)
                         (snapshot (comp reverse-index :static))
                         (snapshot (comp reverse-index :dynamic))
                         (snapshot :variable)
                         (snapshot :source)
                         (snapshot :constant)
                         (snapshot :target)
                         (snapshot :output)
                         (snapshot :input)
                         (merge (select-keys env (keys empty-frame)))
                         (snapshot :constant)
                         (update :constant inc)
                         (update :stack collapse 10 constant)))
           :target (let [[inst cont] args]
                     (-> env
                       (merge empty-frame)
                       (walk idx idx #{} inst)
                       (snapshot (comp reverse-index :static))
                       (snapshot (comp reverse-index :dynamic))
                       (snapshot :variable)
                       (snapshot :source)
                       (snapshot :constant)
                       (snapshot :target)
                       (snapshot :output)
                       (snapshot :input)
                       (merge (select-keys env (keys empty-frame)))
                       (snapshot :target)
                       (update :target inc)
                       (walk off idx dyn cont)
                       (update :stack collapse 11 target)))))
       (assoc empty-frame :vars -1) 0 0 #{} inst)
    (snapshot (comp inc :vars))
    (snapshot (comp reverse-index :dynamic))
    (snapshot :variable)
    (snapshot :source)
    (snapshot :constant)
    (snapshot :target)
    (snapshot :output)
    (snapshot :input)
    (:stack)
    (collapse 9 main)
    (peek)))

(defn sym [& args]
  (symbol (str/join "-" args)))

(defn emit [prefix inst]
  (compile inst
    {:nop      nil
     :sub      (fn [idx] (sym prefix 'pub idx))
     :pub      (fn [form cont idx]
                 `(let [~(sym prefix 'pub idx) (signal ~form)]
                    (latest-last ~(sym prefix 'pub idx) ~cont)))
     :static   (fn [i] `(static ~(sym prefix 'frame) ~i))
     :dynamic  (fn [i] `(dynamic ~(sym prefix 'frame) ~i))
     :eval     (fn [f] `(pure ~f))
     :lift     (fn [f] `(pure ~f))
     :vget     (fn [v] `(aget ~(sym prefix 'vars) (int ~v)))
     :bind     (fn [form slot idx]
                 `(let [~(sym prefix 'prev) (aget ~(sym prefix 'vars) ~slot)]
                    (aset ~(sym prefix 'vars) (int ~slot) ~(sym prefix 'pub idx))
                    (let [~(sym prefix 'res) ~form]
                      (aset ~(sym prefix 'vars) (int ~slot) ~(sym prefix 'prev))
                      ~(sym prefix 'res))))
     :invoke   (fn [& forms] `(latest-apply ~@forms))
     :input    (fn [slot] `(input ~(sym prefix 'frame) ~slot))
     :output   (fn [form slot cont]
                 `(do (output ~(sym prefix 'frame) ~slot ~form) ~cont))
     :global   (fn [x] `(pure ~(symbol x)))
     :literal  (fn [x] `(pure (quote ~x)))
     :inject   (fn [v] `(pure (inject ~v)))
     :variable (fn [form remote slot]
                 `(variable ~(sym prefix 'frame) ~(sym prefix 'vars) ~(+ remote slot) ~slot ~form))
     :source   (fn [remote slot cont]
                 `(do (source ~(sym prefix 'frame) ~(sym prefix 'vars) ~(+ remote slot) ~slot) ~cont))
     :constant (fn [form static dynamic variable-count source-count constant-count target-count output-count input-count slot]
                 `(constant ~(sym prefix 'frame) ~slot
                    (constructor ~(mapv (fn [p] (sym prefix 'pub p)) static) ~dynamic
                      ~variable-count ~source-count
                      ~constant-count ~target-count
                      ~output-count ~input-count
                      (fn [~(sym prefix 'frame)
                           ~(sym prefix 'vars)]
                        ~form))))
     :target   (fn [form static dynamic variable-count source-count constant-count target-count output-count input-count slot cont]
                 `(do (target ~(sym prefix 'frame) ~slot
                        (constructor ~(mapv (fn [p] (sym prefix 'pub p)) static) ~dynamic
                          ~variable-count ~source-count
                          ~constant-count ~target-count
                          ~output-count ~input-count
                          (fn [~(sym prefix 'frame)
                               ~(sym prefix 'vars)]
                            ~form))) ~cont))
     :main     (fn [form var-count dynamic variable-count source-count constant-count target-count output-count input-count]
                 `(peer ~var-count ~dynamic
                    ~variable-count ~source-count
                    ~constant-count ~target-count
                    ~output-count ~input-count
                    (fn [~(sym prefix 'frame)
                         ~(sym prefix 'vars)]
                      ~form)))}))

(tests
  (emit nil [:literal 5]) :=
  `(peer 0 [] 0 0 0 0 0 0
     (fn [~'-frame ~'-vars]
       (pure '5)))

  (emit nil [:apply [:global :clojure.core/+] [:literal 2] [:literal 3]]) :=
  `(peer 0 [] 0 0 0 0 0 0
     (fn [~'-frame ~'-vars]
       (latest-apply (pure ~'clojure.core/+) (pure '2) (pure '3))))

  (emit nil
    [:pub [:literal 1]
     [:apply [:global :clojure.core/+]
      [:sub 1] [:literal 2]]]) :=
  `(peer 0 [] 0 0 0 0 0 0
     (fn [~'-frame ~'-vars]
       (let [~'-pub-0 (signal (pure '1))]
         (latest-last ~'-pub-0
           (latest-apply (pure ~'clojure.core/+) ~'-pub-0 (pure '2))))))

  (emit nil
    [:variable [:global :missionary.core/none]]) :=
  `(peer 0 [] 1 0 0 0 0 0
     (fn [~'-frame ~'-vars]
       (variable ~'-frame ~'-vars 0 0 (pure m/none))))

  (emit nil [:input]) :=
  `(peer 0 [] 0 0 0 0 0 1
     (fn [~'-frame ~'-vars]
       (input ~'-frame 0)))

  (emit nil [:constant [:literal :foo]]) :=
  `(peer 0 [] 0 0 1 0 0 0
     (fn [~'-frame ~'-vars]
       (constant ~'-frame 0
         (constructor [] [] 0 0 0 0 0 0
           (fn [~'-frame ~'-vars]
             (pure ':foo))))))

  (emit nil
    [:variable
     [:pub [:constant [:literal 3]]
      [:pub [:constant [:input]]
       [:apply [:apply [:global :clojure.core/hash-map]
                [:literal 2] [:sub 2]
                [:literal 4] [:sub 1]
                [:literal 5] [:sub 1]]
        [:literal 1]
        [:constant [:literal 7]]]]]]) :=
  `(peer 0 [] 1 0 3 0 0 0
     (fn [~'-frame ~'-vars]
       (variable ~'-frame ~'-vars 0 0
         (let [~'-pub-0 (signal
                          (constant ~'-frame 0
                            (constructor [] [] 0 0 0 0 0 0
                              (fn [~'-frame ~'-vars]
                                (pure '3)))))]
           (latest-last ~'-pub-0
             (let [~'-pub-1 (signal
                              (constant ~'-frame 1
                                (constructor [] [] 0 0 0 0 0 1
                                  (fn [~'-frame ~'-vars]
                                    (input ~'-frame 0)))))]
               (latest-last ~'-pub-1
                 (latest-apply
                   (latest-apply (pure ~'clojure.core/hash-map)
                     (pure '2) ~'-pub-0
                     (pure '4) ~'-pub-1
                     (pure '5) ~'-pub-1)
                   (pure '1)
                   (constant ~'-frame 2
                     (constructor [] [] 0 0 0 0 0 0
                       (fn [~'-frame ~'-vars]
                         (pure '7))))))))))))

  (emit nil [:def 0]) :=
  `(peer 1 [] 0 0 0 0 0 0
     (fn [~'-frame ~'-vars]
       (pure (inject 0))))

  (emit nil
    [:pub [:literal nil]
     [:constant [:sub 1]]]) :=
  `(peer 0 [] 0 0 1 0 0 0
     (fn [~'-frame ~'-vars]
       (let [~'-pub-0 (signal (pure 'nil))]
         (latest-last ~'-pub-0
           (constant ~'-frame 0
             (constructor [~'-pub-0] [] 0 0 0 0 0 0
               (fn [~'-frame ~'-vars]
                 (static ~'-frame 0)))))))))

(defn juxt-with ;;juxt = juxt-with vector, juxt-with f & gs = apply f (apply juxt gs)
  ([f]
   (fn
     ([] (f))
     ([a] (f))
     ([a b] (f))
     ([a b c] (f))
     ([a b c & ds] (f))))
  ([f g]
   (fn
     ([] (f (g)))
     ([a] (f (g a)))
     ([a b] (f (g a b)))
     ([a b c] (f (g a b c)))
     ([a b c & ds] (f (apply g a b c ds)))))
  ([f g h]
   (fn
     ([] (f (g) (h)))
     ([a] (f (g a) (h a)))
     ([a b] (f (g a b) (h a b)))
     ([a b c] (f (g a b c) (h a b c)))
     ([a b c & ds] (f (apply g a b c ds) (apply h a b c ds)))))
  ([f g h i]
   (fn
     ([] (f (g) (h) (i)))
     ([a] (f (g a) (h a) (i a)))
     ([a b] (f (g a b) (h a b) (i a b)))
     ([a b c] (f (g a b c) (h a b c) (i a b c)))
     ([a b c & ds] (f (apply g a b c ds) (apply h a b c ds) (apply i a b c ds)))))
  ([f g h i & js]
   (fn
     ([] (apply f (g) (h) (i) (map #(%) js)))
     ([a] (apply f (g a) (h a) (i a) (map #(% a) js)))
     ([a b] (apply f (g a b) (h a b) (i a b) (map #(% a b) js)))
     ([a b c] (apply f (g a b c) (h a b c) (i a b c) (map #(% a b c) js)))
     ([a b c & ds] (apply f (apply g a b c ds) (apply h a b c ds) (apply i a b c ds) (map #(apply % a b c ds) js))))))

(defn globals [program]
  (->> (tree-seq coll? seq program)
       (eduction (comp (filter vector?)
                       (filter (fn [[a _]] (= :global a)))
                       (map second)
                       (distinct)))
       (sort-by (juxt namespace name))))

(defn missing-exports [resolvef program]
  (->> (globals program)
    (eduction (map (juxt (partial resolvef ::not-found) identity))
      (filter #(= ::not-found (first %)))
      (map second)
      (map symbol))))

(defn dynamic-resolve [nf x]
  #?(:clj (try (clojure.core/eval (symbol x))
               (catch clojure.lang.Compiler$CompilerException _ nf))
     :cljs nf))

(defn eval
  ([inst] (eval dynamic-resolve inst))
  ([resolvef inst]
   (compile inst
     {:nop      (constantly nil)
      ;; FIXME Eval is a security hazard, client should not send any program to
      ;;       eval on server. Solution is for server to compile and store
      ;;       programs, client address them by unique name (hash).
      :eval     (fn [form] (constantly (pure (clojure.core/eval form))))
      :sub      (fn [idx]
                  (fn [pubs frame vars]
                    (nth pubs idx)))
      :pub      (fn [form cont _]
                  (fn [pubs frame vars]
                    (let [pub (signal (form pubs frame vars))]
                      (latest-last pub (cont (conj pubs pub) frame vars)))))
      :lift     (fn [form]
                  (fn [pubs frame vars]
                    (pure (form pubs frame vars))))
      :vget     (fn [slot]
                  (fn [pubs frame ^objects vars]
                    (aget vars (int slot))))
      :static   (fn [slot]
                  (fn [pubs frame vars]
                    (static frame slot)))
      :dynamic  (fn [slot]
                  (fn [pubs frame vars]
                    (dynamic frame slot)))
      :bind     (fn [form slot s]
                  (fn [pubs frame ^objects vars]
                    (let [prev (aget vars (int slot))]
                      (aset vars (int slot) (nth pubs s))
                      (let [res (form pubs frame vars)]
                        (aset vars (int slot) prev) res))))
      :invoke   (fn [& forms] (apply juxt-with latest-apply forms))
      :input    (fn [slot]
                  (fn [pubs frame vars]
                    (input frame slot)))
      :output   (fn [form slot cont]
                  (fn [pubs frame vars]
                    (output frame slot (form pubs frame vars))
                    (cont pubs frame vars)))
      :global   (fn [x]
                  (let [r (resolvef ::not-found x)]
                    (case r
                      ::not-found (throw (ex-info (str "Unable to resolve - " (symbol x)) {}))
                      (constantly (pure r)))))
      :literal  (fn [x] (constantly (pure x)))
      :inject   (fn [slot] (constantly (pure (inject slot))))
      :variable (fn [form remote slot]
                  (fn [pubs frame vars]
                    (variable frame vars (+ remote slot) slot
                      (form pubs frame vars))))
      :source   (fn [remote slot cont]
                  (fn [pubs frame vars]
                    (source frame vars (+ remote slot) slot)
                    (cont pubs frame vars)))
      :constant (fn [form static dynamic variable-count source-count constant-count target-count output-count input-count slot]
                  (fn [pubs frame vars]
                    (constant frame slot
                      (constructor (mapv pubs static) dynamic variable-count source-count constant-count target-count output-count input-count
                        (partial form pubs)))))
      :target   (fn [form static dynamic variable-count source-count constant-count target-count output-count input-count slot cont]
                  (fn [pubs frame vars]
                    (target frame slot
                      (constructor (mapv pubs static) dynamic variable-count source-count constant-count target-count output-count input-count
                        (partial form pubs)))
                    (cont pubs frame vars)))
      :main     (fn [form var-count dynamic variable-count source-count constant-count target-count output-count input-count]
                  (peer var-count dynamic
                    variable-count source-count
                    constant-count target-count
                    output-count input-count
                    (partial form [])))})))
