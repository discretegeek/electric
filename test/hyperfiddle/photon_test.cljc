(ns hyperfiddle.photon-test
  "Photon language unit tests"
  (:require [hyperfiddle.photon :as p]
            [hyperfiddle.rcf :as rcf :refer [tests ! % with]]
            [missionary.core :as m])
  (:import missionary.Cancelled
           hyperfiddle.photon.Pending))


(tests
  "hello world"
  (with (p/run (! "hello world"))
    % := "hello world"))

(tests
  "literals are lifted"
  (with (p/run (! 1))
    % := 1)

  "data literals"
  (with (p/run (! {:a 1}))
    % := {:a 1})

  "globals lifted"
  (def a 1)
  (with (p/run (! a))
    % := 1)

  (with (p/run (! inc))
    % := inc)

  "clojure call"
  (with (p/run (! (inc (inc 1))))
    % := 3))

(tests
  "introduce foreign atom"
  (def !x (atom 0))
  (with (p/run (! (p/watch !x)))                           ; clojure flow derived from atom
    % := 0
    (swap! !x inc)
    % := 1))

(tests
  "p/def can contain Photon"
  (def !x (atom 0))
  (p/def x (p/watch !x))                                   ; just don't use it from Clojure
  (with (p/run (! x))
    % := 0
    (swap! !x inc)
    % := 1))

(tests
  "introduce foreign missionary signal"
  (def !x (atom 0))                                         ; atoms model variable inputs
  (with (p/run (! (new (m/watch !x))))                      ; clojure flow derived from atom
    % := 0
    (swap! !x inc)
    % := 1))

(comment
  "can't boot flow from Clojure global due to syntax collision with Clojure new"
  (def !x (atom 0))
  (def X (m/watch !x))
  (with (p/run (! (new X)))                                 ; unsupported
    % := #?(:clj 0 :cljs _)
    (swap! !x inc)
    % := 1))

(tests
  "however, CAN boot flow from a Photon global"
  (def !x (atom 0))
  (p/def X (m/watch !x))
  (with (p/run (! (X.)))
    % := #?(:clj 0 :cljs _)
    (swap! !x inc)
    % := 1))

(tests "reactive closures - call them with (new)"
  (with
    (p/run (! (let [x 1
                    F (p/fn [] x)]                          ; capitalized
                [(number? x)
                 (fn? F)
                 (new F)])))                                ; construct/flatmap
    % := [true true 1]))

(tests "dataflow diamond - let introduces shared nodes in the dag"
  (def !x (atom 0))
  (with (p/run (! (let [x (p/watch !x)]
                    (+ x x))))
    % := 0
    (swap! !x inc)
    % := 2
    (swap! !x inc)
    % := 4))

(tests "broken dataflow diamond (two propagation frames - bad)"
  (def !x (atom 0))
  (with (p/run (! (let [X (m/watch !x)]                     ; recipe for flow
                    (+ (X.) (X.)))))                        ; bad - construct flow twice
    % := 0
    (swap! !x inc)
    % := 1                                                  ; glitch due to two watch events,
    % := 2                                                  ; causing two propagation frames
    (swap! !x inc)
    % := 3
    % := 4))

(tests "reactive function call"
  (def !f (atom +))
  (def !x2 (atom 1))
  (with (p/run (! ((p/watch !f) 0 (p/watch !x2))))
    % := 1
    (swap! !x2 inc)
    % := 2
    (reset! !f -)
    % := -2))

(tests "p/def without initial value"
  (def !x (atom 0))
  (p/def X_136)
  (with (p/run (! (binding [X_136 (m/watch !x)]
                    (X_136.))))
    % := 0
    (swap! !x inc)
    % := 1))

(tests "p/def with initial value"
  (def !x (atom 0))
  (p/def X_146 (m/watch !x))
  (with (p/run (! (X_146.)))
    % := 0
    (swap! !x inc)
    % := 1))

(tests "foreign clojure collections. clojure.core/map is not incremental, the arguments are"
  (def !xs (atom [1 2 3]))
  (def !f (atom inc))
  (with
    (p/run
      (! (let [f (new (m/watch !f))
               xs (new (m/watch !xs))]
           (clojure.core/map f xs))))
    % := [2 3 4]
    (swap! !xs conj 4)
    % := [2 3 4 5]
    (reset! !f dec)
    % := [0 1 2 3]))

(tests "common core macros just work"
  (with
    (p/run
      (! (let [f (new (m/watch (atom inc)))
               xs (new (m/watch (atom [1 2 3])))]
           (->> xs (map f)))))
    % := [2 3 4])

  "let destructuring"
  (with (p/run (! (let [[a] (new (m/watch (atom [:a])))] a)))
    % := :a))

