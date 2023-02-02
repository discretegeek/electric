(ns hyperfiddle.scrollview
  (:require [contrib.data :refer [unqualify]]
            [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom2 :as dom]
            [hyperfiddle.photon-ui4 :as ui]
            [missionary.core :as m]
            #?(:cljs goog.object))
  #?(:cljs (:require-macros hyperfiddle.scrollview)))

#?(:cljs (defn sample-scroll-state! [scrollable]
           [(.. scrollable -scrollTop) ; optimization - detect changes (pointless)
            (.. scrollable -scrollHeight) ; snapshot height to detect layout shifts in flipped mode
            (.. scrollable -clientHeight)])) ; measured viewport height (scrollbar length)

#?(:cljs (defn scroll-state> [scrollable]
           (m/observe
             (fn [!]
               (let [sample (fn [] (! (sample-scroll-state! scrollable)))]
                 (.addEventListener scrollable "scroll" sample #js {"passive" true})
                 #(.removeEventListener scrollable "scroll" sample))))))

#?(:cljs (def !scrollStateDebug (atom nil)))

(defn throttle [dur >in]
  (m/ap
    (let [x (m/?> (m/relieve {} >in))]
      (m/amb x (do (m/? (m/sleep dur)) (m/amb))))))

#?(:cljs (defn scroll-state< [scrollable]
           (->> (scroll-state> scrollable)
                (throttle 16) ; RAF interval
                (m/reductions {} [0 0 0])
                (m/relieve {})
                (m/latest (fn [[scrollTop scrollHeight clientHeight :as s]]
                            (reset! !scrollStateDebug {::scrollTop scrollTop
                                                       ::scrollHeight scrollHeight
                                                       ::clientHeight clientHeight})
                            s)))))

(p/defn DemoFixedHeightCounted
  "Scrolls like google sheets. this can efficiently jump through a large indexed collection"
  []
  (let [row-count 500
        xs (vec (range row-count)) ; counted
        page-size 100
        row-height 22] ; todo use relative measurement (browser zoom impacts px height)
    (p/client
      (dom/div (dom/props {:class "viewport" :style {:overflowX "hidden" :overflowY "auto"}})
        (let [[scrollTop] (new (scroll-state< dom/node))
              max-height (* row-count row-height)
              clamped-scroll-top (js/Math.min scrollTop max-height)
              start (/ clamped-scroll-top row-height)] ; (js/Math.floor)
          (dom/div (dom/props {:style {:height (str (* row-height row-count) "px") ; optional absolute scrollbar
                                       :padding-top (str clamped-scroll-top "px") ; seen elements are replaced with padding
                                       :padding-bottom (str (- max-height clamped-scroll-top) "px")}})
            (p/server
              ; seen elements are unmounted
              (p/for [x #_(subvec xs
                                  (Math/min start row-count)
                                  (Math/min (+ start page-size) row-count))
                      (->> xs (drop start) (take page-size))]
                (p/client (dom/div (dom/text x)))))))))))

(p/defn DemoVariableHeightInfinite
  "Scrolls like newsfeed. Natural browser layout for variable height rows. Leaves seen elements
  mounted in the DOM."
  []
  (let [xs (range) ; infinite
        page-size 100]
    (p/client
      (dom/div (dom/props {:class "viewport"})
        (let [!pages (atom 1) pages (p/watch !pages)
              [scrollTop scrollHeight clientHeight] (new (scroll-state< dom/node))]
          (when (>= scrollTop (- scrollHeight clientHeight
                                 clientHeight)) ; scrollThresholdPx = clientHeight
            (swap! !pages inc)) ; can this get spammed by photon?
          (dom/div ; content is unstyled, uses natural layout
            (p/server
              (p/for [x (->> xs (take (* pages page-size)))] ; leave dom
                (p/client (dom/div (dom/text x)))))))))))

(defonce !demo #?(:clj (atom {:text "DemoFixedHeightCounted" ::value `DemoFixedHeightCounted}) :cljs nil))
(p/def demo (p/server (p/watch !demo)))
(p/def demos {`DemoVariableHeightInfinite DemoVariableHeightInfinite
              `DemoFixedHeightCounted DemoFixedHeightCounted})
(p/defn Demo []
  (p/client
    ; Requires css {box-sizing: border-box;}
    (dom/element "style" (dom/text ".header { position: fixed; z-index:1; top: 0; left: 0; right: 0; height: 100px; background-color: #abcdef; }"
                                   ".footer { position: fixed; bottom: 0; left: 0; right: 0; height: 100px; background-color: #abcdef; }"
                                   ".viewport { position: fixed; top: 100px; bottom: 100px; left: 0; right: 0; background-color: #F63; overflow: auto; }"))
    (dom/div (dom/props {:class "header"})
      (dom/dl
        (dom/dt (dom/text "scroll debug state"))
        (dom/dd (dom/pre (dom/text (pr-str (update-keys (p/watch !scrollStateDebug) unqualify))))))
      (p/server
        (ui/select
          demo
          (p/fn V! [v] (reset! !demo v))
          (p/fn Options [] [{:text "DemoFixedHeightCounted" ::value `DemoFixedHeightCounted}
                            {:text "DemoVariableHeightInfinite" ::value `DemoVariableHeightInfinite}])
          (p/fn OptionLabel [x] (:text x)))))
    (p/server (new (get demos (::value demo))))
    (dom/div (dom/props {:class "footer"}) (dom/text "Try scrolling to the top, and resizing the window."))))
