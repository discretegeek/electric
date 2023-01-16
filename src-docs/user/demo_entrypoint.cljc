(ns user.demo-entrypoint
  #?(:cljs (:require-macros user.demo-entrypoint))
  (:require [hyperfiddle.api :as hf]
            [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom2 :as dom]
            [hyperfiddle.router :as router]
            user.demo-1-hello-world
            user.demo-2-toggle
            user.demo-3-system-properties
            user.demo-4-chat
            user.demo-4-chat-extended
            user.demo-4-webview
            user.demo-5-todomvc
            user.demo-todomvc-composed
            user.demo-6-two-clocks
            user.demo-7-explorer
            user.demo-10k-dom-elements
            user.demo-controlled-input
            user.todos-simple
            user.seven-gui-1-counter
            user.seven-gui-2-temperature-converter
            user.seven-gui-4-timer
            user.seven-gui-5-crud
            hyperfiddle.scrollview
            user.demo-color
            user.tic-tac-toe
            wip.typeahead-ui1
            #_wip.hfql
            #_user.demo-hfql
            wip.teeshirt-orders
            wip.demo-branched-route

            ; these demos require datomic on classpath, disabled by default
            user.demo-stage-ui3
            #_hyperfiddle.datomic-browser))

(p/def pages
  [[::hello-world user.demo-1-hello-world/App]
   [::toggle user.demo-2-toggle/App]
   [::system-properties user.demo-3-system-properties/App]
   [::chat user.demo-4-chat/App]
   [::chat-extended user.demo-4-chat-extended/App]
   [::webview user.demo-4-webview/App]
   #_[::todos-simple user.todos-simple/Todo-list] ; css fixes
   [::todomvc user.demo-5-todomvc/App]
   [::todomvc-composed user.demo-todomvc-composed/App]
   [::two-clocks user.demo-6-two-clocks/App]
   [::infinite-scroll hyperfiddle.scrollview/Demo]
   [::seven-guis-counter user.seven-gui-1-counter/Counter]
   [::seven-guis-temperature-converter user.seven-gui-2-temperature-converter/App]
   [::seven-guis-timer user.seven-gui-4-timer/Timer]
   [::seven-guis-crud user.seven-gui-5-crud/App]
   [::typeahead wip.typeahead-ui1/App]
   #_[::tic-tac-toe user.tic-tac-toe/App]])

(p/def secret-pages
  [[::color user.demo-color/App]
   [::controlled-input user.demo-controlled-input/App]
   [::demo-stage-ui3 user.demo-stage-ui3/Demo]
   #_[::hfql-teeshirt-orders wip.teeshirt-orders/App] ; todo need nested router
   #_[::explorer user.demo-7-explorer/App] ; todo needs nested router
   #_[::datomic-browser hyperfiddle.datomic-browser/App]
   #_[::demo-10k-dom-elements user.demo-10k-dom-elements/App] ; todo too slow to unmount, crashes
   #_[::hfql user.demo-hfql/App]
   #_[::hfql2 wip.hfql/App]
   [::router wip.demo-branched-route/App]])

(defmacro link [href label On-Click & body]
  `(dom/a (dom/props {:href ~href})
     (dom/text ~label)
     (when-some [e# (dom/Event. "click" false)]
       (.preventDefault e#)
       (new ~On-Click e#))
     ~@body))

(p/defn App [route]
  (p/client
    (let [page (::hf/route route)]
      (dom/div (dom/style {:width "90vw"})
        (case page
          :user-main/index
          (do (dom/h1 (dom/text "Photon Demos"))
              (dom/p (dom/text "See source code in src-docs."))
              (p/for [[k _] pages]
                (dom/div (link k (name k) (p/fn [_] (hf/navigate! k)))))
              (dom/div (dom/style {:opacity 0})
                (link ::secret-hyperfiddle-demos "secret-hyperfiddle-demos"
                  (p/fn [_] (hf/navigate! ::secret-hyperfiddle-demos)))))

          ::secret-hyperfiddle-demos
          (do (dom/h1 "Hyperfiddle demos, unstable/wip")
              (dom/p "These may require a Datomic connection and are unstable, wip, often broken")
              (p/for [[k _] secret-pages]
                (dom/div (link k (name k) (p/fn [_] (hf/navigate! k))))))

          (p/server
            (let [Page (get (into {} (concat pages secret-pages)) page)]
              (new Page))))))))
