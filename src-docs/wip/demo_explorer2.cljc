(ns wip.demo-explorer2
  #?(:cljs (:require-macros wip.demo-explorer2))
  (:require [clojure.datafy :refer [datafy]]
            [clojure.core.protocols :refer [nav]]
            #?(:clj clojure.java.io)
            [clojure.spec.alpha :as s]
            [contrib.datafy-fs #?(:clj :as :cljs :as-alias) fs]
            [hyperfiddle.api :as hf]
            [hyperfiddle.electric :as p]
            [hyperfiddle.electric-dom2 :as dom]
            [hyperfiddle.router :as router]
            [hyperfiddle.hfql.tree-to-grid-ui :as ttgui]))

(def unicode-folder "\uD83D\uDCC2") ; 📂

(p/defn App []
  (ttgui/with-gridsheet-renderer
    (binding [hf/db-name "$"]
      (dom/style {:grid-template-columns "repeat(5, 1fr)"})
      (p/server
        (binding [hf/*nav!*   (fn [db e a] (a (datafy e))) ;; FIXME db is specific, hfql should be general
                  hf/*schema* (constantly nil)] ;; FIXME this is datomic specific, hfql should be general
          (let [path (fs/absolute-path "node_modules")]
            (hf/hfql {(props (fs/list-files (props path {::dom/disabled true})) ;; FIXME forward props
                             {::hf/height 30})
                      [(props ::fs/name #_{::hf/render (p/fn [{::hf/keys [Value]}]
                                                       (let [v (Value.)]
                                                         (case (::fs/kind m)
                                                           ::fs/dir (let [absolute-path (::fs/absolute-path m)]
                                                                      (p/client (router/Link. [::fs/dir absolute-path] v)))
                                                           (::fs/other ::fs/symlink ::fs/unknown-kind) v
                                                           v #_(p/client (router/Link. [::fs/file x] v)))))})

                       ;; TODO add links and indentation

                       (props ::fs/modified {::hf/render (p/fn [{::hf/keys [Value]}]
                                                           (p/client
                                                             (dom/text
                                                               (-> (p/server (Value.))
                                                                   .toISOString
                                                                   (.substring 0 10)))))})
                       ::fs/size
                       (props ::fs/kind {::hf/render (p/fn [{::hf/keys [Value]}]
                                                       (let [v (Value.)]
                                                         (p/client
                                                           (case v
                                                             ::fs/dir (dom/text unicode-folder)
                                                             (dom/text (some-> v name))))))})
                       ]})))))))
