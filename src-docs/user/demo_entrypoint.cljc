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
            user.todos-simple
            user.seven-gui-1-counter
            user.seven-gui-2-temperature-converter
            user.seven-gui-4-timer
            user.seven-gui-5-crud
            hyperfiddle.scrollview
            user.demo-color
            user.tic-tac-toe
            wip.demo-branched-route
            wip.dennis-exception-leak
            #_wip.hfql
            wip.tag-picker
            wip.teeshirt-orders

            ; these demos require datomic on classpath, disabled by default
            #_user.demo-stage-ui4
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
   [::tic-tac-toe user.tic-tac-toe/App]])

(p/def secret-pages
  [[::color user.demo-color/App]
   [::hfql-teeshirt-orders wip.teeshirt-orders/App]
   [::explorer user.demo-7-explorer/App]
   [::demo-10k-dom-elements user.demo-10k-dom-elements/App] ; todo too slow to unmount, crashes
   [::router wip.demo-branched-route/App]
   [::tag-picker wip.tag-picker/App]
   [::dennis-exception-leak wip.dennis-exception-leak/App2]

   ; need Datomic on classpath
   #_[::demo-stage-ui4 user.demo-stage-ui4/Demo]
   #_[::datomic-browser hyperfiddle.datomic-browser/App]])

(p/defn NotFoundPage []
  (p/client
    (dom/h1 (dom/text "Page not found"))))

(p/defn App [route]
  (p/client
    (let [[page & _args] (::hf/route router/route)]
      (dom/div (dom/style {:width "90vw"})
        (case page
          :user-main/index
          (do (dom/h1 (dom/text "Photon Demos"))
              (dom/p (dom/text "See source code in src-docs."))
              (p/for [[k _] pages]
                (dom/div (router/link {::hf/route [k]} (dom/text (name k)))))
              (dom/div (dom/style {:opacity 0})
                (router/link {::hf/route [::secret-hyperfiddle-demos]} (dom/text "secret-hyperfiddle-demos"))))

          ::secret-hyperfiddle-demos
          (do (dom/h1 "Hyperfiddle demos, unstable/wip")
              (dom/p "These may require a Datomic connection and are unstable, wip, often broken")
              (p/for [[k _] secret-pages]
                (dom/div (router/link {::hf/route [k]} (dom/text (name k))))))

          (p/server
            (let [Page (get (into {} (concat pages secret-pages)) page NotFoundPage)]
              (p/client
                (router/router page
                  (p/server (new Page)))))))))))
