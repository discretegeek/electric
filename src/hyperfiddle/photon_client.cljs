(ns hyperfiddle.photon-client
  (:require [hyperfiddle.logger :as log]
            [hyperfiddle.photon-impl.io :as io]
            [missionary.core :as m])
  (:import missionary.Cancelled))

(defn server-url []
  (let [url (.. js/window -hyperfiddle_photon_config -server_url)]
    (assert (string? url) "Missing websocket server url.")
    url))

(defn connect! [socket]
  (let [deliver (m/dfv)]
    (log/info "WS Connecting …")
    (doto socket
      (.addEventListener "open" (fn [] (log/info "WS Connected.")
                                  (deliver socket)))
      (.addEventListener "error" (fn on-error [err]
                                   (.removeEventListener socket "error" on-error)
                                   (log/debug "WS Error" err)
                                   (deliver nil))))
    deliver))

(defn wait-for-close [socket]
  (let [ret (m/dfv)]
    (.addEventListener socket "close" (fn [_] (ret nil)))
    ret))

(defn make-write-chan [socket mailbox]
  (.addEventListener socket "message" #(mailbox (.-data %)))
  (fn [value]
    (fn [success failure]
      (try
        (log/trace "🔼" value)
        (.send socket value)
        (success nil)
        (catch :default e
          (log/error "Failed to write on socket" e)
          (failure e)))
      #(log/info "Canceling websocket write"))))

(defn connect [mailbox]
  (m/ap (loop [] (if-let [socket (m/? (connect! (new js/WebSocket (server-url))))]
                   (m/amb (do (some-> js/document (.getElementById "root") (.setAttribute "data-ws-connected" "true"))
                            (make-write-chan socket mailbox))
                     (do (log/info "WS Waiting for close…")
                       (m/? (wait-for-close socket))
                       (some-> js/document (.getElementById "root") (.setAttribute "data-ws-connected" "false"))
                       (log/info "WS Closed.")
                       (log/info "WS Retry in 2 seconds…")
                       (m/? (m/sleep 2000))
                       (recur)))
                   (do (log/info "WS Failed to connect, retrying in 2 seconds…")
                     (some-> js/document (.getElementById "root") (.setAttribute "data-ws-connected" "false"))
                     (m/? (m/sleep 2000))
                     (recur))))))

(defn client [[client server]]
  (m/reduce {} nil (m/ap
                     (try
                       (let [mailbox    (m/mbx)
                             write-chan (m/?< (connect mailbox))]
                         (log/info "Starting Photon Client")
                         (m/? (write-chan (io/encode server))) ; bootstrap server
                         (m/? (client (io/message-writer write-chan)
                                      (io/message-reader mailbox)))) ; start client
                       (catch Cancelled _
                         "stopped")))))
