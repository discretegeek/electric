(ns user.demo-4-webview
  "Photon fullstack query/view composition with client/server transfer"
  (:require #?(:clj [datascript.core :as d])
            [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom :as dom]
            [hyperfiddle.photon-ui4 :as ui4])
  #?(:cljs (:require-macros user.demo-4-webview)))

; A database backed webview with reactive updates.
; The webview is subscribed to the database, which updates with each transaction.
; Run a transaction (from the REPL) and see the connected tabs update live.

(defonce conn #?(:cljs nil                                  ; state survives reload
                 :clj  (doto (d/create-conn {:order/email {}})
                         (d/transact! [{:order/email "alice@example.com" :order/gender :order/female}
                                       {:order/email "bob@example.com" :order/gender :order/male}
                                       {:order/email "charlie@example.com" :order/gender :order/male}]))))

(defn teeshirt-orders [db ?email]
  #?(:clj
     (sort
       (d/q '[:find [?e ...]
              :in $ ?needle :where
              [?e :order/email ?email]
              [(clojure.string/includes? ?email ?needle)]]
            db (or ?email "")))))

(p/defn Teeshirt-orders-view [db]
  (p/client
    (dom/div
      (dom/h2 (dom/text "frontend/backend webview with server push"))
      (let [!search (atom ""), search (p/watch !search)]
        (ui4/input search (p/fn [v] (reset! !search v))
          (dom/props {:placeholder "Filter…"}))
        (dom/table (dom/props {:class "hyperfiddle"})
          (p/server
            (p/for [id (teeshirt-orders db search)]
              (let [!e (d/entity db id)]
                (p/client
                  (dom/tr
                    (dom/td (dom/text id))
                    (dom/td (dom/text (p/server (:order/email !e))))
                    (dom/td (dom/text (p/server (:order/gender !e))))))))))))))

(p/defn App []
  (let [db (p/watch conn)] ; reactive "database value"
    (Teeshirt-orders-view. db)))

(comment
  #?(:clj (d/transact conn [{:db/id 2 :order/email "bob2@example.com"}]))
  #?(:clj (d/transact conn [{:order/email "dan@example.com"}]))
  #?(:clj (d/transact conn [{:order/email "erin@example.com"}]))
  #?(:clj (d/transact conn [{:order/email "frank@example.com"}]))
  )
