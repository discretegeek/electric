(ns user.photon.photon-recursion
  (:require [hyperfiddle.electric :as p]
            [hyperfiddle.rcf :refer [tests tap % with]]))

(hyperfiddle.rcf/enable!)

; Photon compiler hasn't implemented recursion syntax yet but the primitives are in place
; so you can do it with explicit dynamic vars

(p/def Ping)                                                ; all Photon defs are dynamic
(p/def Pong)

(tests
  "recursion"
  (def !x (atom 0))
  (with (p/run
          (binding [Ping (p/fn [x] (case (tap x) 0 :done (Ping. (dec x))))]
            (tap (Ping. (p/watch !x)))))
    [% %] := [0 :done]
    (swap! !x inc)
    [% % %] := [1 0 :done]
    (swap! !x inc)
    [% % % %] := [2 1 0 :done]
    (swap! !x inc)))

(tests
  "mutual recursion"
  (def !x (atom 1))
  (with (p/run
          (binding [Ping (p/fn [x] (case (tap x) 0 :done (Pong. (dec x))))
                    Pong (p/fn [x] (Ping. x))]
            (tap (Ping. (p/watch !x)))))
    [% % %] := [1 0 :done]
    (swap! !x inc)
    [% % % %] := [2 1 0 :done]))

(tests
  "reactive fibonacci"
  (p/def Fib)
  (def !x (atom 5))
  (with (p/run (binding [Fib (p/fn [n]
                               (case n
                                 0 0 1 1
                                 (+ (Fib. (- n 2))       ; self recur
                                    (Fib. (- n 1)))))]
                 (tap (Fib. (p/watch !x)))))
    % := 5
    (swap! !x inc)
    ; reactive engine will reuse the topmost frame, it is still naive fib though
    % := 8))

; Todo: self-recursion and Clojure recur form
