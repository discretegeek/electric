(ns user.demo-1-hello-world
  (:require [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom2 :as dom])
  #?(:cljs (:require-macros user.demo-1-hello-world)))

; First demonstration of client/server transfer:
; a full stack function with both frontend and backend parts,
; all defined in the same expression

(p/defn App []
  (p/client
    (dom/h1 (dom/text "Hello World"))
    (dom/div (dom/text "Hello from server, where JVM number type is: ")
      (dom/code (dom/text (p/server (pr-str (type 1))))))
    (dom/div (dom/text "Hello from client, where JS number type is: ")
      (dom/code (dom/text (p/client (pr-str (type 1))))))))