(comment
  "reactor termination"
  ; Leo says: pending question. (The test does pass)
  (def !x (atom 0))
  (with (p/run (! (new (->> (m/watch !x) (m/eduction (take-while even?))))))
    % := 0
    (reset! !x 2)
    % := 2
    (reset! !x 1)
    % := ::rcf/timeout))                                    ; never switched odd because it terminated

(tests
  "reactive if"
  (def !a (atom 1))
  (def !p (atom :p))
  (def !q (atom :q))
  (with (p/run (! (if (odd? (new (m/watch !a))) (new (m/watch !p)) (new (m/watch !q)))))
    % := :p
    (swap! !a inc)
    % := :q
    (reset! !p :pp)
    (swap! !a inc)
    % := :pp))

(tests
  "lazy"
  (with (p/run (! (if false (! :a) (! :b))))
    % := :b
    % := :b))

(tests
  "reactive def")

(tests
  "reactive fn"
  (with (p/run (! (new (p/fn [x] (inc x)) 1)))
    % := 2))

(p/def F2 (p/fn [x] (inc x)))
(tests
  "reactive def fn"
  (with (p/run (! (F2. 1)))
    % := 2))

(p/defn My-inc [x] (inc x))
(tests
  "reactive defn"
  (with (p/run (! (My-inc. 1)))
    % := 2))

(tests
  "control flow implemented with lazy signals"
  (p/defn If2 [x a b]                                       ; Key question - how lazy are the parameters?
    (->> (boolean x)
         (get {true (p/fn [] a)
               false (p/fn [] b)})
         (new)))

  (def !x (atom false))
  (def !a (atom :a))
  (def !b (atom :b))
  (with (p/run (let [x (p/watch !x)
                     a (! (p/watch !a))                     ; lazy
                     b (! (p/watch !b))]                    ; lazy
                 (! (If2. x a b))))
    % := :a
    % := :b
    % := :b
    (swap! !x not)
    % := :a))

(tests
  "imperative let behavior (with clojure.core compat) sequences effects in let order, breaking lazy if"
  (def !x (atom false))
  (def !a (atom :a))
  (def !b (atom :b))
  (with (p/run (let [x (p/watch !x)
                     a (! (p/watch !a))
                     b (! (p/watch !b))]
                 (! (if x a b))))
    % := :a    ; too eager, but desirable default for CC compat
    % := :b
    % := :b
    (swap! !x not)
    ;% := :a
    ;% := :b
    % := :a))

(tests
  "reactive case"
  (def !a (atom 0))
  (def !p (atom :p))
  (def !q (atom :q))
  (with (p/run (! (case (p/watch !a)
                    0 (p/watch !p)
                    (p/watch !q))))
    % := :p
    (swap! !a inc)
    % := :q
    (reset! !q :qq)
    % := :qq))

(p/def my-var 1)
(tests
  "def"
  (with (p/run (! my-var))
    % := 1))

(tests
  "binding"
  (p/def foo 1)
  (with (p/run (! (binding [foo 2] foo)))
    % := 2))

(tests
  "lexical closure"
  (with (p/run (! (new (let [a 1] (p/fn [] a)))))
    % := 1))

(tests
  "join captures dynamic scope"
  (p/def foo 1)
  (with (p/run (let [Q (p/fn [] foo)]
                 (binding [foo 2]
                   (! (Q.)))))
    % := 2))

(tests
  "if with bindings"
  (def !a (atom true))
  (p/def foo 1)
  (with (p/run (! (binding [foo 2]
                    (if (p/watch !a)
                      foo
                      (- foo)))))
    % := 2
    (swap! !a not)
    % := -2))

(p/def foo4 1)
(tests
  "if with unwinding binding"
  (def !a (atom true))
  (with (p/run (! (new (binding [foo4 2] (p/fn [] (if (new (m/watch !a)) foo4 (- foo4)))))))
    % := 1
    (swap! !a not)
    % := -1))

(p/def foo 1)
(p/def bar 2)
#_(tests
  ; FIXME unstable
  "internal def"
  (def !a (atom 0))
  (def dispose (p/run (! (new ((def bar) (p/fn [] [foo bar]) (m/watch !a))))))
  % := [1 0] ;; FAIL with [nil 0] in cljs
  (dispose))

(tests
  "reactive for"
  (def !xs (atom [1 2 3]))
  (with (p/run (! (p/for [x (new (m/watch !xs))] (inc x))))
    % := [2 3 4]
    (swap! !xs conj 4)
    % := [2 3 4 5]))

(tests
  "reactive for is differential (diff/patch)"
  (def !xs (atom [1 2 3]))
  (with (p/run (! (p/for [x (new (m/watch !xs))] (! x))))
    (hash-set % % %) := #{1 2 3}                            ; concurrent, order undefined
    % := [1 2 3]
    (swap! !xs conj 4)
    % := 4
    % := [1 2 3 4]
    (swap! !xs pop)
    % := [1 2 3]  ;; TODO glitch here
    (swap! !xs assoc 1 :b)
    % := :b
    % := [1 :b 3]))

