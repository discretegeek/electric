(ns hyperfiddle.hfql.tree-to-grid-ui
  (:require [hyperfiddle.photon :as p]
            [hyperfiddle.api :as hf]
            [hyperfiddle.hfql :as hfql]
            [hyperfiddle.photon-dom :as dom1]
            [hyperfiddle.photon-dom2 :as dom]
            [hyperfiddle.spec :as spec]
            [clojure.datafy :refer [datafy]]
            [clojure.string :as str]
            ;; [contrib.ednish :as ednish]
            [contrib.color :as c]
            [contrib.data :as data]
            [hyperfiddle.photon-ui2 :as ui2]
            [hyperfiddle.photon-ui4 :as ui4]
            [hyperfiddle.scrollview :as sw]
            [hyperfiddle.rcf :refer [tests with % tap]]
            [missionary.core :as m]
            [hyperfiddle.router :as router])
  #?(:cljs (:require-macros [hyperfiddle.hfql.tree-to-grid-ui]))
  #?(:cljs (:refer-clojure :exclude [List])))

(defn attr-spec [attr]
  (cond
    (ident? attr) attr
    (seq? attr)   (attr-spec (first attr))))

(defn spec-value-type [attr] ; TODO extract spec for quoted sexpr ; TODO support args
  (when (qualified-ident? attr)
    (spec/type-of attr)))

(defn schema-value-type [schema-f db a]
  (let [attr (schema-f db a)]
    (spec/valueType->type (or (:db/valueType attr) (:hf/valueType attr))))) ; datascript rejects valueType other than ref.

(defn schema-cardinality [schema-f db a]
  (case (:db/cardinality (schema-f db a))
    :db.cardinality/one  ::hf/one
    :db.cardinality/many ::hf/many
    nil))

(defn spec-description [prefer-ret? attr]
  (when (qualified-ident? attr)
    (when-let [spec (datafy (spec/spec attr))]
      (if prefer-ret?
        (case (::spec/type spec)
          ::spec/fspec (::spec/ret spec)
          (::spec/description spec))
        (::spec/description spec)))))

(def input-type {:hyperfiddle.spec.type/symbol  "text"
                 :hyperfiddle.spec.type/uuid    "text"
                 :hyperfiddle.spec.type/uri     "text"
                 :hyperfiddle.spec.type/instant "datetime-local"
                 :hyperfiddle.spec.type/boolean "checkbox"
                 :hyperfiddle.spec.type/string  "text"
                 :hyperfiddle.spec.type/bigdec  "text"
                 :hyperfiddle.spec.type/keyword "text"
                 :hyperfiddle.spec.type/ref     "text"
                 :hyperfiddle.spec.type/float   "double"
                 :hyperfiddle.spec.type/double  "double"
                 :hyperfiddle.spec.type/long    "number"})

;; ----

(p/def table-picker-options {::group-id nil, ::current-value nil}),

(p/def grid-width 2) ; TODO infer from ctx

(p/def grid-row 1) ; TODO not called with new, don’t capitalize
(p/def grid-col 1)
(p/def indentation 0)
(p/def pagination-offset)

(defmacro cell [row col & body]
  `(dom/div
     (dom/props {::dom/role  "cell"
                  ::dom/style {:grid-column ~col
                                :grid-row    ~row}})
     ~@body))

(defn find-best-identity [v] ; TODO look up in schema
  (cond (map? v) (or (:db/ident v) (:db/id v))
        :else    v))

(p/defn Identity [x] x)

(defmacro input-props [readonly? grid-row grid-col dom-for]
  `(do
     (dom/props {::dom/role     "cell"
                  ::dom/disabled ~readonly?
                  ::dom/style    {:grid-row ~grid-row, :grid-column ~grid-col}})
     (when ~dom-for
       (dom/props {::dom/id ~dom-for}))))

(p/defn Input [{::hf/keys [tx Value] :as ctx}]
  (let [value-type (::value-type ctx)
        tx?        (some? tx)
        readonly?  (if-some [ro (find ctx ::readonly)] (val ro) (not tx?))
        v          (Value.)
        dom-for    (::dom/for ctx)]
    (p/client
      (let [type (input-type value-type "text")
            Tx   (when-not readonly? (p/fn [v] (p/server (hf/Transact!. (tx. ctx v)) nil)))]
        ;; TODO tests/demo for all branches in photon repo
        (case type
          "checkbox"       (ui4/checkbox v       Tx (input-props readonly? grid-row grid-col dom-for))
          "datetime-local" (ui4/date     v       Tx (input-props readonly? grid-row grid-col dom-for))
          "number"         (ui4/long     v       Tx (input-props readonly? grid-row grid-col dom-for))
          "double"         (ui4/double   v       Tx (input-props readonly? grid-row grid-col dom-for))
          #_else           (ui4/input    (str v) Tx (input-props readonly? grid-row grid-col dom-for)))))))

(defn ->picker-type [ctx]
  (cond (seq (::hf/options-arguments ctx)) ::typeahead
        :else                              ::select))

(p/defn Options [{::hf/keys [options continuation option-label tx] :as ctx}]
  (let [Options      (or options (::hf/options (::parent ctx)))
        option-label (or option-label (::hf/option-label (::parent ctx)) Identity)
        continuation (or continuation (::hf/continuation (::parent ctx)) Identity)
        tx           (or tx (::hf/tx (::parent ctx)))
        tx?          (some? tx)
        dom-props    (data/select-ns :hyperfiddle.photon-dom2 ctx)
        v            (find-best-identity (hfql/JoinAllTheTree. ctx))
        V!           (if tx? (p/fn [v] (tx. ctx v)) Identity)
        OptionLabel  (p/fn [id] (option-label. (hfql/JoinAllTheTree. (continuation. id))))]
    (case (->picker-type ctx)
      ::typeahead (ui4/typeahead v V! Options OptionLabel
                    (dom/props {:role     "cell"
                                :style    {:grid-row grid-row, :grid-column grid-col :overflow "visible"}
                                :disabled (not tx?)})
                    (dom/props dom-props))
      ::select    (ui4/select v V! Options OptionLabel
                    (dom/props {:role     "cell"
                                :style    {:grid-row grid-row, :grid-column grid-col :overflow "visible"}
                                :disabled (not tx?)})
                    (dom/props dom-props)))))

(p/defn Default [{::hf/keys [link link-label option-label] :as ctx}]
  (let [route        (when link (new link))
        value        (hfql/JoinAllTheTree. ctx)
        option-label (or option-label (::hf/option-label (::parent ctx)) Identity)
        value        (option-label. value)]
    (cond
      (some? route)      (p/client (cell grid-row grid-col (router/link route (dom/text value))))
      (::value-type ctx) (Input. ctx)
      :else              (p/client
                           (dom/pre (dom/text (pr-str value))
                             (dom/props {:role  "cell", :style {:grid-row grid-row, :grid-column grid-col}}))))))

(p/def Render)

#?(:cljs (def extract-borders
           (let [parse js/parseFloat]
             (juxt
               #(map parse (str/split (.-gridTemplateRows %) #"px\s"))
               #(map parse (str/split (.-gridTemplateColumns %) #"px\s"))
               #(parse (.-width %))
               #(parse (.-height %))
               #(parse (.-gap ^js %))
               #(.getPropertyValue % "--hf-cell-border-color")
               ))))

#?(:cljs (defn draw-lines! [node color width height gap rows columns]
           (let [xs  (reductions (partial + gap) columns)
                 ys  (reductions (partial + gap) rows)
                 ctx (and (.-getContext node) (.getContext node "2d"))]
             (when ctx
               (.clearRect ctx 0 0 width height)
               (set! (.-fillStyle ctx) color)
               (doseq [x xs] (.fillRect ctx (int x) 0 gap height))
               (doseq [y ys] (.fillRect ctx 0 (int y) width gap)))
             )))

;; This should not be see in userland because it’s an implementation detail
;; driven by Photon not supporting mutual recursion as of today.
(defmacro with-gridsheet-renderer [& body]
  `(p/server
     (binding [Table     Table-impl
               List      List-impl
               Form      Form-impl
               Render    Render-impl
               hf/Render Render-impl]
       (p/client ; FIXME don’t force body to run on the client
         (dom/div (dom/props {:class "hyperfiddle-gridsheet"}) ; FIXME drop the wrapper div
           (let [[rows# columns# width# height# gap# color#] (new ComputedStyle extract-borders hyperfiddle.photon-dom/node)
                 [scroll-top# scroll-height# client-height#] (new (sw/scroll-state< hyperfiddle.photon-dom/node))
                 height#                                     (if (zero? scroll-height#) height# scroll-height#)]
             (dom/canvas (dom/props {:class  "hf-grid-overlay"
                                       :width  (str width# "px")
                                       :height (str height# "px")})
               (draw-lines! hyperfiddle.photon-dom/node color# width# height# gap# rows# columns#)))
           ~@body)))))

;; TODO adapt to new HFQL macroexpansion
(p/defn Render-impl [{::hf/keys [type cardinality render Value options] :as ctx}]
  (cond render  (p/client (cell grid-row grid-col (p/server (render. ctx))))
        options (Options. ctx)
        :else   (case type
                  ::hf/leaf (SpecDispatch. ctx)
                  ::hf/keys (Form. ctx)
                  (case cardinality
                    ::hf/many (Table. ctx)
                    (let [v (Value.)]
                      (cond
                        (vector? v) (Table. ctx)
                        (map? v)    (Render. (assoc v ::parent ctx))
                        :else       (throw "unreachable" {:v v})))))))

(defn height
  ([ctx] (height ctx (::value ctx)))
  ([{::hf/keys [height arguments keys] :as ctx} value]
   (let [argc (count arguments)]
     (+ argc
       (cond
         ;; user provided, static height
         (some? height)                        height
         ;; transposed form (table)
         (and keys (pos-int? (::count ctx)))   (+ 1 ; table header
                                                 (max (::count ctx) 1) ; rows
                                                 (if (pos? argc) 1 0)) ; args pushes table to next row
         ;; leaf
         (or (set? value) (sequential? value)) (count value)
         ;; static form
         (some? keys)                          (+ 1 (count keys)) ; form labels on next row
         :else                                 (max (::count ctx) 1))))))

(p/def List)

(p/defn List-impl [{::hf/keys [height attribute] :as ctx}]
  (let [ctxs (p/for [v (hfql/JoinAllTheTree. ctx)]
               {::hf/type        ::hf/keys
                ::hf/keys        [attribute]
                ::hf/cardinality ::hf/one
                ::hf/values      [{::hf/type        ::hf/leaf
                                   ::hf/attribute   attribute
                                   ::hf/cardinality ::hf/one
                                   ::hf/Value       (p/fn [] v)}]})
        cnt  (count ctxs)]
    (Table.
      (-> ctx
        (dissoc ::hf/type)
        (merge
          {::hf/cardinality ::hf/many
           ::hf/height      height
           ::hf/count       (p/fn [] cnt)
           ::count          cnt
           ::hf/keys        [attribute]
           ::list?          true
           ::hf/Value       (p/fn [] (new hf/Paginate ctxs))})))))


(p/defn SpecDispatch [{::hf/keys [attribute cardinality] :as ctx}]
  (let [spec-value-type   (spec-value-type attribute)
        schema-value-type (schema-value-type hf/*schema* hf/db attribute)
        defined-by-spec?  (and spec-value-type (not schema-value-type))
        value-type        (or spec-value-type schema-value-type)
        cardinality       (or cardinality (schema-cardinality hf/*schema* hf/db attribute))]
    (case cardinality
      ::hf/many (List. ctx)
      (case value-type
        (:hyperfiddle.spec.type/string
         :hyperfiddle.spec.type/instant
         :hyperfiddle.spec.type/boolean) (Default. (cond-> (assoc ctx ::value-type value-type)
                                                     defined-by-spec? (assoc ::readonly true)))
        (Default. ctx)))))

(defn non-breaking-padder [n] (apply str (repeat n " ")) )

(defn field-name [attr]
  (if (seq? attr)
    (cons (symbol (field-name (first attr))) (seq (::spec/keys (clojure.datafy/datafy (spec/args (first attr))))) )
    (name attr)))

(p/defn GrayInput [label? spec props [name {:keys [::hf/read ::hf/path ::hf/options ::hf/option-label ::hf/readonly] :as arg}]]
  (let [value    (read.)
        options? (some? options)]
    (p/client
      (let [id       (random-uuid)
            list-id  (random-uuid)
            arg-spec (spec/arg spec name)]
        (when label?
          (dom/label
            (dom/props {::dom/role  "cell"
                         ::dom/class "label"
                         ::dom/for   id,
                         ::dom/title (pr-str (:hyperfiddle.spec/form arg-spec))
                         ::dom/style {:grid-row    grid-row
                                       :grid-column grid-col
                                       :color       :gray}})
            (dom/text (str (non-breaking-padder indentation) (field-name  name)))))
        (if options?
          ;; FIXME Call Options
          (p/server (ui4/select value (p/fn [v] (p/client (router/swap-route! assoc-in path v)))
                      options
                      (or option-label Identity)
                      (dom/props {:id list-id
                                  :role     "cell"
                                  :style    {:grid-row grid-row, :grid-column (inc grid-col)}
                                  :disabled readonly})))
          (case (spec/type-of spec name)
            ;; "checkbox" ()
            #_else (ui4/input value (p/fn [v] (router/swap-route! assoc-in path v) nil)
                     (dom/props {::dom/id    id
                                 ::dom/role  "cell"
                                 ::dom/style {:grid-row grid-row, :grid-column (inc grid-col)}})
                     (when (seq props) (dom/props props))
                     (when options? (dom/props {::dom/list list-id})))))
        value))))

(defn apply-1 [n F args]
  (let [syms (vec (repeatedly n gensym))]
    `(let [~syms ~args]
       (new ~F ~@syms))))

(defmacro applier [n F args]
  (let [Fsym     (gensym "f")
        args-sym (gensym "args")
        cases    (mapcat (fn [n] [n (apply-1 n Fsym args-sym)]) (rest (range (inc n))))]
    `(let [~Fsym     ~F
           ~args-sym ~args
           n#        (count ~args-sym)]
       (case n#
         0 (new ~Fsym)
         ~@cases
         (throw (ex-info (str "Apply is defined for up to 20 args, given " n# ".") {}))))))

(p/defn Apply [F args] (applier 20 F args))

(tests
  (p/defn Plus [a b c] (+ a b c))
  (with (p/run (tap (Apply. Plus [1 2 3]))))
  % := 6)

(p/defn GrayInputs [{::hf/keys [tx attribute arguments] :as ctx}]
  (when-some [arguments (seq arguments)]
    (let [spec (attr-spec attribute)
          args (p/for-by second [[idx arg] (map-indexed vector arguments)]
                 (p/client
                   (binding [grid-row (+ grid-row idx)]
                     (p/server
                       (GrayInput. true spec nil arg)))))]
      (when (some? tx)
        (Apply. tx args)))))

(p/def Form)

(p/defn Form-impl [{::hf/keys [keys values] :as ctx}]
  (let [values (p/for [ctx values]
                 (assoc ctx ::count (new (::hf/count ctx (p/fn [] 0)))))]
    (p/client
      (dom/form
        (dom/event "submit" (fn [e] (.preventDefault e))) ; an HFQL form is semantic only, it is never submitted
        (dom/props {::dom/role  "form"
                     ::dom/style {:border-left-color (c/color hf/db-name)}})
        (p/server
          (let [heights (vec (reductions + 0 (map height values)))]
            (into [] cat
              (p/for-by (comp first second) [[idx [key ctx]] (map-indexed vector (partition 2 (interleave keys values)))]
                (let [leaf? (= ::hf/leaf (::hf/type ctx))
                      argc  (count (::hf/arguments ctx))
                      h     (get heights idx)]
                  (p/client
                    (let [row     (+ grid-row idx (- h idx))
                          dom-for (random-uuid)]
                      (dom/label
                        (dom/props
                          {::dom/role  "cell"
                           ::dom/class "label"
                           ::dom/for   dom-for
                           ::dom/style {:grid-row         row
                                         :grid-column      grid-col
                                         #_#_:padding-left (str indentation "rem")}
                           ::dom/title (pr-str (or (spec-description false (attr-spec key))
                                                  (p/server (schema-value-type hf/*schema* hf/db key))))})
                        (dom/text (str (non-breaking-padder indentation) (field-name key))))
                      (into [] cat
                        [(binding [grid-row    (inc row)
                                   indentation (inc indentation)]
                           (p/server (GrayInputs. ctx)))
                         (binding [grid-row    (cond leaf?       row
                                                     (pos? argc) (+ row (inc argc))
                                                     :else       (inc row))
                                   grid-col    (if leaf? (inc grid-col) grid-col)
                                   indentation (if leaf? indentation (inc indentation))]
                           (p/server
                             (let [ctx (assoc ctx ::dom/for dom-for)]
                               (Render. (assoc ctx ::dom/for dom-for ::parent-argc argc)))))])
                      )))))))))))

(p/defn Row [{::hf/keys [keys values] :as ctx}]
  (p/client
    (dom/tr
      (when-let [id (::group-id table-picker-options)]
        (let [value (p/server (hfql/JoinAllTheTree. ctx))]
          (ui4/checkbox (= (::current-value table-picker-options) value) (p/fn [_])
            (dom/props {::dom/role "cell", ::dom/name id, ::dom/style {:grid-row grid-row, :grid-column grid-col}}))))
      (p/server
        (into [] cat
          (p/for-by second [[idx ctx] (map-indexed vector values)]
            (p/client
              (binding [grid-col (+ grid-col idx)]
                (dom/td (p/server (binding [Form  Default
                                            Table Default
                                            List Default]
                                    (Render. ctx))))))))))))

(p/def default-height 10)

(defn clamp [lower-bound upper-bound number] (max lower-bound (min number upper-bound)))

(defn give-card-n-contexts-a-unique-key [offset ctxs]
  (let [offset (max offset 0)]
    (into [] (map-indexed (fn [idx ctx] (assoc ctx ::key (+ offset idx)))) ctxs)))

(p/def Table)
(p/defn Table-impl [{::hf/keys [keys height Value] :as ctx}]
  (let [actual-height (new (::hf/count ctx (p/fn [] 0)))
        height        (clamp 1 (or height default-height) actual-height)
        list?         (::list? ctx)
        nested?       (and (some? (::dom/for ctx)) (not list?))
        shifted?      (or list? (and (::parent-argc ctx) (zero? (::parent-argc ctx))))]
    (p/client
      (binding [grid-col (if nested? (inc grid-col) grid-col)
                grid-row (if (or shifted? list?) (dec grid-row) grid-row)]
        (paginated-grid (count keys) height actual-height
          (dom/table
            (dom/props {::dom/role "table"})
            (when-not list?
              (dom/thead
                (dom/tr
                  (when (::group-id table-picker-options)
                    (dom/th (dom/props {::dom/role  "cell"
                                        ::dom/style {:grid-row grid-row, :grid-column grid-col}})))
                  (p/for-by second [[idx col] (map-indexed vector keys)]
                    (dom/th (dom/props {::dom/role  "cell"
                                        ::dom/class "label"
                                        ::dom/title (pr-str (or (spec-description true (attr-spec col))
                                                              (p/server (schema-value-type hf/*schema* hf/db col)))),
                                        ::dom/style {:grid-row    grid-row,
                                                     :grid-column (+ grid-col idx)
                                                     :color       (c/color hf/db-name)}})
                      (dom/text (field-name col)))))))
            (dom/tbody
              (p/server
                (let [value (give-card-n-contexts-a-unique-key hf/page-drop (Value.))]
                  (into [] cat
                    (p/for-by (comp ::key second) [[idx ctx] (map-indexed vector value)]
                      (p/client (binding [grid-row (+ grid-row idx 1)]
                                  (p/server (Row. ctx)))))))))))))))

(defn compute-offset [scroll-top row-height]
  #?(:cljs (max 0 (js/Math.ceil (/ (js/Math.floor scroll-top) row-height)))))


#?(:cljs (defn set-css-var! [^js node key value]
           (.setProperty (.-style node) key value)))

(defmacro paginated-grid [actual-width max-height actual-height & body]
  `(let [row-height#    (or (js/parseFloat (ComputedStyle. #(.-gridAutoRows %) (.closest hyperfiddle.photon-dom/node ".hyperfiddle-gridsheet"))) 0)
         actual-height# (* row-height# ~actual-height)
         !scroller#     (atom nil)
         !scroll-top#   (atom 0)]
     (dom/div
       (dom/props {::dom/role  "scrollbar"
                    ::dom/style {:grid-row-start (inc grid-row)
                                  :grid-row-end   (+ (inc grid-row) ~max-height)
                                  :grid-column    (+ grid-col ~actual-width)}})
       (do (reset! !scroller# hyperfiddle.photon-dom/node)
           (let [[scroll-top#] (new (sw/scroll-state< hyperfiddle.photon-dom/node))]
             (reset! !scroll-top# scroll-top#))
           nil)
       (dom/div (dom/props {::dom/role "filler" "data-height" actual-height# ::dom/style {:height (str actual-height# "px")}})))

     (dom/div (dom/props {::dom/role "scrollview"})
       (dom/event "wheel" ; TODO support keyboard nav and touchscreens
         (fn [e#] (let [scroller# @!scroller#]
                    (set! (.. scroller# -scrollTop) (+ (.. scroller# -scrollTop) (.. e# -deltaY))))))
       (let [offset# (compute-offset (p/watch !scroll-top#) row-height#)]
         (p/server
           (binding [hf/page-drop offset#
                     hf/page-take ~max-height]
             (p/client
               ~@body)))))))

(defn throttle [dur >in]
  (m/ap
    (let [x (m/?> (m/relieve {} >in))]
      (m/amb x (do (m/? (m/sleep dur)) (m/amb))))))

(defn get-computed-style [node] #?(:cljs (js/getComputedStyle node)))

(p/defn ComputedStyle
  "Calls the `keyfn` clojure function, passing it the given DOM node’s
  CSSStyleDeclaration instance. `keyfn` is meant to extract a property from the
  live computed style object."
  ;; Does not return CSSStyleDeclaration directly because a CSSStyleDeclaration
  ;; is a live object with a stable identity. m/cp would dedupe it even if
  ;; properties might have changed.
  [keyfn node]
  (let [live-object (get-computed-style node)]
    ;; HACK clock is throttled because network is not deduping. A message would
    ;; be sent on each RAF.
    (->> (m/sample (partial keyfn live-object) dom/<clock)
      (throttle 1000)
      (m/reductions {} (keyfn live-object))
      (m/relieve {})
      (new))
    ;; FIXME use this impl once network dedupes
    #_((fn [_time] (keyfn live-object)) hyperfiddle.photon-dom/system-time-ms)
    ))
