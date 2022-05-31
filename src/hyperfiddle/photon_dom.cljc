(ns hyperfiddle.photon-dom
  (:refer-clojure :exclude [time for])
  (:require [hyperfiddle.photon :as p]
            [hyperfiddle.zero :as z]
            [missionary.core :as m]
            #?(:cljs [goog.dom :as d])
            #?(:cljs [goog.events :as e])
            #?(:cljs [goog.object :as o])
            #?(:cljs [goog.style])
            [clojure.string :as str])
  #?(:cljs (:require-macros [hyperfiddle.photon-dom :refer [with oget]]))
  #?(:cljs (:import (goog.ui KeyboardShortcutHandler)
                    (goog.ui.KeyboardShortcutHandler EventType))))

(p/def node nil)

(defn by-id [id] #?(:cljs (js/document.getElementById id)))

(defn unsupported [& _]
  (throw (ex-info (str "Not available on this peer.") {})))

(def hook
  #?(:clj  unsupported
     :cljs (fn ([x] (.removeChild (.-parentNode x) x))
             ([x y] (.insertBefore (.-parentNode x) x y)))))

(defmacro with [n & body]
  `(binding [node ~n]
     (new (p/hook hook node
            (p/fn [] ~@body)))))

(defn dom-element [parent type]
  #?(:cljs (let [node (d/createElement type)]
             (.appendChild parent node) node)))

(defmacro element [t & [props & body]]
  `(with (dom-element node ~(name t)) ~@(if (map? props)
                                          (cons `(props ~props) body)
                                          (cons props body))))

(defn by-id [id] #?(:cljs (js/document.getElementById id)))

(defn text-node [parent]
  #?(:cljs (let [node (d/createTextNode "")]
             (.appendChild parent node) node)))

(defn set-text-content! [e t] #?(:cljs (d/setTextContent e (str t))))

(defmacro text [& strs]
  `(with (text-node node) (set-text-content! node (str ~@strs))))

(defn clean-props [props]
  (cond-> props
    (contains? props :class) (update :class #(if (vector? %) (str/join " " %) %))))

(defn set-properties! [e m] #?(:cljs (let [styles (:style m)
                                           props  (dissoc m :style)]
                                       (when (seq styles) (goog.style/setStyle e (clj->js styles)))
                                       (when (seq props) (d/setProperties e (clj->js (clean-props props)))))))

(defmacro props [m] `(set-properties! node ~m))

(defn >events* [node event-type & [xform init rf :as args]]
  #?(:cljs (let [event-type (if (coll? event-type) (to-array event-type) event-type)
                 init?      (>= (count args) 2)]
             (cond->> (m/observe (fn [!] (e/listen node event-type !) #(e/unlisten node event-type !)))
               xform  (m/eduction xform)
               init?  (m/reductions (or rf {}) init)))))

(defmacro >events
  "Produce a discreet flow of dom events of `event-type` on the current dom node.
  `event-type` can be a string naming the event or a set of event names.
  If provided:
  - `xform` will be applied as an eduction,
  - `init`  will be the initial value of the flow,
  - `rf`    will be used as a reducing function over the flow, defaulting to `{}`.
  Also:
  - if `init` is not provided, the flow will not have an initial value,
  - `rf` is applied only if `init` is provided,
  - if `xform`, `init` and `rf` are provided, they form a transduction."
  [event-type & [xform init rf :as args]]
  (list* `>events* `node event-type args))

(defn >keychord-events* [node keychord & [xform init rf :as args]]
  (assert (or (string? keychord) (coll? keychord)))
  #?(:cljs (let [init? (>= (count args) 2)]
             (cond->> (m/observe (fn [!]
                                   (let [^js handler (new KeyboardShortcutHandler node)]
                                     (doseq [chord (if (string? keychord) #{keychord} keychord)]
                                       (.registerShortcut handler chord chord))
                                     ;; FIXME, desired behavior is `setModifierShortcutsAreGlobal` but it
                                     ;; doesn’t work with codemirror
                                     (.setAllShortcutsAreGlobal handler true)
                                     (e/listen handler "shortcut" !)
                                     #(e/unlisten handler "shortcut" !))))
               xform (m/eduction xform)
               init? (m/reductions (or rf {}) init)))))

(defmacro >keychord-events
  "Produce a discreet flow of key combo events. `keychord` is a string or a set of
  strings describing keychords, which looks like:
  - `\"a\"`
  - `\"t e s t\"`
  - `\"shift+f12\"`
  - `\"shift+f11 c\"`
  - `\"meta+y\"`
  @see https://github.com/google/closure-library/blob/master/closure/goog/demos/keyboardshortcuts.html
  "
  ;; TODO This implementation ignores focused form elements. For instance,
  ;; pressing SPACE on a button simulates a click and should not be tampered
  ;; with for accessibility reasons. In the meantime, please register keychords
  ;; at the right place in the dom tree to avoid event bubbling messing with
  ;; accessibility.
  [keychord & [xform init rf :as args]]
  (list* `>keychord-events* `node keychord args))

(defmacro events
  "Return a transduction of events as a continuous flow. See `clojure.core/transduce`.\n
   `event-type` can be a string like `\"click\"`, or a set of strings.

   ```clojure
   ;; count clicks
   (new (events \"click\" (map (constantly 1)) 0 +))

   ;; track focus state
   (new (events #{\"focus\" \"blur\"}
        (comp (map event-type) (map {\"focus\" true, \"blur\" false}))
        false))
   ```"
  ([event-type]                    `(new (m/relieve {} (>events* node ~event-type nil    nil   nil))))
  ([event-type xform]              `(new (m/relieve {} (>events* node ~event-type ~xform nil   nil))))
  ([event-type xform init]         `(new (m/relieve {} (>events* node ~event-type ~xform ~init nil))))
  ([event-type xform init rf]      `(new (m/relieve {} (>events* node ~event-type ~xform ~init ~rf))))
  ([node event-type xform init rf] `(new (m/relieve {} (>events* ~node  ~event-type ~xform ~init ~rf)))))

(defn- flip [f] (fn [& args] (apply f (reverse args))))

(defn oget* [obj ks]
  #?(:clj  (get-in obj ks)
     :cljs (apply o/getValueByKeys obj (clj->js ks))))

(defn- path-ident? [x] (or (string? x) (simple-keyword? x)))

(defmacro oget
  "Like `aget`, but for js objects.
  Can be used in two ways:
  - direct call:         `(oget js-obj \"k1\" \"k2\" …)`
  - partial application: `(oget \"k1\" \"k2\" …)`"
  ;; TODO instead of calling `oget*`, expands to direct field access
  ;;      e.g.: obj["k1"]["k2"] -> obj.k1.k2
  [& args]
  (if (path-ident? (first args))
    (do (assert (every? path-ident? args))
        `(partial (flip oget*) [~@args]))
    (do (assert (every? path-ident? (rest args)))
        `(oget* ~(last args) ~@(butlast args)))))

(defn oset!* [obj path val]
  #?(:clj  (assoc-in obj path val)
     :cljs (do (if (= 1 (count path))
                 (o/set obj (clj->js (first path)) val)
                 (oset!* (oget* obj (butlast path)) [(last path)] val))
               obj)))

(defmacro oset! [& args]
  (if (path-ident? (first args))
    (do (assert (every? path-ident? (butlast args)))
        `(partial (flip oset!) ~(last args) [~@(butlast args)]))
    (do (assert (every? path-ident? (butlast (rest args))))
        `(oset!* ~(first args) [~@(butlast (rest args))] ~(last args)))))

(defn stop-event! [event]
  (.preventDefault event)
  (.stopPropagation event)
  event)

(defn focus-state [e]
  (>events* e #{"focus" "blur"}
      (comp (map (oget :type))
            (map {"focus" true, "blur" false}))
    false))

#?(:cljs
   (deftype Clock [Hz
                   ^:mutable ^number raf
                   ^:mutable callback
                   terminator]
     IFn
     (-invoke [_]
       (if (zero? raf)
         (set! callback nil)
         (do (if (zero? Hz)
               (.cancelAnimationFrame js/window raf)
               (.clearTimeout js/window raf))
           (terminator))))
     IDeref
     (-deref [_]
       (if (nil? callback)
         (terminator)
         (if (zero? Hz)
           (set! raf (.requestAnimationFrame js/window callback))
           (set! raf (.setTimeout js/window callback (/ 1000 Hz)))))
       (.now js/Date))))

(defn ^:no-doc clock* [Hz]
  #?(:cljs
     (fn [n t]
       (let [c (->Clock Hz 0 nil t)]
         (set! (.-callback c)
           (fn [_] (set! (.-raf c) 0) (n)))
         (n) c))))

(p/defn clock
  "The number of milliseconds elapsed since January 1, 1970, with custom `Hz` frequency.
  If `Hz` is 0, sample at the browser Animation Frame speed."
  [Hz] (new (clock* Hz)))

(defn pack-string-litterals [xs]
  (loop [r        []
         [x & xs] xs]
    (cond
      (empty? xs) (conj r x)
      (and (string? x) (string? (first xs))) (recur (conj r (cons `str (cons x (take-while string? xs))))
                                               (drop-while string? xs))
      :else (recur (conj r x) xs))))

(defn- new-invoke? [x]
  (and (symbol? x) (= \. (last (name x)))))

(defn hiccup* [form]
  (cond
    (vector? form)  (cond
                      (new-invoke? (first form)) (cons (first form) (map hiccup* (rest form)))
                      (keyword? (first form))    (let [[tag & content]   form
                                                       [props & content] (pack-string-litterals content)
                                                       body              (cond (map? props) (cons props (map hiccup* content))
                                                                               (nil? props) (map hiccup* content)
                                                                               :else        (map hiccup* (cons props content)))]
                                                   (cond
                                                     (= :text tag) `(text ~@body)
                                                     :else         (list* `element (name tag) body)))
                      :else                      (mapv hiccup* form))
    (keyword? form) `(text ~(name form))
    (seq? form)     form
    :else           `(text ~form)))

(defmacro hiccup [form] (hiccup* form))

(defmacro a [& body] `(element :a ~@body))
(defmacro abbr [& body] `(element :abbr ~@body))
(defmacro address [& body] `(element :address ~@body))
(defmacro area [& body] `(element :area ~@body))
(defmacro article [& body] `(element :article ~@body))
(defmacro aside [& body] `(element :aside ~@body))
(defmacro audio [& body] `(element :audio ~@body))
(defmacro b [& body] `(element :b ~@body))
(defmacro bdi [& body] `(element :bdi ~@body))
(defmacro bdo [& body] `(element :bdo ~@body))
(defmacro blockquote [& body] `(element :blockquote ~@body))
(defmacro br [& body] `(element :br ~@body))
(defmacro button [& body] `(element :button ~@body))
(defmacro canvas [& body] `(element :canvas ~@body))
(defmacro cite [& body] `(element :cite ~@body))
(defmacro code [& body] `(element :code ~@body))
(defmacro data [& body] `(element :data ~@body))
(defmacro datalist [& body] `(element :datalist ~@body))
(defmacro del [& body] `(element :del ~@body))
(defmacro details [& body] `(element :details ~@body))
(defmacro dfn [& body] `(element :dfn ~@body))
(defmacro dialog [& body] `(element :dialog ~@body))
(defmacro div [& body] `(element :div ~@body))
(defmacro dl "The <dl> HTML element represents a description list. The element encloses a list of groups of terms (specified using the <dt> element) and descriptions (provided by <dd> elements). Common uses for this element are to implement a glossary or to display metadata (a list of key-value pairs)." [& body] `(element :dl ~@body))
(defmacro dt "The <dt> HTML element specifies a term in a description or definition list, and as such must be used inside a <dl> element. It is usually followed by a <dd> element; however, multiple <dt> elements in a row indicate several terms that are all defined by the immediate next <dd> element." [& body] `(element :dt ~@body))
(defmacro dd "The <dd> HTML element provides the description, definition, or value for the preceding term (<dt>) in a description list (<dl>)." [& body] `(element :dd ~@body))
(defmacro em [& body] `(element :em ~@body))
(defmacro embed [& body] `(element :embed ~@body))
(defmacro fieldset [& body] `(element :fieldset ~@body))
(defmacro figure [& body] `(element :figure ~@body))
(defmacro footer [& body] `(element :footer ~@body))
(defmacro form [& body] `(element :form ~@body))
(defmacro h1 [& body] `(element :h1 ~@body))
(defmacro h2 [& body] `(element :h2 ~@body))
(defmacro h3 [& body] `(element :h3 ~@body))
(defmacro h4 [& body] `(element :h4 ~@body))
(defmacro h5 [& body] `(element :h5 ~@body))
(defmacro h6 [& body] `(element :h6 ~@body))
(defmacro header [& body] `(element :header ~@body))
(defmacro hgroup [& body] `(element :hgroup ~@body))
(defmacro hr [& body] `(element :hr ~@body))
(defmacro i [& body] `(element :i ~@body))
(defmacro iframe [& body] `(element :iframe ~@body))
(defmacro img [& body] `(element :img ~@body))
(defmacro input [& body] `(element :input ~@body))
(defmacro ins [& body] `(element :ins ~@body))
(defmacro kbd [& body] `(element :kbd ~@body))
(defmacro label [& body] `(element :label ~@body))
(defmacro legend [& body] `(element :legend ~@body))
(defmacro li [& body] `(element :li ~@body))
(defmacro link [& body] `(element :link ~@body))
(defmacro main [& body] `(element :main ~@body))
#_(defmacro map [& body] `(element :map ~@body))
(defmacro mark [& body] `(element :mark ~@body))
(defmacro math [& body] `(element :math ~@body))
(defmacro menu [& body] `(element :menu ~@body))
(defmacro itemprop [& body] `(element :itemprop ~@body))
(defmacro meter [& body] `(element :meter ~@body))
(defmacro nav [& body] `(element :nav ~@body))
(defmacro noscript [& body] `(element :noscript ~@body))
(defmacro object [& body] `(element :object ~@body))
(defmacro ol [& body] `(element :ol ~@body))
(defmacro option [& body] `(element :option ~@body))
(defmacro output [& body] `(element :output ~@body))
(defmacro p [& body] `(element :p ~@body))
(defmacro picture [& body] `(element :picture ~@body))
(defmacro pre [& body] `(element :pre ~@body))
(defmacro progress [& body] `(element :progress ~@body))
(defmacro q [& body] `(element :q ~@body))
(defmacro ruby [& body] `(element :ruby ~@body))
(defmacro s [& body] `(element :s ~@body))
(defmacro samp [& body] `(element :samp ~@body))
(defmacro script [& body] `(element :script ~@body))
(defmacro section [& body] `(element :section ~@body))
(defmacro select [& body] `(element :select ~@body))
(defmacro slot [& body] `(element :slot ~@body))
(defmacro small [& body] `(element :small ~@body))
(defmacro span [& body] `(element :span ~@body))
(defmacro strong [& body] `(element :strong ~@body))
(defmacro sub [& body] `(element :sub ~@body))
(defmacro sup [& body] `(element :sup ~@body))
(defmacro table [& body] `(element :table ~@body))
(defmacro tbody [& body] `(element :tbody ~@body))
(defmacro td [& body] `(element :td ~@body))
(defmacro th [& body] `(element :th ~@body))
(defmacro thead [& body] `(element :thead ~@body))
(defmacro tr [& body] `(element :tr ~@body))
(defmacro template [& body] `(element :template ~@body))
(defmacro textarea [& body] `(element :textarea ~@body))
(defmacro time [& body] `(element :time ~@body))
(defmacro u [& body] `(element :u ~@body))
(defmacro ul [& body] `(element :ul ~@body))
(defmacro var [& body] `(element :var ~@body))
(defmacro video [& body] `(element :video ~@body))
(defmacro wbr [& body] `(element :wbr ~@body))
