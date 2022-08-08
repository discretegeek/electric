(ns user.demo-1-hello-world
  (:require [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom :as dom])
  #?(:cljs (:require-macros user.demo-1-hello-world)))

(p/defn App []
  (dom/h1 (dom/text "Hello World"))
  (dom/div (dom/text "Hello from server, where JVM number type is: ")
           (dom/code (dom/text (p/server (pr-str (type 1))))))
  (dom/div (dom/text "Hello from client, where JS number type is: " )
           (dom/code (dom/text (p/client (pr-str (type 1)))))))