(p/def foo 0)
(tests
  "Reactive for with bindings"
  (def !items (atom ["a"]))
  (p/run (binding [foo 1]
           (p/for [item (new (m/watch !items))]
             (! foo)
             item)))

  % := 1
  (swap! !items conj "b")
  % := 1 ; If 0 -> foo’s binding vanished
  )


(tests
  "reactive for with keyfn"
  (def !xs (atom [{:id 1 :name "alice"} {:id 2 :name "bob"}]))
  (with (p/run (! (p/for-by :id [x (new (m/watch !xs))] (! x))))
    (hash-set % %) := #{{:id 1 :name "alice"} {:id 2 :name "bob"}}
    % := [{:id 1 :name "alice"} {:id 2 :name "bob"}]
    (swap! !xs assoc-in [0 :name] "ALICE")
    % := {:id 1 :name "ALICE"}
    % := [{:id 1 :name "ALICE"} {:id 2 :name "bob"}]))

(comment
  (p/run (do :a :b))
  )

(tests
  "reactive do (this is changing soon)"
  ; see: https://www.notion.so/hyperfiddle/What-is-do-let-and-implement-ed781cc5645d4e83aa90b04e31988754
  ; current behavior is not compatible with cc/let
  (def !x (atom 0))
  (with (p/run (! (do (! :a) (! (p/watch !x)))))
    ; Currently, do is not monadic sequence.
    ; It's an incremental computation so only rerun what changed in our opinion
    % := :a
    % := 0
    % := 0
    (swap! !x inc)
    ; no :a
    % := 1
    % := 1))

(tests
  "do forces evaluation (introduces eagerness)"
  ; Current behavior - do stmts are sampled eagerly, as fast as possible
  (def !a (atom 0))
  (def !b (atom 0))
  (with (p/run (! @(doto !b (reset! (! (new (m/watch !a)))))))
    % := 0
    % := 0
    (swap! !a inc)
    ; the ref !b doesn't change, so we don't see 1 again
    % := 1))

(comment
  "entrypoint forces evaluation (introduces eagerness)" ; desired behavior, we think
  ; Alternative - do stmts are sampled (for effect) when result is sampled

  (def !a (atom 0))
  (def !b (atom 0))
  (p/run (! @(doto !b (reset! (! (new (m/watch !a)))))))
  % := 0
  % := 0
  (swap! !a inc)
  % := 1
  % := 1)


(comment
  (rcf/set-timeout! 4000)
  "do stmts run in parallel, not sequence.
  In other words, `do` is sequenceA or sequenceM"
  (def x (m/ap (m/? (m/sleep 1000 :a))))
  (def y (m/ap (m/? (m/sleep 1000 :b))))
  (def z (m/ap (m/? (m/sleep 1000 :c))))
  (with (p/run (! (do (new x) (new y) (new z))))
    ; and took 1 seconds
    % := :c))

; first way (do a b) is same as (let [_ a] b) - true in clojure. Problem here is do stmts (a) are never sampled which is
; never what you want
;
; second way (do a b) is the same as (case a b). a and b are sequenced. problem is b is not constructed until a is
; available (maybe bug). This is what we have today, may not disqualify the current behavior
;
; third way (do a b) is same as ({} a b); both are constructed at the same time and we need both to be available for the
; do expression to be available. whenever a changes, the expr changes.

#?(:clj
   (tests
    "reactive doto"
    (defn MutableMap [] (new java.util.HashMap))
    (defn PutMap [!m k v] (.put !m k v))
    (defn Ref [] (new Object))
    (def !z (atom 0))
    (def !xx (atom 0))
    (def dispose2
      (p/run
        #_(doto (element "input")
            (set-attribute! "type" "text")
            (set-attribute! "value" x))
        (! (doto (MutableMap)                                 ; the doto is incrementalized
             (PutMap "a" (swap! !z inc))                      ; detect effect
             (PutMap "b" (new (m/watch !xx)))))))
    % := {"a" 1 "b" 0}
    (swap! !xx inc)
                                        ;% := ::rcf/timeout       ; old design no further sample, the map hasn't changed
    % := {"a" 1 "b" 1} ; alternative (desired) design will sample again
    (dispose2))
   :cljs
   (tests 1 := 1, 1 := 1) ;; for assert count parity in reports
   )

(p/def trace!)
(p/defn Div [child] (trace! child) [:div child])
(p/defn Widget [x]
  (Div. [(Div. x) (Div. :a)]))

(tests
  "reactive defn"
  ; best example of this is hiccup incremental maintenance
  (def !x (atom 0))
  (with (p/run (! (binding [trace! !]
                    (Widget. (p/watch !x)))))
    % := 0
    % := :a
    % := [[:div 0] [:div :a]]
    % := [:div [[:div 0] [:div :a]]]
    (swap! !x inc)
    % := 1
    ; no :a
    % := [[:div 1] [:div :a]]
    % := [:div [[:div 1] [:div :a]]]))

