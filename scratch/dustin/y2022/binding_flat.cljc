(ns dustin.y2022.binding2
  (:require [clojure.walk :refer [macroexpand-all]]
            [hyperfiddle.rcf :refer [tests]]))

(comment
  (defmacro binding2 [bindings & body]
    (loop [[[s expr :as x] & xs] (partition 2 bindings)]
      `(binding [~s ~expr]
         ~(if (seq xs)
            (recur xs)
            ~@body)))))

(defmacro binding2 [bindings & body]
  (let [[s expr & xs] bindings]
    `(binding [~s ~expr]
       ~(if (seq xs)
          `(binding2 ~xs ~@body)
          `(do ~@body)))))

(defmacro binding2 [bindings & body]
  (let [[s expr & xs] bindings]
    (if (seq xs)
      `(binding [~s ~expr] (binding2 ~xs ~@body))
      `(binding [~s ~expr] ~@body))))

(defmacro binding2 [bindings & body]
  (let [[s expr & xs] bindings]
    (if (seq xs)
      `(binding [~s ~expr] ~`(binding2 ~xs ~@body))
      `(binding [~s ~expr] ~@body))))

(defn binding2* [bindings body]
  (let [[s expr & xs] bindings]
    (if (seq xs)
      `(binding [~s ~expr] ~(binding2* xs body))
      `(binding [~s ~expr] ~@body))))

(defmacro binding2 [bindings & body]
  (binding2* bindings body))

(tests
  (macroexpand-1 '(binding2 [a 1 b (inc a)] (inc b)))
  := '(binding [a 1]
        (binding [b (inc a)]
          (inc b)))

  (def ^:dynamic a)
  (def ^:dynamic b)
  (binding2 [a 1 b (inc a)] (inc b)) := 3

  )

(comment

  `(do ~`a)
  )