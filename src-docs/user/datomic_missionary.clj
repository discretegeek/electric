(ns user.datomic-missionary
  (:require [contrib.data :refer [omit-keys-ns]]
            datomic.client.api
            [datomic.client.api.async :as d]
            [missionary.core :as m]
            [hyperfiddle.photon :as p]))

(defn client [arg-map] (datomic.client.api/client arg-map))
(defn connect [client arg-map] (datomic.client.api/connect client arg-map))
(defn db [conn] (d/db conn))

;(defn entity! [db e] (reify ...))
;(defn touch! [db e] (pull! db e ['*]))

(defn db-stats [db] (p/chan-read! (d/db-stats db)))

(comment (m/? (db-stats user/db)))

(defn datoms> [db arg-map]
  (->> (p/chan->ap (d/datoms db arg-map))
       (m/eduction cat))) ; ?

(comment
  (take 3 (m/? (m/reduce conj [] (datoms> user/db {:index :aevt, :components [:db/ident]}))))
  (take 3 (m/? (m/reduce conj [] (datoms> user/db {:index :aevt, :components [:db/txInstant]})))))

(defn datoms! [db arg-map] ; waits for completion before returning, for repl only really
  (p/chan->task (d/datoms db arg-map)))

(comment
  (count (m/? (datoms! user/db {:index :aevt, :components [:db/ident]})))

  "Recent TX"
  (->> (datoms> user/db {:index :aevt, :components [:db/txInstant]})
       (m/reduce conj ()) m/?
       (take 5)))

(defn tx-range> [conn arg-map] ; has pagination
  (p/chan->ap (d/tx-range conn arg-map)))

(comment
  "scan all tx"
  (->> (tx-range> user/datomic-conn {:start nil :end nil})
       (m/eduction (map :data) cat (drop 20) (take 5))
       (m/reduce conj [])
       m/?)

  "datoms for tx"
  (->> (tx-range> user/datomic-conn {:start 0, :end 1})
       (m/eduction (map :data) cat (take 5))
       (m/reduce conj [])
       m/?))

(defn q [arg-map] (->> (d/q arg-map) p/chan->ap (m/eduction cat)))
(defn q! [arg-map] (->> (q arg-map) (m/reduce conj [])))

(comment
  (def query-attrs '[:find (pull ?e [:db/ident]) ?f :where [?e :db/valueType ?f]])
  (def >x (q {:query query-attrs :args [user/db]}))
  (def it (>x #(println ::notify) #(println ::terminate)))
  @it
  (it)

  (->> (q {:query query-attrs :args [user/db]}) (m/reduce conj []) m/?)
  (m/? (q! {:query query-attrs :args [user/db]})))

; The returned seq object efficiently supports count.
(defn qseq [arg-map] (->> (p/chan->ap (d/qseq arg-map))
                          (m/eduction cat))) ; qseq returns chunks, smooth them out

(comment
  (defn attrs> [db]
    (qseq {:query '[:find (pull ?e [:db/ident]) ?f :where [?e :db/valueType ?f]] :args [db]}))

  (m/? (->> (attrs> user/db)
            (m/eduction (take 3))
            (m/reduce conj []))))

(defn history [db] (d/history db))

(defn pull! [db arg-map]
  (m/sp (let [arg-map' (omit-keys-ns (namespace ::this) arg-map)
              ; opportunity to validate
              ;{:pre [(some? e)]} ; fixme when this fires, the exception is never seen
              tree (m/? (p/chan-read! (d/pull db arg-map')))]
          (if (contains? arg-map ::compare)
            ; Use datafy/nav to sort on the fly? need to know cardinality many attrs and sort on nav
            ; unless we can use datoms API to make datomic sort them
            (into (sorted-map-by (::compare arg-map)) tree)
            tree))))

; extras ?


;(defn transactions> [conn]
;  (->> (p/chan->ap (d/tx-range conn {:start nil :end nil}))
;       (m/eduction (map :data))))

;#?(:clj (defn attributes! [db pull-pattern]
;          (m/sp (->> (m/? (p/chan->task
;                            (d/q {:query '[:find (pull ?e pattern)
;                                           :in $ pattern
;                                           :where [?e :db/valueType _]]
;                                  :args [db pull-pattern]})))
;                     (map first)
;                     (sort-by :db/ident)))))
;
;(comment (time (take 3 (m/? (attributes! db [:db/ident])))))

; #?(:clj (defn attributes<
;          ([db]
;           (->> (attributes> db)
;                (m/eduction cat) ; ?
;                (m/reductions conj [])
;                (m/latest identity)))
;          ([db pull-pattern]
;           (->> (attributes> db pull-pattern)
;                (m/eduction cat) ; ?
;                (m/reductions conj [])
;                (m/latest identity)))))
; (comment
;  (time (m/? (m/reduce into [] (attributes> db [:db/ident]))))
;  (time (m/? (m/reduce into [] (attributes> db)))))

;#?(:clj (defn entity-datoms! [db e] (p/chan->task (d/datoms db {:index :eavt, :components [e]}))))
;#?(:clj (defn entity-datoms> [db e] (->> (p/chan->ap (d/datoms db {:index :eavt, :components [e]}))
;                                         (m/eduction (mapcat identity)))))
;#?(:clj (defn entity-datoms< [db a] (->> (entity-datoms> db a)
;                                         (m/reductions conj [])
;                                         (m/latest identity)))) ; FIXME BUFFER
;
;(comment
;  (m/? (entity-datoms! db 1))
;  (m/? (entity-datoms! db :db/ident))
;  (take 3 (m/? (m/reduce conj [] (entity-datoms> db :db/ident)))))