(p/def G (p/fn [x] x))                                      ; reactive fn (DAG). Compiler marks dag with meta
(tests
  "node call vs fn call"
  (defn f [x] x)                                            ; This var is not marked with meta
  (def !x (atom 0))
  (with
    (p/run
      (! (let [x (new (m/watch !x))]
           [(f x) (G. x)])))
    % := [0 0]))

(p/def G (p/fn [x] x))
(tests
  "higher order dags"
  (def !x (atom 0))
  (defn f [x] x)
  (with
    (p/run
      (! (let [ff #_(fn [x] x) identity                     ; foreign clojure fns are sometimes useful, e.g. passing callbacks to DOM (don't have yet cc/fn, todo)
               Gg (p/fn [x] x)                              ; but you almost always want reactive lambda, not cc/fn
               x (new (m/watch !x))]
           [(f x)                                           ; var marked
            (G. x)                                           ; var says node
            (ff x)                                          ; Must assume interop, for compat with clojure macros
            (Gg. x)                                        ; Must mark reactive-call
            (new (p/fn [x] x) x)])))
    % := [0 0 0 0 0]))

(tests
  "reactive closures"
  (def !x (atom 1))
  (def !y (atom 10))
  (p/def x (p/watch !x))
  (p/def y (p/watch !y))
  (with (p/run (! (new (if (odd? x)
                         (p/fn [x] (* y x))
                         (p/fn [x] (* y x)))
                       x)))
    % := 10
    (swap! !x inc)
    % := 20
    (swap! !x inc)
    % := 30
    (swap! !y inc)
    % := 33
    (swap! !y inc)
    % := 36))

(tests
  "reactive closures 2"
  (def !x (atom 0))
  (def !y (atom 0))
  (with
    (p/run (! (let [x (p/watch !x)
                    y (p/watch !y)
                    F (p/fn [x] (+ y x))                    ; constant signal
                    G (if (odd? x) (p/fn [x] (+ y x))
                                   (p/fn [x] (+ y x)))
                    H (new (m/seed [(p/fn [x] (+ y x))]))]
                [(F. x)
                 (G. x)
                 (H. x)])))
    % := [0 0 0]))

(comment
  ; todo implement fn
  "reactive clojure.core/fn"
  (def !x (atom 0))
  (def !y (atom 0))
  (def dispose
    (p/run
      (! (let [x (new (m/watch !x))
               y (new (m/watch !y))
               ; rebuild clojure closure when y updates
               f (fn [needle] (+ y needle))]
           ; (value is fully compatible with fn contract)
           ; the lambda is as variable as the var it closes over
           ; well defined. It's not allowed to use dataflow inside FN. Compiler can never reach it
           ; compiler will walk it to detect the free variables only
           (f x)))))
  % := 0
  (swap! !y inc)
  % := 1
  (swap! !x inc)
  % := 2
  (dispose))

; if we really want to be able to close over reactive values we
; need to solve the problem of dynamic extent. if a node closes over a
; reactive value and this value is destroyed due to a conditional switching,
; what happens ?
; In other words, there is a dag alive that needs X and X dies
; Should that dag be killed as well, or allowed to live with last known value of x, or undefined?

(comment
  "reactive closure over discarded var"
  (def !a (atom false))
  (def !b (atom 1))
  (def dispose
    (p/run
      (! (new                                               ; call a closure from outside the extent of its parent
           (let [!n (atom (p/fn [] 0))]
             (when (new (m/watch !a))
               (let [x (new (m/watch !b))]
                 (reset! !n (p/fn [] x))))                  ; use mutation to escape the extent of the closure
             (new (m/watch !n)))))))
  := 0
  (swap! !a not)
  := 1
  (swap! !a not)                                            ; watch !b is discarded
  := ::rcf/timeout)

(comment
  "reactive recursion"
  (p/defn fib [n]
    ; todo, recursion doesn't work yet
    (case n
      0 0 1 1
      (+ (new fib (- n 2))                                        ; self recur
         (new fib (- n 1)))))
  (def !x (atom 5))
  (def dispose (p/run (! (fib (new (m/watch !x))))))
  % := 5
  (swap! !x inc)
  ; this will reuse the topmost frame, it is still naive though
  % := 8
  (dispose))

