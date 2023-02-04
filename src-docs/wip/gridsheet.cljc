(ns wip.gridsheet
  "todo deprecate, use HFQL grid"
  #?(:cljs (:require-macros wip.gridsheet))
  #?(:clj (:import [clojure.lang ExceptionInfo]))
  (:require clojure.math
            [contrib.data :refer [unqualify auto-props round-floor]]
            [clojure.spec.alpha :as s]
            [hyperfiddle.photon :as p]
            [hyperfiddle.photon-dom2 :as dom]
            #?(:cljs [hyperfiddle.scrollview :as scrollview :refer [scroll-state<]])
            [hyperfiddle.rcf :refer [tests]]
            #?(:cljs goog.object)))

(p/def Format (p/server (p/fn [m a] (pr-str (a m)))))

(defmacro check
  ([v] `(check some? ~v))
  ([pred v]
   `(let [pred# ~pred, v# ~v]
      (when-not (pred# v#) (throw (ex-info (str "check failed: (" (pr-str '~pred) " " (pr-str '~v) ") for " (pr-str v#)) {})))
      v#)))

(tests
  (check nil) :throws ExceptionInfo
  (check odd? 2) :throws ExceptionInfo
  (check odd? 1) := 1)

(p/defn GridSheet [xs props]
  (let [props (auto-props props {::dom/class "hyperfiddle-gridsheet"})
        {:keys [::columns
                ::grid-template-columns
                ::row-height ; px, same unit as scrollTop
                ::page-size #_ "tight"]} props
        client-height (* (inc (check number? page-size)) (check number? row-height))
        rows (seq xs)
        row-count (count rows)]
    (assert columns)
    (p/client
      (dom/link (dom/props {:rel :stylesheet, :href "gridsheet.css"}))
      (dom/div (dom/props {:role "grid"
                           :class (::dom/class props)
                           :style (merge (::dom/style props)
                                         {:height (str client-height "px")
                                          :display "grid" :overflowY "auto"
                                          :grid-template-columns (or (::grid-template-columns props)
                                                                     (->> (repeat (p/server (count columns)) "1fr")
                                                                          (interpose " ") (apply str)))})})
        (let [[scroll-top scroll-height client-height'] (new (scrollview/scroll-state< dom/node))
              max-height (* row-count row-height)
              padding-bottom (js/Math.max (- max-height client-height) 0)

              ; don't scroll past the end
              clamped-scroll-top (js/Math.min scroll-top padding-bottom)

              start-row (clojure.math/ceil (/ clamped-scroll-top row-height))

              ; batch pagination to improve latency
              ; (does reducing network even help or just making loads happen offscreen?)
              ; clamp start to the nearest page
              start-row-page-aligned (round-floor start-row page-size)]
          #_(println [:scrollTop scroll-top :scrollHeight scroll-height :clientHeight client-height
                    :padding-bottom padding-bottom
                    :start-row start-row :start-row-page-aligned start-row-page-aligned
                    :take page-size :max-height max-height])

          (p/for [k columns]
            (dom/div (dom/props {:role "columnheader"
                                 :style {:position "sticky" #_"fixed" :top (str 0 "px")
                                         :background-color "rgb(248 250 252)" :box-shadow "0 1px gray"}})
              (dom/text (name k))))

          ; userland could format the row, no need
          ; for grid to be aware of columns, it's just vertical scroll.
          ; horizontal scroll changes things.
          ; except for the tricky styles ...
          (p/server
            (when (seq rows) (check vector? (first rows)))
            (let [xs (vec (->> rows (drop start-row) (take page-size)))]
              (p/for [i (range page-size)]
                (let [[depth m] (get xs i [0 ::empty])]
                  (p/client
                    (dom/div (dom/props {:role "group" :style {:display "contents"
                                                               :grid-row (inc i)}})
                      (dom/div (dom/props {:role "gridcell"
                                           :style {:padding-left (-> depth (* 15) (str "px"))
                                                   :position "sticky" :top (str (* row-height (inc i)) "px")
                                                   :height (str row-height "px")}})
                        (dom/text (p/server (case m ::empty nil (Format. m (first columns))))))
                      (p/for [a (rest columns)]
                        (dom/div (dom/props {:role "gridcell"
                                             :style {:position "sticky" :top (str (* row-height (inc i)) "px")
                                                     :height (str row-height "px")}})
                          (dom/text (p/server (case m ::empty nil (Format. m a))))))))))))
          (dom/div (dom/props {:style {:padding-bottom (str padding-bottom "px")}})))) ; scrollbar
      (dom/div (dom/text (pr-str {:count row-count}))))))

(p/defn ^:deprecated TableSheet
  "Perhaps useful to keep around? Prefer the css-grid sheet
  This does not align column headers with body columns due to position:sticky layout"
  [xs props]
  (let [{:keys [::columns
                ::row-height ; px, same unit as scrollTop
                ::page-size]} ; tight
        (auto-props props)
        rows (seq xs)
        row-count (count rows)]
    (p/client
      (dom/table (dom/props {:style {:display "block" :overflowY "auto"
                                     :height "500px"}}) ; fixme
        (let [[scrollTop scrollHeight clientHeight] (new (scrollview/scroll-state< dom/node))
              max-height (* row-count row-height)

              ; don't scroll past the end
              clamped-scroll-top (js/Math.min scrollTop (- max-height clientHeight))

              start-row (clojure.math/ceil (/ clamped-scroll-top row-height))

              ; batch pagination to improve latency
              ; (does reducing network even help or just making loads happen offscreen?)
              ; clamp start to the nearest page
              start-row-page-aligned (round-floor start-row page-size)

              ;padding-top (* start-row-page-aligned row-height)
              ;padding-bottom (- max-height padding-top clientHeight)
              padding-top #_clamped-scroll-top (* start-row row-height)
              padding-bottom (- max-height padding-top clientHeight)]
          #_(println [:scrollTop scrollTop :scrollHeight scrollHeight :clientHeight clientHeight
                      :start-record start-row-page-aligned :start-row start-row :take page-size
                      ; padding  not needed if max-height is set
                      :max-height max-height
                      :padding-top padding-top :padding-bottom padding-bottom])

          (dom/thead
            (dom/props {:style {:position "sticky" #_"fixed" :top "0"
                                ; :position breaks column layout - detaches thead from tbody layout
                                :background-color "rgb(248 250 252)" :box-shadow "0 1px gray"}})
            (p/for [k columns]
              (dom/td (dom/text (name k)))))

          (dom/tbody
            (dom/props {:style {:height (str max-height "px")}})
            (let [!!rows (vec (repeatedly page-size (partial atom nil)))]
              #_(dom/div (dom/props {:style {:padding-top (str padding-top "px")}}))
              (p/for [i (range page-size)]
                (dom/tr
                  (dom/props {:style {:position "fixed"
                                      :margin-top (str (* row-height i) "px")
                                      :height (str row-height "px")}}) ; freeze layout
                  (let [[a & as] columns
                        [depth ?Render] (p/watch (get !!rows i))]
                    (dom/td
                      (dom/props {:style {:padding-left (-> depth (* 15) (str "px"))}})
                      (dom/text (some-> ?Render (new a)))) ; added text node, is it needed?
                    (p/for [a as]
                      (dom/td (dom/text (some-> ?Render (new a)))))))) ; todo added text node is it needed?
              #_(dom/div (dom/props {:style {:padding-bottom (str padding-bottom "px")}}))

              (p/server
                (let [xs (->> rows (drop start-row) (take page-size))]
                  (p/for-by first [[i [depth m]] (map vector (range) xs)]
                    (p/client
                      (reset! (get-in !!rows [i])
                              [depth (p/fn [a] (p/server (Format. m a)))])))))))))
      (dom/div (pr-str {:count row-count})))))

; How to do transactionally with a fragment to avoid the churn? (for variable infinite seq)

(p/defn RenderTableInfinite [xs props]
  (let [{:keys [::columns
                ::row-height ; px, same unit as scrollTop
                ::page-size]} ; you want this loose, like 100
        (auto-props props {::page-size 100})
        !pages (atom 1) pages (p/watch !pages)]
    (p/client
      (dom/table
        (dom/props {:style {:display "block" :overflow "hidden auto"
                            :height "500px"}})
        (let [[scrollTop scrollHeight clientHeight] (new (scrollview/scroll-state< dom/node))]
          (when (> scrollTop (- scrollHeight clientHeight clientHeight)) ; scrollLoadThreshold = clientHeight
            (p/server (swap! !pages inc))))
        (dom/thead
          (dom/props {:style {:position "sticky" :top "0" :background-color "rgb(248 250 252)"
                              :box-shadow "0 1px gray"}})
          (p/for [k columns]
            (dom/td (dom/text (name k)))))
        (dom/tbody
          (p/server
            (if-let [rows (take (* pages page-size) (seq xs))]
              (p/for [[depth m] rows]
                (let [[a & as] columns]
                  (p/client
                    (dom/tr
                      (dom/td
                        (dom/props {:style {:padding-left (-> depth (* 15) (str "px"))}})
                        (dom/text (p/server (Format. m a)))) ; todo check extra text node
                      (p/server
                        (p/for [a as]
                          (p/client (dom/td (dom/text (p/server (Format. m a))))))))))) ; todo check extra text node
              (p/client
                (dom/div
                  (dom/props {:class "no-results"})
                  (dom/text "no results matched"))))))))))