(comment
  "recur special form"
  (p/defn fib' [n]
    (case n
      0 0 1 1
      (+ (recur (- n 2)) ; todo
         (recur (- n 1)))))
  (def !x (atom 5))
  (def dispose (p/run (! (fib' (new (m/watch !x))))))
  % := 5
  (swap! !x inc)
  ; this will reuse the topmost frame, it is still naive though
  % := 8
  (dispose))

; todo loop recur

(comment
  "mutual recursion"
  (declare Pong)
  (p/defn Ping [x] (case x 0 :done (Pong. (dec x))))
  ; can static call infer $ here? Leo needs to think
  (p/defn Pong [x] (Ping. x))
  (def dispose (p/run (! (Ping. 3))))
  % := :done
  (dispose))

(tests
  "For reference, Clojure exceptions have dynamic scope"
  (try
    (let [f (try (fn [] (throw (ex-info "boom" {}))) ; this exception will escape
                 (catch #?(:clj Exception, :cljs :default) _ ::inner))]
      ; the lambda doesn't know it was constructed in a try/catch block
      (f))
    (catch #?(:clj Exception, :cljs :default) _ ::outer))
  := ::outer)

(tests
  "Reactor crashes on uncaugh exceptions"
  (def !x (atom true))
  (with ((p/local (! (assert (p/watch !x)))) ! !)
    % := nil ; assert returns nil or throws
    (swap! !x not) ; will crash the reactor
    #?(:clj (instance? AssertionError %)
       :cljs (instance? js/Error %)) := true
    (swap! !x not) ; reactor will not come back.
    % := ::rcf/timeout))

(p/defn Boom [] (throw (ex-info "" {})))
(tests
  "reactive exceptions"
  (with (p/run (! (try
                    (Boom.)
                    (catch #?(:clj Exception, :cljs :default) e
                      ::inner))))
    % := ::inner))

(tests
  (def dispose
    (p/run (! (try
                (let [Nf (try
                           (p/fn [] (Boom.))             ; reactive exception uncaught
                           (catch #?(:clj Exception, :cljs :default) _ ::inner))]
                  (Nf.))
                (catch #?(:clj Exception, :cljs :default) _ ::outer)))))
  % := ::outer)

; dumb test
;(comment
;  "can take value of bind (previously couldn't)"
;  (p/def nf)
;  (def dispose
;    (p/run (! (binding [nf 1] nf))))
;  % := 1                                        ; runtime error
;  (dispose))

(p/def inner)
(p/def Outer (p/fn [] inner))
(tests
  "dynamic scope (note that try/catch has the same structure)"
  (def dispose (p/run (! (binding [inner ::inner] (Outer.)))))
  % := ::inner
  (dispose)

  (def dispose (p/run (! (binding [inner ::outer]
                           (let [Nf (binding [inner ::inner]
                                      (p/fn [] (Outer.)))]     ; binding out of scope
                             (Nf.))))))
  % := ::outer
  (dispose))

(comment
  "reactive interop with clojure dynamic scope? Watch out for performance gotchas"
  ; motivating use case: (p/defn hf-nav [kf e] (kf (d/entity *db* e)))
  ; we think this is well defined but dangerous because
  ; each and every down-scope function call will react on this implicit global
  ; which can be catastrophic to performance
  (def ^:dynamic *db*)
  ; all reactive bindings are captured and attached to both of these calls
  ; only reactive bindings must be translated, not clojure bindings
  (defn not-query [] (inc 1))                               ; reacts on implicit global !!
  (defn query [] (inc *db*))
  (def !x (atom 0))
  (with (p/run (! (binding [*db* (new (m/watch !x))] (query))))
    % := 0
    (swap! !x inc)
    % := 1))

; p/fn can be used as "reactive quote" to introduce flow transformers
(comment
  "flow transformers"
  ; :: (a -> m b) -> (a -> m b)
  ; widgets take a flow and return a new flow.
  )

(tests
  "lazy parameters. Flows are not run unless sampled"
  (def dispose (p/run (new (p/fn [_]) (! :boom))))
  % := :boom
  (dispose)

  (def dispose (p/run (let [_ (! :bang)])))                 ; todo, cc/let should sequence effects for cc compat
  % := :bang
  (dispose))

(tests
  "client/server transfer"
  ; Pending state is an error state.
  ; Pending errors will crash the reactor if not caugh
  (p/run (try (! ~@~@1) (catch Pending _)))
  % := 1)

(p/def foo nil)
(tests
  (p/run (try (! (binding [foo 1] ~@~@foo))
           (catch Pending _)))
  % := 1)

(p/def foo nil)
(tests
  (p/run (try (! (binding [foo 1] ~@(new (p/fn [] ~@foo))))
           (catch Pending _)))
  % := 1)

(p/def foo1 nil)
(p/def Bar1 (p/fn [] ~@foo1))
(tests
  (p/run (try (! (binding [foo1 1] ~@(Bar1.)))
           (catch Pending _)))
  % := 1)

(tests
  "reactive pending states"
  ;~(m/reductions {} hyperfiddle.photon-impl.runtime/pending m/none)
  (def dispose (p/run (! (try true (catch Pending _ ::pending)))))
  % := true)

(tests
  (p/run (! (try ~@1 (catch Pending _ ::pending))))
  % := ::pending    ; Use try/catch to intercept special pending state
  % := 1)

(tests
  (p/run (! (try [(! 1) (! ~@2)]
                 (catch Pending _
                   ::pending))))
  % := 1
  % := ::pending
  ; do not see 1 again
  % := 2
  % := [1 2])

;(tests
;  (p/run (! (try (dom/div)                              ; must be cleaned up by pending state - in dom layer. todo
;                 (dom/div ~@1)
;                 (catch Pending _
;                   (dom/div ::pending)))))
;  % := ::pending
;  % := 1)

;(tests
;  (p/run (! (try [~@(d/q) ~@(d/entity)]
;                 (catch Pending _
;                   ::pending))))
;  % := ::pending
;  % := 1)

(defn hook [mount! unmount!]
  (m/observe (fn [!]
               (mount!)
               (! nil)
               #(unmount!))))

(tests "object lifecycle"
  (def !x (atom 0))
  (def dispose!
    (p/run (!
             (let [x (new (m/watch !x))]
               (if (even? x)
                 (new (p/fn [x]
                        (new (hook (partial ! 'mount) (partial ! 'unmount)))
                        x)
                      x))))))

  % := 'mount
  % := 0
  (swap! !x inc)
  % := 'unmount
  % := nil
  (swap! !x inc)
  % := 'mount
  % := 2
  (dispose!)
  % := 'unmount)

(comment
  "object lifecycle 2, cleaner, no bug in this one"
  (defn hook [x !]
    (m/observe (fn [send!]
                 (! 'mount)
                 (send! x)
                 #(! 'unmount))))

  (p/defn Foo [x !]
    (new (hook x !)))
  
  (def !x (atom 0))
  (def dispose
    (p/run (!
             (let [x (new (m/watch !x))]
               (if (even? x)
                 (Foo. x !))))))
  % := 'mount
  % := 0
  (swap! !x inc)
  % := 'unmount
  % := nil
  (swap! !x inc)
  % := 'mount
  % := 2
  (dispose)
  % := 'unmount)

(tests
  "object lifecycle 3"
  (defn observer [x]
    (fn mount [f]
      (f (! [:up x]))
      (fn unmount [] (! [:down x]))))

  (def !state (atom [1]))
  (with (p/run (p/for [x (p/watch !state)]
                 (new (m/observe (observer x)))))
    % := [:up 1]
    (swap! !state conj 2)
    % := [:up 2]
    (reset! !state [3])
    (hash-set % % %) := #{[:up 3] [:down 1] [:down 2]}))

(p/def x2 1)
(tests
  "object lifecycle 4"
  (def !input (atom [1 2]))
  (defn up-down [x trace!] (m/observe (fn [!] (trace! :up) (! x) #(trace! :down))))

  (p/run
    (! (p/for [id (new (m/watch !input))]
         (binding [x2 (do id x2)]
           (new (up-down x2 !))))))
  [% %] := [:up :up]
  % := [1 1]
  (swap! !input pop)
  % := :down
  % := [1])

(comment
  "photon binding transfer"
  ; Guidance: distribution should not impact the evaluated result of the expr
  (tests
    (p/defn Expr [x] x)
    (p/run (! ~@(Expr. 1)))
    % := 1)

  (tests
    (p/def Expr (p/fn [] (let [x %0] x)))
    (p/run (! ~@(binding [%0 1] (Expr.))))                ; no binding transfer
    % := 1)

  (tests
    (p/def Expr (p/fn [] (let [x %0] x)))
    (p/run (! (binding [%0 1] ~@(Expr.))))                ; binding transfer
    % := 1))

(tests
  (def !x (atom 0))
  (p/run
    (let [x (new (m/watch !x))]
      (when (even? x) (! x))))
  % := 0
  (swap! !x inc)
  (swap! !x inc)
  % := 2
  )

(comment
  "clojure metadata"
  ; is this problematic?
  ; leo says if transit encodes this then it will work for free
  ; thus keep clojure core behavior wrt metas
  (p/run
    (let [x (with-meta {} {:foo 1})]
      ; works with explicit do
      ; crashes currently
      (! (meta x))
      (! (meta ~@x))
      (! (meta ~@~@x))))
  % := {:foo 1}
  % := {:foo 1}
  % := {:foo 1})

(tests
  "reactive metadata"
  (def !x (atom 0))
  (p/run (! (meta (let [x (with-meta [] {:foo (new (m/watch !x))})] x))))
  % := {:foo 0}
  (swap! !x inc)
  % := {:foo 1})

(p/def foo2 42)

;; TODO fixme, hangs
(comment
 (let [Foo (m/ap (m/? (m/sleep 10 :foo)))]
   (p/run (! (new (new (p/fn [] (let [a (Foo.)] (p/fn [] a)))))))
   % := ::rcf/timeout))

;; TODO fixme
(comment
  "regression: cancel on reactive quote"

  ; prove that if we pass this fn a reactive quote,
  ; then it will fail to cancel properly. The switch will cancel
  ; the quote then await termination which never happens.
  (defn x [>a] (m/ap (m/?< (m/seed [:a 2]))
                 (try (m/?< >a) (catch Cancelled _))))

  ; To repro the bug the >a must just be a reactive var

  (p/run (! (new (x (p/fn [] foo2)))))
  % := 42
  % := ::rcf/timeout  ; do not produce 42 twice
  )

;; TODO fixme
(comment
  ""

  ; prove that if we pass this fn a reactive quote,
  ; then it will fail to cancel properly. The switch will cancel
  ; the quote then await termination which never happens.
  (defn x [>a] (m/ap (m/?< (m/seed [1 2]))
                 (try (m/?< >a) (catch Cancelled _))))

  ; To repro the bug the >a must just be a reactive var

  (p/run (! (new (x (let [x foo2] (p/fn [] x))))))
  % := 42
  % := ::rcf/timeout  ; do not produce 42 twice
  )

;; TODO fixme, hangs
(comment
  "undefined continuous flow, flow is not defined for the first 10ms"
  (let [flow (m/ap (m/? (m/sleep 10 :foo)))]
    (p/run (! (new (new (p/fn [] (let [a (new flow)] (p/fn [] a)))))))
    % := ::rcf/timeout ;; FAIL should throw, does nothing instead
    ))

(tests
  (def !x (atom 0))
  (p/run (! (try (-> (new (m/watch !x))
                   (doto (-> even? (when-not (throw (ex-info "odd" {})))))
                   (/ 2))
                 (catch #?(:clj Exception, :cljs :default) e (ex-message e)))))
  % := 0
  (swap! !x inc)
  % := "odd"
  (swap! !x inc)
  % := 1)

(tests
  (def !x (atom 0))
  (def !f (atom "hello"))
  (def e (ex-info "error" {}))
  (p/run
    (! (try (if (even? (p/watch !x)) :ok (throw e))
         (catch #?(:clj Throwable, :cljs :default) _ :caugh)
         (finally (! (p/watch !f))))))
  % := "hello"
  % := :ok
  (swap! !x inc)
  % := :caugh
  (reset! !f "world")
  % := "world"
  % := :caugh
  (swap! !x inc)
  % := :ok)

(tests
  (p/run-with (p/vars vector) (prn (p/for [id ~@[1]] id))))

;; (tests
;;   (r/run (! ~#'(when (true? true) :ok)))
;;   % := :ok ; pass

;;   (r/run (! ~#'(when (true? ~@ true) :ok)))
;;   % := :ok)

;; (tests
;;   (let [!xs     (atom [])
;;         failure (hyperfiddle.photon-impl.runtime/->Failure ":trollface:")
;;         dispose (p/run (! (r/for [x ~(m/watch !xs)] x)))]

;;     % := []

;;     (reset! !xs failure)  ; won’t call `!` , failure state bypasses apply.
;;     (reset! !xs [1])

;;     % := []  ; collapse bug
;;     % := [1]

;;     (dispose)))

;; (tests
;;   (def !value (atom 0))
;;   (p/run (! (let [v ~(m/watch !value)] ~@ (do (prn v) :nil))))
;;   ;; print 0
;;   % := :nil
;;   (swap! !value inc)
;;   ;; print 1
;;   % := :nil ;; :nil sent N times to other peer. Waste of resources.
;;   )

(p/def unbound1)
(p/def unbound2)
(tests
  (p/run (! (new (p/fn [] (binding [unbound1 1 unbound2 2] (+ unbound1 unbound2))))))
  % := 3)

#?(:clj 
(tests
  "understand how Clojure handles unbound vars"
  ; In Clojure,
  ; Is unbound var defined or undefined behavior?
  ; What does it mean in CLJS? No vars in cljs.
  (def ^:dynamic y_964)
  (bound? #'y_964) := false
  (.isBound #'y_964) := false
  (def unbound (clojure.lang.Var$Unbound. #'y_964))
  (instance? clojure.lang.Var$Unbound unbound) := true

  ; leaking unbounded value
  (instance? clojure.lang.Var$Unbound y_964) := true

  ; not an error in clojure
  (try y_964 (catch Exception e nil))
  (instance? clojure.lang.Var$Unbound *1) := true)
)

(tests
  "In Photon, accessing an unbound var throws a userland exception"
  ;; An unbound var is either:
  ;; - an uninitialized p/def,
  ;; - an unsatisfied reactive fn parameter (reactive fn called with too few arguments).
  (p/def x)
  (with ((p/local x) prn !)
    (ex-message %) := "Unbound var."))

(tests
  "Calling a reactive fn with less arguments than expected throws a userland exception"
  (with ((p/local (new (p/fn [x] x) #_1)) prn !)
    (ex-message %) := "Unbound var."))

(tests
  "Unbound var access can be caugh with try/catch"
  (with (p/run (! (try (new (p/fn [x] x) #_1)
                    (catch #?(:clj Error, :cljs :default) _
                      :unbound-var-access))))
    % := :unbound-var-access))
    
(tests
 "Initial p/def binding is readily available in p/run"
 (def !x (atom 0))
 (p/def X (m/watch !x))
 (with (p/run (! (X.)))
       % := 0
       (swap! !x inc)
       % := 1))

(comment ;; Hangs the REPL
 (let [n 1000]
   (dotimes [_ 8]
     (with (p/run (! (p/for [x (range n)] x)))
           (count %) := n))))

#?(:clj
   (tests ; GG: IDE doc on hover support
    "Vars created with p/defn have the same metas as created with cc/defn"
    (p/defn Documented "doc" [a b c])
    (select-keys (meta (var Documented)) [:name :doc :arglists])
    := {:name 'Documented
        :doc  "doc"
        :arglists '([a b c])}))

(tests
  "pentagram of death - via Kenny Tilton"
  ; Key elements:
  ;  - two dependency chains from some property P leading back to one property X; and
  ;  - branching code in the derivation of P that will not travel the second dependency chain until a
  ;    certain condition is met; and
  ;  - by chance, propagation reaches P on the the first path before it reaches some intermediate property
  ;    I on the second dependency chain.
  ; The consequence is P updating once and reading (for the first time) property I, which has not yet been
  ; updated hence is inconsistent with the new value of X. This inconsistency is temporary (hence the name
  ; "glitch") because I will be updated soon enough and P will achieve consistency with X, but if one's
  ; reactive engine dispatches side effects off state change -- possible trouble.
  (def !aa (atom 1))
  (def !a7 (atom 7))
  (with
    (p/run
      (let [aa  (p/watch !aa)
            a7  (p/watch !a7)
            a70 (* 10 a7)
            bb  aa
            cc  (* 10 aa)
            dd  (if (even? bb)
                  (* 10 cc)
                  42)]
        (! (+ a70 bb (* 10000 dd)))))
    % := 420071
    (swap! !aa inc)
    % := 2000072
    (swap! !aa inc)
    % := 420073))

(tests
  "pentagram of death reduced"
  ; the essence of the problem is:
  ; 1. if/case switch/change the DAG (imagine a railroad switch between two train tracks)
  ; 2. to have a conditional where the predicate and the consequent have a common dependency
  (def !x (atom 1))
  (with (p/run (! (let [p       (p/watch !x)
                        q       (! (str p))
                        control (- p)]
                    (case control -1 p -2 q q))))
    % := "1"                                                ; cc/let sequences effects
    % := 1                                                  ; cross
    (swap! !x inc)
    % := "2"                                                ; q first touched
    % := "2"))

(tests
  "for with literal input"
  (with (p/run (! (p/for [x [1 2 3]] (! x))))

    (hash-set % % %) := #{1 2 3}
    % := [1 2 3]))

(tests
  "for with literal input, nested"
  (def !x (atom 0))
  (with (p/run (! (when (even? (p/watch !x))
                      (p/for [x [1 2 3]]
                        (! x)))))
    (hash-set % % %) := #{1 2 3}
    % := [1 2 3]
    (swap! !x inc)
    % := nil))

(tests
  "nested closure"
  (def !x (atom 0))
  (with (p/run (! (new (let [x (new (m/watch !x))]
                         (if (even? x)
                           (p/fn [] :even)
                           (p/fn [] :odd))))))
    % := :even
    (swap! !x inc)
    % := :odd))

(tests
  "simultaneous add and remove in a for with a nested hook"
  (def !xs (atom [1]))
  (defn hook
    ([x] (! [x]))
    ([x y] (! [x y])))
  (with
    (p/run
      (! (new (p/hook hook 0
                (p/fn []
                  (p/for [x (new (m/watch !xs))]
                    (new (p/hook hook x
                           (p/fn [] (str x))))))))))
    % := [1 nil]
    % := ["1"]
    (reset! !xs [2])
    % := [1]
    % := [2 nil]
    % := ["2"]))

(tests
  (def !t (atom true))
  (p/run
    (! (try (let [t (p/watch !t)]
              (when t t ~@t))
            (catch Pending _ :pending)
            (catch Cancelled _ :cancelled))))
  % := :pending
  % := true
  (swap! !t not)
  % := :cancelled
  % := nil)


(tests
 (def !state (atom true))
 (with (p/run (when (p/watch !state) (! :touch)))
       % := :touch
       (reset! !state true)
       % := :touch #_::rcf/timeout ; FAIL returns :touch, indicating branch was rerun
                                   ;      even if condition did not toggle.
       )
 (def !state2 (atom true))
 (with (p/run (when (p/deduping (p/watch !state2)) (! :touch)))
       % := :touch
       (reset! !state true)
       % := ::rcf/timeout) ; PASS because of deduping
 )
