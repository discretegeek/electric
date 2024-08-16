(ns hyperfiddle.electric.impl.lang-de2
  (:refer-clojure :exclude [compile])
  (:require [cljs.analyzer]
            [cljs.env]
            [clojure.string :as str]
            [contrib.assert :as ca]
            [contrib.debug]
            [clojure.set :as set]
            [contrib.triple-store :as ts]
            [dom-top.core :refer [loopr]]
            [fipp.edn]
            [missionary.core :as m]
            [hyperfiddle.electric-de :as-alias e]
            [hyperfiddle.electric.impl.analyzer :as ana]
            [hyperfiddle.electric.impl.cljs-analyzer2 :as cljs-ana]
            [hyperfiddle.electric.impl.destructure :as dst]
            [hyperfiddle.electric.impl.runtime-de :as r]
            [hyperfiddle.rcf :as rcf :refer [tests]]))

;;;;;;;;;;;
;;; ENV ;;;
;;;;;;;;;;;

(defn clj-env? [env] (not (contains? env :locals)))
(defn electric-env? [env] (contains? env ::peers))
(defn cljs-env? [env] (and (contains? env :locals) (not (electric-env? env))))
(defn ->env-type [env] (if (:js-globals env) :cljs :clj))
(defn normalize-env [env] (if (clj-env? env) {:locals env, :ns {:name (ns-name *ns*)}} env))
(defn get-ns [env] (ca/is (-> env :ns :name) some? "No ns found in environment map" {:env env}))

(defn serialized-require [sym]
  ;; we might be expanding clj code before the ns got loaded (during cljs compilation)
  ;; to correctly lookup vars the ns needs to be loaded
  ;; since shadow-cljs compiles in parallel we need to serialize the requires
  (when-not (get (loaded-libs) sym)
    (try (#'clojure.core/serialized-require sym) ; try bc it can be cljs file
         (catch java.io.FileNotFoundException _))))

(let [-base-cljs-env {:context :statement
                      :locals {}
                      :fn-scope []
                      :js-globals (into {}
                                    (map #(vector % {:op :js-var :name % :ns 'js})
                                      '(alert window document console escape unescape
                                         screen location navigator history location
                                         global process require module exports)))}]
  (defn ->cljs-env
    ([] (->cljs-env (ns-name *ns*)))
    ([nssym] (cond-> -base-cljs-env nssym (assoc :ns {:name nssym})))))

;;;;;;;;;;;;;;;;
;;; EXPANDER ;;;
;;;;;;;;;;;;;;;;

(defn- fn-> [f a] (fn [o] (f o a)))

(declare -expand-all-in-try)

(defn ?expand-clj-method-call [o]
  (let [[s & args] o]
    (if (clojure.lang.Compiler/namesStaticMember s)
      (let [?class (-> s namespace symbol)]
        (if (clojure.lang.Compiler$HostExpr/maybeClass ?class false)
          (list* '. ?class (-> s name symbol) args)
          o))
      o)))

(defn macroexpand-clj [o env]
  (serialized-require (ns-name *ns*))
  (if-some [mac (when-some [mac (resolve env (first o))] (when (.isMacro ^clojure.lang.Var mac) mac))]
    (apply mac o env (next o))
    (try (?expand-clj-method-call o)
         (catch ClassNotFoundException _ o)))) ; e.g. (goog.color/hslToHex ..) won't expand on clj

(def !a (cljs-ana/->!a))

(comment
  (cljs-ana/purge-ns !a 'hyperfiddle.electric-de-test)
  )

(defn expand-macro [env o]
  (let [[f & args] o, n (name f), e (dec (count n))]
    (if (= "." n)
      o
      (if (and (not= ".." n) (= \. (nth n e)))
        `(new ~(symbol (namespace f) (subs n 0 e)) ~@args)
        (if (some? (re-find #"^\.[^.]" n))
          (list* '. (first args) (symbol (subs n 1)) (rest args))
          (if (= :cljs (get (::peers env) (::current env)))
            (if-some [mac (cljs-ana/find-macro-var @!a f (get-ns env))]
              (apply mac o (merge (->cljs-env (get-ns env)) env) args)
              o)
            (macroexpand-clj o env)))))))

(defn find-local-entry [env sym] (contains? (:locals env) sym))
(defn add-local [env sym] (update env :locals assoc sym ::unknown))

(defn ?meta [metao o]
  (if (instance? clojure.lang.IObj o)
    (cond-> o (meta metao) (vary-meta #(merge (meta metao) %)))
    o))

(declare -expand-all -expand-all-foreign -expand-all-foreign-try)

(defn traceable [f] (case (namespace f) ("hyperfiddle.electric.impl.runtime-de" "missionary.core" "hyperfiddle.incseq") false #_else true))

(defn trace-crumb [o env]
  (let [ns (-> env :ns :name), {:keys [line column]} (meta o)]
    (str ns ":" line ":" column " " o)))

(defn ?expand-macro [o env caller]
  (if (symbol? (first o))
    (let [o2 (?meta o (expand-macro env o))]
      (if (identical? o o2)
        (?meta o (cond->> (?meta o (list* (first o) (mapv (fn-> caller env) (rest o))))
                   (and (::trace env) (traceable (first o))) (list `r/tracing (list 'quote (trace-crumb o env)))))
        (caller o2 env)))
    (?meta o (list* (caller (first o) env) (mapv (fn-> caller env) (next o))))))

(defmacro $ [F & args]
  `(::call ((::static-vars r/dispatch) '~F ~F ~@(map (fn [arg] `(::pure ~arg)) args))))

(defn -expand-let-bindings [bs env]
  (loopr [bs2 [], env2 env]
      [[sym v] (eduction (partition-all 2) bs)]
      (recur (conj bs2 sym (-expand-all-foreign v env2)) (add-local env2 sym))))

(defn -expand-fn-arity [[bs & body :as o] env]
  (?meta o (list bs (-expand-all-foreign (?meta body (cons 'do body)) (reduce add-local env bs)))))

(defn -expand-all-foreign [o env]
  (cond
    (and (seq? o) (seq o))
    (if (find-local-entry env (first o))
      (?meta o (list* (first o) (mapv (fn-> -expand-all-foreign env) (rest o))))
      (case (first o)
        (do) (if (nnext o)
               (?meta o (cons 'do (eduction (map (fn-> -expand-all-foreign env)) (next o))))
               (recur (?meta o (second o)) env))

        (let clojure.core/let cljs.core/let)
        (let [[_ bs & body] o] (recur (?meta o (list* 'let* (dst/destructure* bs) body)) env))

        (let* loop*) (let [[call bs & body] o, [bs2 env2] (-expand-let-bindings bs env)]
                       (?meta o (list call bs2 (-expand-all-foreign (?meta body (cons 'do body)) env2))))

        (quote) o

        (fn*) (let [[?name more] (if (symbol? (second o)) [(second o) (nnext o)] [nil (next o)])
                    arities (cond-> more (vector? (first more)) list)]
                (?meta o (list* (into (if ?name ['fn* ?name] ['fn*]) (map (fn-> -expand-fn-arity env)) arities))))

        (letfn*) (let [[_ bs & body] o
                       env2 (reduce add-local env (eduction (take-nth 2) bs))
                       bs2 (->> bs (into [] (comp (partition-all 2)
                                              (mapcat (fn [[sym v]] [sym (-expand-all-foreign v env2)])))))]
                   (?meta o `(letfn* ~bs2 ~(-expand-all-foreign (cons 'do body) env2))))

        (try) (list* 'try (mapv (fn-> -expand-all-foreign-try env) (rest o)))

        (binding clojure.core/binding)
        (let [[_ bs & body] o]
          (?meta o (list 'binding (into [] (comp (partition-all 2)
                                             (mapcat (fn [[sym v]] [sym (-expand-all-foreign v env)]))) bs)
                     (-expand-all-foreign (cons 'do body) env))))

        #_else (?expand-macro o env -expand-all-foreign)))

    (instance? cljs.tagged_literals.JSValue o)
    (cljs.tagged_literals.JSValue. (-expand-all-foreign (.-val o) env))

    (map-entry? o) (clojure.lang.MapEntry. (-expand-all-foreign (key o) env) (-expand-all-foreign (val o) env))
    (coll? o) (?meta (meta o) (into (empty o) (map (fn-> -expand-all-foreign env)) o))
    :else o))

(defn -expand-all-foreign-try [o env]
  (if (seq? o)
    (if (find-local-entry env (first o))
      (?meta o (list* (first o) (mapv (fn-> -expand-all-foreign env) (rest o))))
      (case (first o)
        (catch) (let [[_ typ sym & body] o]
                  (list* 'catch typ sym (mapv (fn-> -expand-all-foreign (add-local env sym)) body)))
        #_else (-expand-all-foreign o env)))
    (-expand-all-foreign o env)))

(defn -expand-all [o env]
  (cond
    (and (seq? o) (seq o))
    (if (find-local-entry env (first o))
      (?meta o (list* (first o) (mapv (fn-> -expand-all env) (rest o))))
      (case (first o)
        ;; (ns ns* deftype* defrecord* var)

        (do) (if (nnext o)
               (let [body (mapv #(list `e/drain %) (next o))
                     body (conj (pop body) (second (peek body)))] ; last arg isn't drained
                 (recur (?meta o (cons `e/amb body)) env))
               (recur (?meta o (second o)) env))

        (let clojure.core/let cljs.core/let)
        (let [[_ bs & body] o] (recur (?meta o (list* 'let* (dst/destructure* bs) body)) env))

        (let*) (let [[_ bs & body] o
                     [bs2 env2] (reduce
                                  (fn [[bs env] [sym v]]
                                    [(conj bs sym (-expand-all v env)) (add-local env sym)])
                                  [[] env]
                                  (partition-all 2 bs))]
                 (?meta o (list 'let* bs2 (-expand-all (?meta body (cons 'do body)) env2))))

        (loop*) (let [[_ bs & body] o
                      [bs2 env2] (reduce
                                   (fn [[bs env] [sym v]]
                                     [(conj bs sym (-expand-all v env)) (add-local env sym)])
                                   [[] env]
                                   (partition-all 2 bs))]
                  (recur (?meta o `(::call (r/bind-args (r/bind-self (::ctor (let [~@(interleave (take-nth 2 bs2)
                                                                                       (map (fn [i] `(::lookup ~i))
                                                                                         (range)))] ~@body)))
                                             ~@(map (fn [arg] `(::pure ~arg))
                                                 (take-nth 2 (next bs2))))))
                    env2))

        (recur) (recur (?meta o `(::call (r/bind-args (::lookup :recur) ~@(map (fn [arg] `(::pure ~arg)) (next o))))) env)

        (case clojure.core/case)
        (let [[_ v & clauses] o
              has-default-clause? (odd? (count clauses))
              clauses2 (cond-> clauses has-default-clause? butlast)
              xpand (fn-> -expand-all env)]
          (?meta o (list* 'case (xpand v)
                     (cond-> (into [] (comp (partition-all 2) (mapcat (fn [[match expr]] [match (xpand expr)])))
                               clauses2)
                       has-default-clause? (conj (xpand (last clauses)))))))

        (if) (let [[_ test then else] o, xpand (fn-> -expand-all env)]
               (?meta o (list 'case (xpand test) '(nil false) (xpand else) (xpand then))))

        (quote) o

        (fn*) (-expand-all-foreign o (dissoc env ::electric))

        (letfn*) (let [[_ bs & body] o
                       env2 (reduce add-local env (take-nth 2 bs))
                       bs2 (->> bs (into [] (comp (partition-all 2)
                                              (mapcat (fn [[sym v]] [sym (-expand-all-foreign v env2)])))))]
                   (recur (?meta o `(let [~(vec (take-nth 2 bs2)) (::cc-letfn ~bs2)] ~(-expand-all (cons 'do body) env2)))
                     env))

        (try) (throw (ex-info "try is TODO" {:o o})) #_(list* 'try (mapv (fn-> -all-in-try env) (rest o)))

        (js*) (let [[_ s & args] o, gs (repeatedly (count args) gensym)]
                (recur (?meta o `((fn* ([~@gs] (~'js* ~s ~@gs))) ~@args)) env))

        (binding clojure.core/binding)
        (let [[_ bs & body] o]
          (?meta o (list 'binding (into [] (comp (partition-all 2) (mapcat (fn [[sym v]] [sym (-expand-all v env)]))) bs)
                     (-expand-all (cons 'do body) env))))

        (set!) (recur (?meta o `((fn* [v#] (set! ~(nth o 1) v#)) ~(nth o 2))) env)

        (::site) (?meta o (seq (conj (into [] (take 2) o)
                                 (-expand-all (cons 'do (drop 2 o)) (assoc env ::current (second o))))))

        #_else (?expand-macro o env -expand-all)))

    (instance? cljs.tagged_literals.JSValue o)
    (cljs.tagged_literals.JSValue. (-expand-all (.-val o) env))

    (map-entry? o) (clojure.lang.MapEntry. (-expand-all (key o) env) (-expand-all (val o) env))
    (coll? o) (?meta (meta o) (into (empty o) (map (fn-> -expand-all env)) o))
    :else o))

#_(defn -expand-all-in-try [o env]
    (if (seq? o)
      (if (find-local-entry env (first o))
        (list* (first o) (mapv (fn-> -expand-all env) (rest o)))
        (case (first o)
          (catch) (let [[_ typ sym & body] o, env2 (add-local env sym)]
                    (list* 'catch typ sym (mapv (fn-> -expand-all env2) body)))
          #_else (-expand-all o env)))
      (-expand-all o env)))

(defn expand-all [env o]
  (cljs-ana/analyze-nsT !a env (get-ns env))
  (let [expanded (-expand-all o (assoc env ::electric true))]
    (when (::print-expansion env) (fipp.edn/pprint expanded))
    expanded))

;;;;;;;;;;;;;;;;
;;; COMPILER ;;;
;;;;;;;;;;;;;;;;

(defn fail!
  ([env msg] (fail! env msg {}))
  ([env msg data] (throw (ex-info (str "\n" (get-ns env) (when-some [d (::def env)] (str "/" d)) ":" (-> env ::meta :line) ":" (-> env ::meta :column) "\n" msg)
                           (merge {:in (::def env) :for (or (::current env) ::unsited)} data)))))

(defn get-them [env] (-> env ::peers keys set (disj (::current env)) first))

(defn cannot-resolve! [env form]
  (fail! env (str "I cannot resolve " "`"form"`"
               (when-let [them (get-them env)]
                 (let [site (name them)]
                   (str ", maybe it's defined only on the " site "?"
                     \newline "If `" form "` is supposed to be a macro, you might need to :refer it in the :require-macros clause."))))
    {:locals (keys (:locals env))}))

(defn ns-qualify [node] (if (namespace node) node (symbol (str *ns*) (str node))))

(tests
  (ns-qualify 'foo) := `foo
  (ns-qualify 'a/b) := 'a/b)

(defn qualify-sym-in-var-node "If ast node is `:var`, update :form to be a fully qualified symbol" [env ast]
  (if (and (= :var (:op ast)) (not (-> ast :env :def-var)))
    (assoc ast :form (case (or (get (::peers env) (::current env)) (->env-type env))
                       :clj  (symbol (str (:ns (:meta ast))) (str (:name (:meta ast))))
                       :cljs (:name (:info ast))))
    ast))

(defn closure
  "Analyze a cc/fn form, looking for electric defs and electric lexical bindings references.
  Rewrites the cc/fn form into a closure over electric dynamic and lexical scopes.
  Return a pair [closure form, references to close over].

  e.g.:
  (let [x 1]
    (binding [y 2]
      (fn [arg] [x y arg])))

   =>
  [(fn [x123 y123]
     (fn [& rest-args123]
       (binding [y y123]
         (let [x x123]
           (apply (fn [arg] [x y arg]) rest-args123)))))
   [x y]]
  "
  [env form]
  (let [refered-evars   (atom {})
        refered-lexical (atom {})
        edef?           (fn [ast] (and (= :var (:op ast)) (not (-> ast :env :def-var))))
        dynamic?        (fn [ast] (or (:assignable? ast) ; clj
                                    (:dynamic (:meta (:info ast))) ; cljs
                                    ))
        lexical?        (fn [ast] (or (::provided? ast) ; clj
                                    (::provided? (:info ast)) ;cljs
                                    ))
        namespaced?     (fn [ast] (qualified-symbol? (:form ast)))
        safe-let-name   (fn [sym] (if (qualified-symbol? sym)
                                    (symbol (str/replace (str (munge sym)) #"\." "_"))
                                    sym))
        record-lexical! (fn [{:keys [form]}]
                          (swap! refered-lexical assoc form
                            (gensym (name form))))
        record-edef!    (fn [{:keys [form] :as ast}]
                          (if (dynamic? ast)
                            (swap! refered-evars assoc form #_(ana/var-name ast) (gensym (name form)))
                            (record-lexical! ast)))
        env             (update env :locals update-vals #(if (map? %) (assoc % ::provided? true) {::provided? true}))
        rewrite-ast     (fn [ast]
                          (cond
                            (edef? ast)    (do (record-edef! ast)
                                               (cond (dynamic? ast)    (qualify-sym-in-var-node env ast)
                                                     (namespaced? ast) (update ast :form safe-let-name)
                                                     :else             ast))
                            (lexical? ast) (do (record-lexical! ast) ast)
                            :else          (qualify-sym-in-var-node env ast)))
        var-set?        (fn [ast] (and (= :set! (:op ast)) (= :var (:op (:target ast)))))
        rewrite-cljs-ast (fn walk [ast]
                           (cond
                             (var-set? ast) (update ast :val walk)
                             (edef? ast)    (do (record-edef! ast)
                                                (cond (dynamic? ast)    (qualify-sym-in-var-node env ast)
                                                      (namespaced? ast) (update ast :form safe-let-name)
                                                      :else             ast))
                             (lexical? ast) (do (record-lexical! ast) ast)
                             :else          (let [quald-ast (qualify-sym-in-var-node env ast)]
                                              (if-some [cs (:children quald-ast)]
                                                (reduce (fn [ast k]
                                                          (update ast k #(if (vector? %) (mapv walk %) (walk %))))
                                                  quald-ast cs)
                                                quald-ast))))
        form            (case (or (get (::peers env) (::current env)) (->env-type env))
                          :clj  (-> (ana/analyze-clj (update env :ns :name) form)
                                  (ana/walk-clj rewrite-ast)
                                  (ana/emit-clj))
                          :cljs (-> (binding [cljs.analyzer/*cljs-warning-handlers*
                                              [(fn [_warning-type _env _extra])]]
                                      (ana/analyze-cljs env form))
                                  (rewrite-cljs-ast)
                                  (ana/emit-cljs)))
        rest-args-sym   (gensym "rest-args")
        all-syms        (merge @refered-evars @refered-lexical)
        [syms gensyms]  [(keys all-syms) (vals all-syms)]
        fn?             (and (seq? form) (#{'fn 'fn* 'clojure.core/fn 'clojure.core/fn* 'cljs.core/fn 'cljs.core/fn*} (first form)))
        form            (if fn?
                          `(apply ~form ~rest-args-sym)
                          form)
        form            (if (seq @refered-lexical)
                          `(let [~@(flatten (map (fn [[k v]] [(safe-let-name k) v]) @refered-lexical))]
                             ~form)
                          form)
        form            (if (seq @refered-evars)
                          `(binding [~@(flatten (seq @refered-evars))]
                             ~form)
                          form)
        form            (if fn?
                          `(fn [~@gensyms] (fn [~'& ~rest-args-sym] ~form))
                          `(fn [~@gensyms] ~form))]
    [form syms]))

(defn bound-js-fn
  "Given a js global resolving to a function (e.g js/alert, js/console.log required-js-ns/js-fn), ensures it
  is called under the correct `this` context."
  [sym]
  (let [fields (str/split (name sym) #"\.")]
    `(.bind ~sym ~(symbol (namespace sym)
                    (if (seq (rest fields))
                      (str/join (interpose '. (butlast fields)))
                      "globalThis")))))

(defn resolve-static-field [sym]
  (when-some [ns (some-> (namespace sym) symbol)]
    (when-some [cls (resolve ns)]
      (when (class? cls)
        (clojure.lang.Reflector/getField cls (name sym) true)))))

(defn get-children-e [ts e] (-> ts :ave ::parent (get e)))
(defn ?get-child-e [ts e] (first (get-children-e ts e)))
(defn get-child-e [ts e] (ca/is (first (get-children-e ts e)) some? (str "no child for " e) {:e e, :nd (ts/->node ts e)}))
(defn get-root-e [ts] (get-child-e ts '_))

(defn ?add-source-map [{{::keys [->id]} :o :as ts} pe form]
  (let [mt (meta form)]
    (cond-> ts (:line mt) (ts/add {:db/id (->id), ::source-map-of pe, ::line (:line mt), ::column (:column mt)}))))

(defn untwin [s]
  (if (= "cljs.core" (namespace s))
    (let [clj (symbol "clojure.core" (name s))]
      (if (resolve clj) clj s))
    s))

(tests
  (untwin 'cljs.core/prn) := 'clojure.core/prn
  (untwin 'a/b) := 'a/b
  (untwin 'a) := 'a
  (untwin 'cljs.core/not-in-clj) := 'cljs.core/not-in-clj)

(defn node? [mt] (::node mt))
(defn resolve-node [sym env]
  (case (->env-type env)
    :clj (when-some [^clojure.lang.Var vr (resolve env sym)]
           (when (-> vr meta node?) (symbol vr)))
    :cljs (when-some [vr (cljs-ana/find-var @!a sym (get-ns env))]
            ;; temporary hack
            ;; the commented out expression should work, seems the new cljs analyzer loses the metadata
            ;; so we check it on clj side, which is safe for a clj-server/cljs-client setup
            (when-some [vr (cljs-ana/safe-requiring-resolve (-> vr ::cljs-ana/name))]
              (when (-> vr meta node?) (symbol vr)))
            #_(when (-> vr ::cljs-ana/meta node?)
                (symbol (-> vr :name str))))))

(defn analyze-clj-symbol [sym ns$]
  (if (resolve-static-field sym)
    {::type ::static, ::sym sym}
    (when-some [v (some-> (find-ns ns$) (ns-resolve sym))]
      (if (var? v) {::type ::var, ::sym (symbol v) ::meta (meta v)} {::type ::static, ::sym sym}))))

(def implicit-cljs-nses '#{goog goog.object goog.string goog.array Math String})

(defn analyze-cljs-symbol [sym env]
  (if-some [v (cljs-ana/find-var @!a sym (get-ns env))]
    {::type ::var, ::sym (untwin (::cljs-ana/name v)), ::meta (::cljs-ana/meta v)}
    (if-some [quald (when (qualified-symbol? sym) (cljs-ana/ns-qualify @!a sym (get-ns env)))]
      {::type ::static, ::sym quald}
      (if (or (cljs-ana/referred? @!a sym (get-ns env)) (cljs-ana/js-call? @!a sym (get-ns env)))
        {::type ::static, ::sym sym}
        (when (cljs-ana/imported? @!a sym (get-ns env))
          {::type ::static, ::sym sym})))))

(defn resolve-symbol [sym env]
  (if-some [local (-> env :locals (get sym))]
    (if-some [uid (::electric-let local)]
      {::lang nil, ::type ::localref, ::sym sym, ::ref uid}
      {::lang nil, ::type ::local, ::sym sym})
    (if (= sym (::def env))
      {::lang nil, ::type ::self, ::sym sym}
      (if-some [nd (resolve-node sym env)]
        {::lang nil, ::type ::node, ::node nd}
        (case (get (::peers env) (::current env))
          :clj (let [v (analyze-clj-symbol sym (get-ns env))]
                 (case v nil (cannot-resolve! env sym) #_else (assoc v ::lang :clj)))
          :cljs (let [v (analyze-cljs-symbol sym env)]
                  (case v nil (cannot-resolve! env sym) #_else (assoc v ::lang :cljs)))
          #_unsited (case (->env-type env)
                      :clj (assoc (or (analyze-clj-symbol sym (get-ns env)) {::type ::var, ::sym `r/cannot-resolve})
                             :lang :clj)
                      :cljs (assoc (or (analyze-cljs-symbol sym env) {::type ::var, ::sym `r/cannot-resolve})
                              :lang :cljs)))))))


(defn ->bindlocal-value-e [ts e] (first (get-children-e ts e)))
(defn ->bindlocal-body-e [ts e] (second (get-children-e ts e)))
(defn ->localv-e [ts mklocal-uid]
  (->> (ts/find ts ::type ::bindlocal, ::ref mklocal-uid) first (get-child-e ts)))


(defn get-ret-e [ts e]
  (let [nd (get (:eav ts) e)]
    (case (::type nd)
      (::bindlocal) (recur ts (->bindlocal-body-e ts e))
      (::site ::mklocal) (recur ts (get-child-e ts e))
      #_else e)))

(defn find-sitable-point-e [ts e]
  (loop [e e]
    (when-some [nd (ts/->node ts e)]
      (case (::type nd)
        (::literal ::ap ::join ::pure ::comp ::ctor ::call ::frame) e
        (::site) (when (some? (::site nd)) (recur (::parent nd)))
        (::var ::node ::lookup ::mklocal ::bindlocal ::localref) (some-> (::parent nd) recur)
        #_else (throw (ex-info (str "can't find-sitable-point-e for " (pr-str (::type nd))) (or nd {})))))))

(defn get-site [ts e]
  (loop [e (find-sitable-point-e ts e)]
    (when-some [nd (get (:eav ts) e)]
      (case (::type nd)
        ::site (::site nd)
        #_else (recur (::parent nd))))))

(defn get-local-site [ts localv-e]
  (let [ret-e (get-ret-e ts localv-e)]
    (loop [e ret-e]
      (let [nd (ts/->node ts e)]
        (case (::type nd)
          (::localref) (get-local-site ts (->localv-e ts (::ref nd)))
          (::site) (::site nd)
          #_else (recur (::parent nd)))))))

(defn get-lookup-key [sym env]
  (if (symbol? sym)
    (let [it (resolve-symbol sym env)]
      (case (::type it)
        (::var) (keyword (::sym it))
        (::node) (keyword (::node it))
        (::static) (throw (ex-info (str "`" sym "` did not resolve as a var") {::form sym}))
        #_else (keyword sym)))
    sym))

(declare analyze analyze-foreign)

(defn add-literal [{{::keys [->id]} :o :as ts} v e pe]
  (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
    (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v v})))

(defn add-ap-literal [f args pe e env {{::keys [->id ->uid]} :o :as ts}]
  (let [ce (->id)]
    (reduce (fn [ts form] (analyze form e env ts))
      (-> (ts/add ts {:db/id e, ::parent pe, ::type ::ap, ::uid (->uid)})
        #_(add-literal f ce e)
        (ts/add {:db/id ce, ::parent e, ::type ::pure})
        (ts/add {:db/id (->id), ::parent ce, ::type ::literal, ::v f}))
      args)))

(defn ->class-method-call [clazz method method-args pe env form {{::keys [->id]} :o :as ts}]
  (if (seq method-args)
    (let [f (let [margs (repeatedly (count method-args) gensym), meth (symbol (str clazz) (str method))]
              `(fn [~@margs] (~meth ~@margs)))]
      (add-ap-literal f method-args pe (->id) env ts))
    (let [e (->id)]                     ; (. java.time.Instant now)
      (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
        (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v form})))))

(defn meta-of-key [mp k] (-> mp keys set (get k) meta))
(defn gensym-with-local-meta [env k]
  (let [g (gensym (if (instance? clojure.lang.Named k) (name k) "o")), mt (meta-of-key (:locals env) k)]
    (with-meta g (merge mt (meta k)))))

(defn ->obj-method-call [o method method-args pe env {{::keys [->id]} :o :as ts}]
  (let [f (let [[oo & margs] (mapv #(gensym-with-local-meta env %) (cons o method-args))]
            `(fn [~oo ~@margs] (. ~oo ~method ~@margs)))]
    (add-ap-literal f (cons o method-args) pe (->id) env ts)))

(defn def-sym-in-cljs-compiler! [sym ns]
  (swap! @(requiring-resolve 'cljs.env/*compiler*)
    assoc-in [:cljs.analyzer/namespaces ns :defs sym] {:name sym}))

(defn e->uid [ts e] (ca/is (::uid (ts/->node ts e)) some? "node without uid" {:e e, :nd (ts/->node ts e)}))
(defn uid->e [ts uid] (first (ca/check #(= 1 (count %)) (ts/find ts ::uid uid))))
(defn reparent-children [ts from-e to-e]
  (reduce (fn [ts e] (ts/asc ts e ::parent to-e)) ts (ts/find ts ::parent from-e)))

(defn ?update-meta [env form] (cond-> env (meta form) (assoc ::meta (meta form))))

(defn my-turn? [env] (let [c (get (::peers env) (::current env))] (or (nil? c) (= c (->env-type env)))))

(defn analyze [form pe env {{::keys [->id ->uid]} :o :as ts}]
  (let [env (?update-meta env form)]
    (cond
      (and (seq? form) (seq form))
      (case (first form)
        (let*) (let [[_ bs bform] form]
                 (recur (?meta form
                          (reduce (fn [ac [k v]]
                                    (let [g (with-meta (gensym k) (meta k))]
                                      `(::mklocal ~g (::bindlocal ~g ~v (::mklocal ~k (::bindlocal ~k ~g ~ac))))))
                            bform (->> bs (partition 2) reverse)))
                   pe env ts))
        (::mklocal) (let [[_ k bform] form, e (->id), uid (->uid)
                          ts (-> ts (ts/add {:db/id e, ::parent pe, ::type ::mklocal, ::k k, ::uid uid})
                               (?add-source-map e form))]
                      (recur bform e (update-in env [:locals k] assoc ::electric-let uid) ts))
        (::bindlocal) (let [[_ k v bform] form, e (->id)
                            ts (ts/add ts {:db/id e, ::parent pe, ::type ::bindlocal ::k k
                                           ::ref (-> env :locals (get k) ::electric-let)})
                            ts (analyze v e env ts)]
                        (recur bform e env ts))
        (case) (let [[_ test & brs] form
                     [default brs2] (if (odd? (count brs)) [(last brs) (butlast brs)] [:TODO brs])]
                 (loopr [bs [], mp {}]
                   [[v br] (partition 2 brs2)]
                   (let [b (gensym "case-val")]
                     (recur (conj bs b `(::ctor ~br))
                       (reduce (fn [ac nx] (assoc ac (list 'quote nx) b)) mp (if (seq? v) v [v]))))
                   (recur (?meta form `(let* ~bs (::call (~mp ~test (::ctor ~default))))) pe env ts)))
        (quote) (let [e (->id)]
                  (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
                    (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v form})))
        (fn*) (let [current (get (::peers env) (::current env))
                    arg* (analyze-foreign form env), {:keys [d]} (group-by first arg*)]
                (if (or (nil? current) (= (->env-type env) current))
                  (if (seq arg*)
                    (let [form (if (seq d) `(binding [~@(mapcat (fn [[_d lex dyn _v]] [dyn lex]) d)] ~form) form)]
                      (add-ap-literal `(fn* [~@(mapv second arg*)] ~form)
                        (mapv #(nth % 3) arg*) pe (->id) env ts))
                    (add-literal ts form (->id) pe))
                  (recur `[~@(mapv #(nth % 2) arg*)] pe env ts)))
        (::cc-letfn) (let [[_ bs] form, [form refs] (closure env `(letfn* ~bs ~(vec (take-nth 2 bs)))), e (->id)]
                       (add-ap-literal form refs pe e env (?add-source-map ts e form)))
        (new) (let [[_ f & args] form, current (get (::peers env) (::current env))]
                (if (or (nil? current) (= (->env-type env) current))
                  (let [f (let [gs (repeatedly (count args) gensym)] `(fn [~@gs] (new ~f ~@gs)))]
                    (add-ap-literal f args pe (->id) env ts))
                  (recur `[~@args] pe env ts)))
        ;; (. java.time.Instant now)
        ;; (. java.time.Instant ofEpochMilli 1)
        ;; (. java.time.Instant (ofEpochMilli 1))
        ;; (. java.time.Instant EPOCH)
        ;; (. java.time.Instant -EPOCH)
        ;; (. i1 isAfter i2)
        ;; (. i1 (isAfter i2))
        ;; (. pt x)
        ;; (. pt -x)
        (.) (let [me? (my-turn? env)]
              (cond
                (implicit-cljs-nses (second form)) ; (Math/abs -1) expanded to (. Math abs -1)
                (let [[_ clazz method & method-args] form] ; cljs fails on dot form, so we compile as class call
                  (->class-method-call clazz method method-args pe env form ts))

                (and (symbol? (second form)) (class? (resolve env (second form))))
                (if (seq? (nth form 2)) ; (. java.time.Instant (ofEpochMilli 1))
                  (if me?
                    (let [[_ clazz [method & method-args]] form]
                      (->class-method-call clazz method method-args pe env form ts))
                    (recur `[~@(next (nth form 2))] pe env ts))
                  (let [[_ clazz x & xs] form] ; (. java.time.instant opEpochMilli 1)
                    (if me?
                      (->class-method-call clazz x xs pe env form ts)
                      (recur `[~@xs] pe env ts))))

                (seq? (nth form 2))     ; (. i1 (isAfter i2))
                (let [[_ o [method & method-args]] form]
                  (if me?
                    (->obj-method-call o method method-args pe env ts)
                    (recur `[~(second form) ~@(next (nth form 2))] pe env ts)))

                :else
                (let [[_ o x & xs] form]
                  (if (seq xs)          ; (. i1 isAfter i2)
                    (if me?
                      (->obj-method-call o x xs pe env ts)
                      (recur `[~o ~@xs] pe env ts))
                    (if me?             ; (. pt x)
                      (add-ap-literal `(fn [oo#] (. oo# ~x)) [o] pe (->id) env ts)
                      (recur nil pe env ts))))))
        (binding clojure.core/binding) (let [[_ bs bform] form, gs (repeatedly (/ (count bs) 2) gensym)]
                                         (recur (if (seq bs)
                                                  `(let* [~@(interleave gs (take-nth 2 (next bs)))]
                                                     (::call ((::static-vars r/bind) (::ctor ~bform)
                                                              ~@(interleave
                                                                  (mapv #(get-lookup-key % env) (take-nth 2 bs))
                                                                  (mapv #(list ::pure %) gs)))))
                                                  bform)
                                           pe env ts))
        (def) (let [[_ sym v] form]
                (case (->env-type env)
                  :clj (recur `((fn* ([x#] (def ~sym x#))) ~v) pe env ts)
                  :cljs (do (def-sym-in-cljs-compiler! sym (get-ns env))
                            (add-ap-literal `(fn [v#] (set! ~sym v#)) [v] pe (->id) env ts))))
        (set!) (let [[_ target v] form] (recur `((fn* ([v#] (set! ~target v#))) ~v) pe env ts))
        (::ctor) (let [e (->id), ce (->id)]
                   (recur (list ::site nil (second form))
                     ce env (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
                              (ts/add {:db/id ce, ::parent e, ::type ::ctor, ::uid (->uid)})
                              (?add-source-map e form))))
        (::call) (let [e (->id)] (recur (second form) e env
                                   (-> (ts/add ts {:db/id e, ::parent pe, ::type ::call, ::uid (->uid)})
                                     (?add-source-map e form))))
        (::tag) (let [e (->id)] (recur (second form) e env
                                  (-> (ts/add ts {:db/id e, ::parent pe, ::type ::call, ::uid (->uid), ::call-type ::tag})
                                    (?add-source-map e form))))
        (::pure) (let [pure (with-meta (gensym "pure") {::dont-inline true})]
                   (recur `(let* [~pure ~(second form)] (::pure-gen ~pure)) pe env ts))
        (::pure-gen) (let [e (->id)]
                       (recur (second form) e env (-> (ts/add ts {:db/id e, ::parent pe, ::type ::pure})
                                                    (?add-source-map e form))))
        (::join) (let [e (->id)] (recur (second form) e env (-> (ts/add ts {:db/id e, ::parent pe, ::type ::join})
                                                              (?add-source-map e form))))
        (::site) (let [[_ site bform] form, current (::current env), env2 (assoc env ::current site)]
                   (if (or (nil? site) (= site current) (= ::bindlocal (::type (ts/->node ts pe))))
                     (let [e (->id)]
                       (recur bform e env2
                         (-> (ts/add ts {:db/id e, ::parent pe, ::type ::site, ::site site})
                           (?add-source-map e form))))
                     ;; Due to an early bad assumption only locals are considered for runtime nodes.
                     ;; Since any site change can result in a new node we wrap these sites in an implicit local.
                     ;; Electric aggressively inlines locals, so the generated code size will stay the same.
                     (let [g (gensym "site-local")]
                       (recur `(::mklocal ~g (::bindlocal ~g ~form ~g)) pe env2 ts))))
        (::frame) (let [e (->id)] (-> ts
                                    (ts/add {:db/id e, ::parent pe, ::type ::pure})
                                    (ts/add {:db/id (->id), ::parent e, ::type ::frame})))
        (::lookup) (let [[_ sym] form] (ts/add ts {:db/id (->id), ::parent pe, ::type ::lookup, ::sym sym}))
        (::static-vars) (recur (second form) pe (assoc env ::static-vars true) ts)
        (::debug) (recur (second form) pe (assoc env ::debug true) ts)
        #_else (let [current (get (::peers env) (::current env)), [f & args] form]
                 (if (and (= :cljs (->env-type env)) (contains? #{nil :cljs} current) (symbol? f)
                       (let [js-call? (cljs-ana/js-call? @!a f (get-ns env))]
                         (when (::debug env) (prn :js-call? f '=> js-call?))
                         js-call?))
                   (add-ap-literal (bound-js-fn f) args pe (->id) env ts)
                   (let [e (->id), uid (->uid)]
                     (reduce (fn [ts nx] (analyze nx e env ts))
                       (-> (ts/add ts {:db/id e, ::parent pe, ::type ::ap, ::uid uid})
                         (?add-source-map uid form)) form)))))

      (instance? cljs.tagged_literals.JSValue form)
      (let [o (.-val ^cljs.tagged_literals.JSValue form)]
        (if (map? o)
          (recur (?meta form (cons `(::static-vars cljs.core/js-obj) (into [] (mapcat (fn [[k v]] [(name k) v])) o)))
            pe env ts)
          (recur (?meta form (cons `(::static-vars cljs.core/array) o)) pe env ts)))

      (vector? form) (recur (?meta form (cons `(::static-vars vector) form)) pe env ts)
      (map? form) (recur (?meta form (cons `(::static-vars hash-map) (eduction cat form))) pe env ts)
      (set? form) (recur (?meta form (cons `(::static-vars hash-set) form)) pe env ts)

      (symbol? form)
      (let [e (->id), ret (resolve-symbol form env)]
        (-> (case (::type ret)
              (::localref) (ts/add ts {:db/id e, ::parent pe, ::type ::localref, ::ref (::ref ret)
                                       ::sym form, ::uid (->uid)})
              (::local) (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
                          (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v form}))
              (::self) (let [ce (->id)]
                         (-> ts
                           (ts/add {:db/id e, ::parent pe, ::type ::lookup, ::sym (keyword (ns-qualify form))})
                           (ts/add {:db/id ce, ::parent e, ::type ::pure})
                           (ts/add {:db/id (->id), ::parent ce, ::type ::literal, ::v (list form)})))
              (::static ::var) (if (::static-vars env)
                                 (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
                                   (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v form}))
                                 (ts/add ts (cond-> {:db/id e, ::parent pe, ::type ::var
                                                     ::var form, ::qualified-var (::sym ret)}
                                              (::lang ret) (assoc ::resolved-in (::lang ret)))))
              (::node) (ts/add ts {:db/id e, ::parent pe, ::type ::node, ::node (::node ret)})
              #_else (throw (ex-info (str "unknown symbol type " (::type ret)) (or ret {}))))
          (?add-source-map e form)))

      :else
      (let [e (->id)]
        (-> ts (ts/add {:db/id e, ::parent pe, ::type ::pure})
          (ts/add {:db/id (->id), ::parent e, ::type ::literal, ::v form})
          (?add-source-map e form))))))

(defn add-foreign-local [env sym] (update env :locals update sym assoc ::electric-let nil))

(defn analyze-foreign
  ([form env] (analyze-foreign form env #{} gensym))
  ([form env acc] (analyze-foreign form env acc gensym))
  ([form env acc gen]
   ((fn rec [form env acc]
      (cond
        (and (seq? form) (seq form))
        (case (first form)
          (let*) (let [[_ bs & body] form
                       [acc2 env2] (loopr [acc2 acc, env2 env]
                                       [[sym v] (eduction (partition-all 2) bs)]
                                       (recur (rec v env2 acc2) (add-foreign-local env2 sym)))]
                   (recur (cons 'do body) env2 acc2))

          (quote) acc

          (fn*) (let [[?name arity+] (if (symbol? (second form)) [(second form) (nnext form)] [nil (next form)])
                      env2 (cond-> env ?name (add-foreign-local ?name))]
                  (reduce (fn [acc [args & body]]
                            (rec (cons 'do body)
                              (reduce add-foreign-local env2 args) acc)) acc arity+))

          (letfn*) (let [[_ bs & body] form
                         env (reduce add-foreign-local env (eduction (take-nth 2) bs))
                         acc (loopr [acc2 acc]
                                 [[_fn _nm & arity+] (eduction (take-nth 2) (next bs))
                                  [arg* & body] arity+]
                                 (recur (rec (cons 'do body) (reduce add-foreign-local env arg*) acc2)))]
                     (recur (cons 'do body) env acc))

          (.) (cond (and (symbol? (second form))
                      (or (implicit-cljs-nses (second form))
                        (class? (resolve env (second form)))))
                    ;; (. Instant (ofEpochMilli 1)) vs. (. Instant ofEpochMilli 1)
                    (let [arg* (if (seq? (nth form 2)) (next (nth form 2)) (drop 3 form))]
                      (reduce (fn [acc nx] (rec nx env acc)) acc arg*))

                    (seq? (nth form 2)) ; (. i1 (isAfter i2))
                    (reduce (fn [acc nx] (rec nx env acc))
                      (rec (second form) env acc) (next (nth form 2)))

                    :else
                    (let [[_ o _x & x*] form]
                      (if (seq x*)      ; (. i1 isAfter i2) vs. (. pt x)
                        (reduce (fn [acc nx] (rec nx env acc))
                          (rec o env acc) x*)
                        (recur o env acc))))

          (binding clojure.core/binding) (let [[_ bs & body] form
                                               acc (transduce (take-nth 2)
                                                     (completing (fn [acc nx] (rec nx env acc)))
                                                     acc
                                                     (next bs))]
                                           (recur (cons 'do body) env acc))

          (def set!) (recur (nth form 2) env acc)

          (new) (reduce (fn [acc nx] (rec nx env acc)) acc (nnext form))

          (do) (reduce (fn [acc nx] (rec nx env acc)) acc (next form))

          #_else (reduce (fn [acc nx] (rec nx env acc)) acc form))

        (instance? cljs.tagged_literals.JSValue form)
        (let [o (.-val ^cljs.tagged_literals.JSValue form)]
          (reduce (fn [acc nx] (rec nx env acc)) acc (if (map? o) (eduction cat o) o)))

        (map? form) (reduce (fn [acc nx] (rec nx env acc)) acc (eduction cat form))
        (or (vector? form) (set? form)) (reduce (fn [acc nx] (rec nx env acc)) acc form)

        (symbol? form) (let [ret (resolve-symbol form env)]
                         (case (::type ret)
                           (::localref) (conj acc [:l form form form])
                           (::local ::static) acc
                           (::self ::node) (throw (ex-info "Cannot pass electric defns to clojure(script) interop"
                                                    {:var (or (::sym ret) (::node ret))}))
                           (::var) (conj acc (if (:dynamic (::meta ret))
                                               [:d (gen form) form (::sym ret)]
                                               [:l form form (::sym ret)]))
                           #_else (throw (ex-info (str "unknown symbol type " (::type ret)) (or ret {})))))

        :else acc))
    form env acc)))

(defn ->->id [] (let [!i (long-array [-1])] (fn [] (aset !i 0 (unchecked-inc (aget !i 0))))))

(letfn [(add [{{::keys [->id]} :o :as ts} u p ->i more]
          (ts/add ts (assoc more :db/id (->id), ::u u, ::p p, ::i (->i))))]
  (defn analyze-foreign2
    ([form env] (analyze-foreign2 (ts/->ts {::->id (->->id), ::->u (->->id)}) form env -1 (->->id)))
    ([{{::keys [->u]} :o :as ts} form env p ->i]
     (cond (and (seq? form) (seq form))
           (case (first form)
             (let*) (let [[_ bs & body] form, let*-u (->u)
                          ts (add ts let*-u p ->i {::t ::let*})
                          ->sym-i (->->id)
                          [ts2 env2] (loopr [ts2 ts, env2 env]
                                         [[sym v] (eduction (partition-all 2) bs)]
                                         (let [sym-u (->u)]
                                           (recur (-> ts2 (add sym-u let*-u ->sym-i {::t ::let*-sym, ::sym sym})
                                                    (analyze-foreign2 v env2 sym-u (->->id)))
                                             (add-foreign-local env2 sym))))
                          body-u (->u), ->body-i (->->id)]
                      (reduce (fn [ts nx] (analyze-foreign2 ts nx env2 body-u ->body-i))
                        (add ts2 body-u let*-u (->->id) {::t ::body}) body))

             (binding clojure.core/binding)
             (let [[_ bs & body] form, bind-u (->u)
                   ts (add ts bind-u p ->i {::t ::binding})
                   ->sym-i (->->id)
                   ts (reduce (fn [ts [sym v]]
                                (let [sym-u (->u)]
                                  (-> ts (add sym-u bind-u ->sym-i {::t ::binding-sym, ::sym sym})
                                    (analyze-foreign2 v env sym-u (->->id)))))
                        ts (eduction (partition-all 2) bs))
                   body-u (->u), ->body-i (->->id)]
               (reduce (fn [ts nx] (analyze-foreign2 ts nx env body-u ->body-i))
                 (add ts body-u bind-u (->->id) {::t ::body}) body))

             (quote) (add ts (->u) p ->i {::t ::quote, ::v form})

             (fn*) (let [[?name arity+] (if (symbol? (second form)) [(second form) (nnext form)] [nil (next form)])
                         env2 (cond-> env ?name (add-foreign-local ?name))
                         fn*-u (->u), ->arity-i (->->id)
                         ts (add ts fn*-u p ->i (cond-> {::t ::fn*} ?name (assoc ::name ?name)))]
                     (reduce (fn [ts [args & body]]
                               (let [arity-u (->u), ->body-i (->->id)
                                     ts (add ts arity-u fn*-u ->arity-i {::t ::fn*-arity, ::args args})
                                     env3 (reduce add-foreign-local env2 args)]
                                 (reduce (fn [ts nx] (analyze-foreign2 ts nx env3 arity-u ->body-i)) ts body)))
                       ts arity+))

             (letfn*) (let [[_ bs & body] form
                            env (reduce add-foreign-local env (eduction (take-nth 2) bs))
                            letfn*-u (->u), ->fn-i (->->id)
                            ts (add ts letfn*-u p ->i {::t ::letfn*})
                            ts (reduce (fn [ts f] (analyze-foreign2 ts f env letfn*-u ->fn-i))
                                 ts (eduction (take-nth 2) (next bs)))
                            body-u (->u), ->body-i (->->id)]
                        (reduce (fn [ts nx] (analyze-foreign2 ts nx env body-u ->body-i))
                          (add ts body-u letfn*-u ->i {::t ::body}) body))

             (.) (cond (and (symbol? (second form))
                         (or (implicit-cljs-nses (second form))
                           (class? (resolve env (second form)))))
                       ;; (. Instant (ofEpochMilli 1)) vs. (. Instant ofEpochMilli 1)
                       (let [[method & args] (if (symbol? (nth form 2)) (drop 2 form) (nth form 2))
                             class-u (->u), ->class-i (->->id)]
                         (reduce (fn [ts nx] (analyze-foreign2 ts nx env class-u ->class-i))
                           (add ts class-u p ->i {::t ::class-method-call, ::class (second form), ::method method})
                           args))

                       (and (empty? (drop 3 form)) (symbol? (nth form 2))) ; (. pt x)
                       (let [field-u (->u)]
                         (recur (add ts field-u p ->i {::t ::field-access, ::field (nth form 2)})
                           (second form) env field-u (->->id)))

                       :else         ; (. i1 isAfter i2) vs. (. i1 (isAfter i2))
                       (let [[method & args] (if (symbol? (nth form 2)) (drop 2 form) (nth form 2))
                             method-u (->u), ->method-i (->->id)]
                         (reduce (fn [ts nx] (analyze-foreign2 ts nx env method-u ->method-i))
                           (add ts method-u p ->i {::t ::method-call, ::method method})
                           (cons (second form) args))))

             (def) (let [u (->u)]
                     (recur (add ts u p ->i {::t ::def, ::sym (second form)}) (nth form 2) env u (->->id)))

             (set!) (let [set-u (->u), ->set-i (->->id)]
                      (reduce (fn [ts nx] (analyze-foreign2 ts nx env set-u ->set-i))
                        (add ts set-u p ->i {::t ::set!}) (next form)))

             (new) (let [new-u (->u), ->new-i (->->id)]
                     (reduce (fn [ts nx] (analyze-foreign2 ts nx env new-u ->new-i))
                       (add ts new-u p ->i {::t ::new}) (next form)))

             (do) (let [do-u (->u), ->do-i (->->id)]
                    (reduce (fn [ts nx] (analyze-foreign2 ts nx env do-u ->do-i))
                      (add ts do-u p ->i {::t ::do}) (next form)))

             #_else (let [ap-u (->u), ->ap-i (->->id)]
                      (reduce (fn [ts nx] (analyze-foreign2 ts nx env ap-u ->ap-i))
                        (add ts ap-u p ->i {::t ::invoke}) form))
             )

           (instance? cljs.tagged_literals.JSValue form)
           (let [o (.-val ^cljs.tagged_literals.JSValue form)]
             (if (map? o)
               (let [map-u (->u), ->map-i (->->id)]
                 (reduce (fn [ts nx] (analyze-foreign2 ts nx env map-u ->map-i))
                   (add ts map-u p ->i {::t ::js-map}) (eduction cat o)))
               (let [vec-u (->u), ->vec-i (->->id)]
                 (reduce (fn [ts nx] (analyze-foreign2 ts nx env vec-u ->vec-i))
                   (add ts vec-u p ->i {::t ::js-array}) o))))

           (map? form) (let [map-u (->u), ->map-i (->->id)]
                         (reduce (fn [ts nx] (analyze-foreign2 ts nx env map-u ->map-i))
                           (add ts map-u p ->i {::t ::map}) (eduction cat form)))

           (set? form) (let [set-u (->u), ->set-i (->->id)]
                         (reduce (fn [ts nx] (analyze-foreign2 ts nx env set-u ->set-i))
                           (add ts set-u p ->i {::t ::set}) form))

           (vector? form) (let [vector-u (->u), ->vector-i (->->id)]
                            (reduce (fn [ts nx] (analyze-foreign2 ts nx env vector-u ->vector-i))
                              (add ts vector-u p ->i {::t ::vector}) form))

           (symbol? form) (let [ret (resolve-symbol form env)]
                            (case (::type ret)
                              (::localref) (add ts (->u) p ->i
                                             {::t ::electric-local, ::sym form
                                              ::resolved (::sym ret), ::ref (::ref ret)})
                              (::local) (add ts (->u) p ->i
                                          {::t ::local, ::sym form, ::resolved (::sym ret)})
                              (::static) (add ts (->u) p ->i
                                           {::t ::static, ::sym form, ::resolved (::sym ret)})
                              (::self ::node) (throw (ex-info "Cannot pass electric defns to clojure(script) interop"
                                                       {:var form}))
                              (::var) (add ts (->u) p ->i
                                        {::t ::var, ::sym form ::resolved (::sym ret), ::meta (::meta ret)})
                              #_else (throw (ex-info (str "unknown symbol type " (::type ret)) (or ret {})))))

           :else (add ts (->u) p ->i {::t ::literal, ::v form})))))

;; NEXT
;; - write a pass that finds electric locals and vars
;; - emit a wrapper fn that takes the values and binds them
;; - lexicals can be bound at fn args
;; - simple symbol vars too
;; - qualified symbol vars need a "gensym" rewrite and binding
;; - dynamic vars need a "gensym" lexical that we bind to the dynamic var

(defn emit-foreign
  ([ts] (emit-foreign ts 0))
  ([ts u]
   (letfn [(->node [u] (ts/->node ts (ts/find1 ts ::u u)))
           (->u [e] (::u (ts/->node ts e)))
           (order [u*] (sort-by (comp ::i ->node) u*))
           (find [& kvs] (order (eduction (map ->u) (apply ts/find ts kvs))))
           (find1 [& kvs] (->u (apply ts/find1 ts kvs)))
           (? [u k] (get (->node u) k))
           (emit-foreign-arity [u] (cons (? u ::args) (eduction (map emit) (find ::p u))))
           (unname [v] (cond-> v (instance? clojure.lang.Named v) name))
           (emit [u]
             (let [nd (->node u)]
               (case (::t nd)
                 (::let*) (let [{sym* ::let*-sym, body ::body} (group-by #(? % ::t) (find ::p u))]
                            (list* 'let* (into [] (mapcat (fn [u] [(? u ::sym) (emit (find1 ::p u))])) sym*)
                              (eduction (map emit) (find ::p (first body)))))
                 (::binding) (let [{sym* ::binding-sym, body ::body} (group-by #(? % ::t) (find ::p u))]
                               (list* 'binding (into [] (mapcat (fn [u] [(? u ::sym) (emit (find1 ::p u))])) sym*)
                                 (eduction (map emit) (find ::p (first body)))))
                 (::quote) (::v nd)
                 (::literal) (::v nd)
                 (::fn*) (list* (into (cond-> ['fn*] (::name nd) (conj (::name nd)))
                                  (map emit-foreign-arity (find ::t ::fn*-arity, ::p u))))
                 (::letfn*) (let [{f* ::fn*, body ::body} (group-by #(? % ::t) (find ::p u))]
                              (list* 'letfn* (into [] (mapcat (fn [u] [(? u ::name) (emit u)])) f*)
                                (eduction (map emit) (find ::p (first body)))))
                 ;; (Math/abs -1) expands to (. Math abs -1) but the dot form fails on cljs
                 ;; so we generate (Math/abs -1)
                 (::class-method-call) (list* (symbol (str (::class nd)) (str (::method nd)))
                                         (eduction (map emit) (find ::p u)))
                 (::field-access) (list '. (emit (find1 ::p u)) (::field nd))
                 (::method-call) (let [[o & arg*] (find ::p u)]
                                   (list* '. (emit o) (::method nd) (eduction (map emit) arg*)))
                 (::def) (list 'def (? u ::sym) (emit (find1 ::p u)))
                 (::set!) (list* 'set! (eduction (map emit) (find ::p u)))
                 (::new) (list* 'new (eduction (map emit) (find ::p u)))
                 (::do) (list* 'do (eduction (map emit) (find ::p u)))
                 (::invoke) (map emit (find ::p u))
                 (::js-map) (list* 'js-object (eduction (map emit) (map unname) (find ::p u)))
                 (::js-array) (list* 'array (eduction (map emit) (map unname) (find ::p u)))
                 (::map) (apply hash-map (eduction (map emit) (find ::p u)))
                 (::set) (set (eduction (map emit) (find ::p u)))
                 (::vector) (vec (eduction (map emit) (find ::p u)))
                 (::electric-local ::local ::static ::var) (::sym nd)
                 )))]
     (emit u))))

(defn find-ctor-e [ts e]
  (let [pe (::parent (get (:eav ts) e))]
    (if (or (nil? pe) (= ::ctor (::type (get (:eav ts) pe)))) pe (recur ts pe))))

(defn- ts->reducible* [ts f init]
  (loop [ac init, es (cons (get-root-e ts) (set/difference (-> ts :eav keys set) (->> ts :ave ::parent vals (reduce into)))), seen #{}]
    (if (or (reduced? ac) (empty? es))
      (unreduced ac)
      (let [[e & es] es]
        (if (seen e)
          (recur ac es seen)
          (recur (f ac (ts/->node ts e)) (concat (get-children-e ts e) es) (conj seen e)))))))

(defn ts->reducible [ts]
  (reify clojure.lang.IReduce
    (reduce [_ f init] (ts->reducible* ts f init))
    (reduce [_ f] (ts->reducible* ts f (f)))))

(defn get-node-idx [ts ctor-uid uid]
  (->> (ts/find ts ::ctor-node ctor-uid, ::ctor-ref uid) first (ts/->node ts) ::node-idx))

(defn ->thunk [xs] `(fn* [] (~@xs)))

(defn tag-call? [ts e] (= ::tag (::call-type (ts/->node ts e))))

(defn emit [ts e ctor-e env nm]
  ((fn rec [e]
     (let [nd (get (:eav ts) e)]
       (case (::type nd)
         ::literal (::v nd)
         ::ap (list* `r/ap (mapv rec (get-children-e ts e)))
         ::var (let [in (::resolved-in nd)]
                 (list* `r/lookup 'frame (keyword (::qualified-var nd))
                   (when (or (nil? in) (= in (->env-type env))) [(list `r/pure (::qualified-var nd))])))
         ::node (list `r/lookup 'frame (keyword (::node nd)) (list `r/pure (list `r/resolve 'frame (keyword (::node nd)))))
         ::join (list `r/join (rec (get-child-e ts e)))
         ::pure (list `r/pure (rec (get-child-e ts e)))
         ::comp ((or (::comp-fn nd) ->thunk) (eduction (map rec) (get-children-e ts e))) #_(list 'fn* '[] (doall (map rec (get-children-e ts e))))
         ::site (recur (get-child-e ts e))
         ::ctor (list* `r/ctor nm (::ctor-idx nd)
                  (mapv (fn [e]
                          (let [nd (ts/->node ts e)]
                            (case (::closed-over nd)
                              ::node (list `r/node 'frame
                                       (get-node-idx ts
                                         (e->uid ts (find-ctor-e ts (uid->e ts (::ctor-free nd))))
                                         (::closed-ref nd)))
                              ::free (list `r/free 'frame
                                       (->> (ts/find ts
                                              ::ctor-free (e->uid ts
                                                            (find-ctor-e ts (uid->e ts (::ctor-free nd))))
                                              ::closed-ref (::closed-ref nd))
                                         first (ts/->node ts) ::free-idx)))))
                    (ts/find ts ::ctor-free (e->uid ts e))))
         ::call (if (tag-call? ts e)
                  (list `r/pure (list `r/tag 'frame (::call-idx nd)))
                  (list `r/join (list `r/call 'frame (::call-idx nd))))
         ::frame 'frame
         ::lookup (list* `r/lookup 'frame (::sym nd) (when-some [c (?get-child-e ts e)] (list (rec c))))
         ::mklocal (recur (get-ret-e ts (get-child-e ts e)))
         ::bindlocal (recur (get-ret-e ts (->bindlocal-body-e ts e)))
         ::localref
         (if-some [node-e (first (ts/find ts ::ctor-node (e->uid ts ctor-e), ::ctor-ref (::ref nd)))]
           (list `r/node 'frame (::node-idx (ts/->node ts node-e)))
           (if-some [free-e (first (ts/find ts ::ctor-free (e->uid ts ctor-e), ::closed-ref (::ref nd)))]
             (list `r/free 'frame (::free-idx (ts/->node ts free-e)))
             (throw (ex-info "localref must be a node or free" nd))))
         #_else (throw (ex-info (str "cannot emit on " (pr-str (::type nd))) (or nd {}))))))
   e))

(defn emit-node-init [ts ctor-e node-e env nm]
  (let [nd (get (:eav ts) node-e)]
    (list `r/define-node 'frame (::node-idx nd)
      (emit ts (->> (::ctor-ref nd) (->localv-e ts) (get-ret-e ts)) ctor-e env nm))))

(defn emit-call-init [ts ctor-e e env nm]
  (list `r/define-call 'frame (::call-idx (ts/->node ts e))
    (emit ts (get-ret-e ts (get-child-e ts e)) ctor-e env nm)))

(defn get-ordered-ctors-e [ts] (into [] (map (comp first second)) (->> ts :ave ::ctor-idx (sort-by first))))

(defn get-ordered-calls-e [ts ctor-uid]
  (->> (ts/find ts ::ctor-call ctor-uid) (sort-by #(::call-idx (ts/->node ts %)))))

(defn get-ordered-nodes-e [ts ctor-uid]
  (->> (ts/find ts ::ctor-node ctor-uid) (sort-by #(::node-idx (ts/->node ts %)))))

(defn compute-program-order [ts e]
  (let [->order (->->id), ord (fn [ts e] (ts/upd ts e ::pg-order #(or % (->order)))), seen (volatile! #{})]
    ((fn rec [ts e]
       (let [nd (ts/->node ts e)]
         (if (@seen e)
           ts
           (do (vswap! seen conj e)
               (case (::type nd)
                 (::literal ::var ::lookup ::node ::frame) (ord ts e)
                 (::ap ::comp) (ord (reduce rec ts (get-children-e ts e)) e)
                 (::site ::join ::pure ::call ::ctor ::mklocal) (ord (rec ts (get-child-e ts e)) e)
                 (::bindlocal) (-> ts (rec (->bindlocal-value-e ts e)) (rec (->bindlocal-body-e ts e)) (ord e))
                 (::localref) (ord (rec ts (->localv-e ts (::ref nd))) (uid->e ts (::ref nd)))
                 #_else (throw (ex-info (str "cannot compute-program-order on " (pr-str (::type nd))) (or nd {})))
                 )))))
     ts e)))

(defn emit-ctor [ts ctor-e env nm]
  (let [ret-e (get-ret-e ts (get-child-e ts ctor-e))
        ctor-uid (::uid (ts/->node ts ctor-e))
        nodes-e (get-ordered-nodes-e ts ctor-uid)
        calls-e (get-ordered-calls-e ts ctor-uid)]
    `(r/cdef ~(count (ts/find ts ::ctor-free ctor-uid))
       ~(mapv #(get-site ts (->> (ts/->node ts %) ::ctor-ref (->localv-e ts) (get-ret-e ts)))
          nodes-e)
       ~(mapv #(get-site ts %) calls-e)
       ~(get-site ts ret-e)
       (fn [~'frame]
         ~@(let [node-inits (->> nodes-e
                              (mapv (fn [e] [(->> e (ts/->node ts) ::ctor-ref (uid->e ts) (ts/->node ts) ::pg-order)
                                             (emit-node-init ts ctor-e e env nm)])))
                 call-inits (->> calls-e
                              (remove #(tag-call? ts %))
                              (mapv (fn [e] [(->> e (ts/->node ts) ::pg-order)
                                             (emit-call-init ts ctor-e e env nm)])))]
             ;; with xforms would be
             ;; (into [] (comp cat (x/sort-by first) (map second)) [node-inits call-inits])
             (->> (concat node-inits call-inits) (sort-by first) (eduction (map second))))
         ~(emit ts ret-e ctor-e env nm)))))

(defn emit-deps [ts e]
  (let [seen (volatile! #{})
        ret (volatile! (sorted-set))
        mark (fn mark [ts e]
               (if (@seen e)
                 ts
                 (let [nd (ts/->node ts e)]
                   (vswap! seen conj e)
                   (case (::type nd)
                     (::literal ::var ::lookup ::frame) ts
                     (::ap ::comp) (reduce mark ts (get-children-e ts e))
                     (::site ::join ::pure ::call ::ctor ::mklocal) (recur ts (get-child-e ts e))
                     (::bindlocal) (recur ts (->bindlocal-body-e ts e))
                     (::localref) (recur ts (->> (::ref nd) (->localv-e ts) (get-ret-e ts)))
                     (::node) (do (vswap! ret conj (::node nd)) ts)
                     #_else (throw (ex-info (str "cannot emit-deps/mark on " (pr-str (::type nd))) (or nd {})))))))]
    (mark ts e)
    @ret))

(defn emit-fn [ts e nm]
  ((fn rec [e]
     (let [nd (get (:eav ts) e)]
       (case (::type nd)
         ::ap (map rec (get-children-e ts e))
         (::pure ::site) (rec (get-child-e ts e))
         ::comp ((or (::comp-fn nd) ->thunk) (eduction (map rec) (get-children-e ts e)))
         ::literal (::v nd)
         ::ctor `(r/ctor ~nm ~(::ctor-idx nd))
         ::mklocal (recur (get-ret-e ts (get-child-e ts e)))
         ::localref (recur (->> (::ref nd) (->localv-e ts) (get-ret-e ts))))))
   e))

(defn delete-point-recursively [ts e]
  (let [ts (ts/del ts e)]
    (if-some [ce (get-children-e ts e)]
      (reduce delete-point-recursively ts ce)
      ts)))

(def pure-fns '#{clojure.core/vector clojure.core/hash-map clojure.core/get clojure.core/boolean
                 hyperfiddle.electric.impl.runtime-de/incseq})

(defn implode-point [ts e]              ; remove e, reparent child, keep e as id
  (let [nd (ts/->node ts e), ce (get-child-e ts e), cnd (ts/->node ts ce)]
    (-> ts (ts/del e) (ts/del ce) (ts/add (assoc cnd :db/id e, ::parent (::parent nd))) (reparent-children ce e))))

(defn wrap-point [{{::keys [->id]} :o :as ts} e wrap-nd] ; wrap e in another point `nd`, keeping order
  (let [nd (ts/->node ts e), new-e (->id)]
    (-> ts (ts/del e)
      (ts/add (merge wrap-nd (select-keys nd [:db/id ::parent])))
      (reparent-children e new-e)
      (ts/add (assoc nd :db/id new-e, ::parent e)))))

(defn get-program-order [ts e] (::pg-order (ts/->node ts e)))

(defn analyze-electric [env {{::keys [->id]} :o :as ts}]
  (when (::print-analysis env) (prn :analysis) (run! prn (ts->reducible ts)))
  (let [pure-fn? (fn pure-fn? [nd] (and (= ::literal (::type nd)) (pure-fns (::v nd))))
        collapse-ap-with-only-pures
        (fn collapse-ap-with-only-pures [ts]
          (reduce (fn [ts ap-uid]
                    (let [ap-e (uid->e ts ap-uid), ce (get-children-e ts ap-e)]
                      (when (::print-ap-collapse env) (prn :ap-collapse) (run! prn (ts->reducible ts)))
                      (if (every? #(= ::pure (::type (ts/->node ts (get-ret-e ts %)))) ce)
                        (if (pure-fn? (->> ce first (get-ret-e ts) (get-child-e ts) (ts/->node ts)))
                          ;; (ap (pure vector) (pure 1) (pure 2)) -> (pure (comp-with list vector 1 2))
                          (-> (reduce (fn [ts ce]
                                        (let [pure-e (get-ret-e ts ce)]
                                          (implode-point ts pure-e)))
                                (ts/asc ts ap-e ::type ::comp, ::comp-fn list*) ce)
                            (wrap-point ap-e {::type ::pure}))
                          ;; (ap (pure x) (pure y) (pure z)) -> (ap (pure (comp-with ->call x y z)))
                          (let [pure-e (->id), comp-e (->id)]
                            (reduce (fn [ts e]
                                      (let [ce (->> e (get-ret-e ts) (get-child-e ts))
                                            cnd (ts/->node ts ce), newe (->id)]
                                        (-> ts
                                          (ts/add (assoc cnd :db/id newe, ::parent comp-e))
                                          (reparent-children ce newe)
                                          (delete-point-recursively e))))
                              (-> ts
                                (ts/add {:db/id pure-e, ::parent ap-e, ::type ::pure})
                                (ts/add {:db/id comp-e, ::parent pure-e, ::type ::comp}))
                              ce)))
                        ts)))
            ts (eduction (map #(e->uid ts %)) (ts/find ts ::type ::ap))))
        ->ctor-idx (->->id)
        seen (volatile! #{})
        mark-used-ctors (fn mark-used-ctors [ts e]
                          (if (@seen e)
                            ts
                            (let [nd (get (:eav ts) e)]
                              (vswap! seen conj e)
                              (case (::type nd)
                                (::literal ::var ::lookup ::node ::frame) ts
                                (::ap ::comp) (reduce mark-used-ctors ts (get-children-e ts e))
                                (::site ::join ::pure ::call ::mklocal) (recur ts (get-child-e ts e))
                                (::bindlocal) (recur ts (->bindlocal-body-e ts e))
                                (::ctor) (if (::ctor-idx nd)
                                           ts
                                           (recur (ts/asc ts e ::ctor-idx (->ctor-idx)) (get-child-e ts e)))
                                (::localref) (recur ts (->> (::ref nd) (->localv-e ts) (get-ret-e ts)))
                                #_else (throw (ex-info (str "cannot mark-used-ctors on " (pr-str (::type nd))) (or nd {})))))))
        ts (-> ts (compute-program-order (get-root-e ts)) (mark-used-ctors (get-root-e ts)))
        ctors-uid (mapv #(e->uid ts %) (get-ordered-ctors-e ts))
        has-node? (fn has-node? [ts uid] (ts/find ts ::ctor-ref uid))
        ensure-node (fn ensure-node [ts uid]
                      (let [ctor-uid (e->uid ts (find-ctor-e ts (uid->e ts uid)))]
                        (cond-> ts (not (has-node? ts uid))
                                (ts/add {:db/id (->id) ::ctor-node ctor-uid, ::ctor-ref uid}))))
        ensure-free-node (fn ensure-free-node [ts uid ctor-uid]
                           (cond-> ts (not (ts/find ts ::ctor-free ctor-uid, ::closed-ref uid))
                                   (ts/add {:db/id (->id) ::ctor-free ctor-uid, ::closed-ref uid, ::closed-over ::node})))
        ensure-free-free (fn ensure-free-free [ts uid ctor-uid]
                           (cond-> ts (not (ts/find ts ::ctor-free ctor-uid, ::closed-ref uid))
                                   (ts/add {:db/id (->id) ::ctor-free ctor-uid, ::closed-ref uid, ::closed-over ::free})))
        ensure-free-frees (fn ensure-free-frees [ts uid ctors-uid]
                            (reduce (fn [ts ctor-uid] (ensure-free-free ts uid ctor-uid)) ts ctors-uid))
        order-nodes (fn order-nodes [ts]
                      (reduce (fn [ts nodes-e]
                                (let [->idx (->->id)]
                                  (reduce (fn [ts e] (ts/asc ts e ::node-idx (->idx)))
                                    ts (sort-by #(get-program-order ts (uid->e ts (::ctor-ref (ts/->node ts %)))) nodes-e))))
                        ts (-> ts :ave ::ctor-node vals)))
        order-frees (fn order-frees [ts]
                      (reduce (fn [ts frees-e]
                                (let [->idx (->->id)]
                                  (reduce (fn [ts e] (ts/asc ts e ::free-idx (->idx)))
                                    ts (sort-by #(::pg-order (ts/->node ts %)) frees-e))))
                        ts (-> ts :ave ::ctor-free vals)))
        unlink (fn [ts e]
                 (-> ts (reparent-children e (::parent (ts/->node ts e))) (ts/del e)))
        inline-locals (fn inline-locals [ts]
                        (reduce (fn [ts mklocal-uid]
                                  (let [mklocal-nd (ca/is (ts/->node ts (uid->e ts mklocal-uid)) (comp #{::mklocal} ::type))
                                        localrefs-e (mapv #(uid->e ts %) (::used-refs mklocal-nd))
                                        localref-e (first (ca/check #(= 1 (count %)) localrefs-e {:refs localrefs-e, :mklocal-nd mklocal-nd}))
                                        localv-e (->localv-e ts mklocal-uid), localv-nd (ts/->node ts localv-e)
                                        site (get-site ts (get-ret-e ts localv-e))]
                                    (-> ts
                                      (ts/asc localref-e ::type ::site)
                                      (ts/asc localref-e ::site site)
                                      (ts/asc localv-e ::parent localref-e)
                                      (unlink (:db/id mklocal-nd))
                                      (unlink (::parent localv-nd)))))
                          ts (->> ts :ave ::used-refs vals (reduce into)
                               (mapv #(e->uid ts %))
                               (remove #(has-node? ts %)))))
        in-a-call? (fn in-a-call? [ts ref-e mklocal-e]
                     (loop [e (::parent (ts/->node ts ref-e))]
                       (when-let [nd (ts/->node ts e)]
                         (case (::type nd)
                           ::call e
                           ::ctor nil
                           #_else (when (not= e mklocal-e) (recur (::parent nd)))))))
        seen (volatile! #{})
        reroute-local-aliases (fn reroute-local-aliases [ts]
                                (reduce (fn [ts bl-e]
                                          (let [v-e (get-child-e ts bl-e), v-nd (ts/->node ts v-e)]
                                            (if (= ::localref (::type v-nd))
                                              (let [bl-nd (ts/->node ts bl-e)]
                                                (reduce (fn [ts lr-e] (ts/asc ts lr-e ::ref (::ref v-nd)))
                                                  ts
                                                  (ts/find ts ::type ::localref, ::ref (::ref bl-nd))))
                                              ts)))
                                  ts (ts/find ts ::type ::bindlocal)))
        optimize-locals (fn optimize-locals [ts e] ; nodes and frees (closed over)
                          (let [nd (ts/->node ts e)]
                            (case (::type nd)
                              (::literal ::var ::lookup ::node ::frame) ts
                              (::ap ::comp) (reduce optimize-locals ts (get-children-e ts e))
                              (::site ::join ::pure ::ctor ::call ::mklocal) (recur ts (get-child-e ts e))
                              (::bindlocal) (recur ts (->bindlocal-body-e ts e))
                              (::localref)
                              (let [mklocal-uid (::ref nd), mklocal-e (uid->e ts mklocal-uid)
                                    mklocal-nd (ts/->node ts mklocal-e)
                                    ctors-e (loop [ac '(), e (::parent (ts/->node ts e))]
                                              (if (= mklocal-e e)
                                                ac
                                                (let [nd (ts/->node ts e)]
                                                  (recur (cond-> ac (= ::ctor (::type nd)) (conj e)) (::parent nd)))))
                                    ctors-uid (mapv #(e->uid ts %) ctors-e)
                                    localv-e (->localv-e ts mklocal-uid)
                                    ts (cond-> ts (::dont-inline (meta (::k mklocal-nd)))
                                               (ensure-node mklocal-uid))
                                    ts (if-some [call-e (in-a-call? ts e mklocal-e)]
                                         (-> ts (ts/upd mklocal-e ::in-call #(conj (or % #{}) (e->uid ts call-e)))
                                           (ensure-node mklocal-uid))
                                         ts)
                                    ts (if (seq ctors-e) ; closed over
                                         (-> ts (ensure-node mklocal-uid)
                                           (ensure-free-node mklocal-uid (first ctors-uid))
                                           (ensure-free-frees mklocal-uid (next ctors-uid)))
                                         (cond-> (ts/upd ts mklocal-e ::used-refs #(conj (or % #{}) (::uid nd)))
                                           (or (= 1 (count (::used-refs mklocal-nd))) ; before inc, now it's 2
                                             (when-some [pt-e (find-sitable-point-e ts e)]
                                               (not= (get-site ts pt-e) (get-local-site ts localv-e))))
                                           (ensure-node mklocal-uid)))]
                                (or (and (@seen mklocal-uid) ts)
                                  (do (vswap! seen conj mklocal-uid)
                                      (recur ts (get-ret-e ts localv-e)))))
                              #_else (throw (ex-info (str "cannot optimize-locals on " (::type nd)) (or nd {}))))))
        ->call-idx (let [mp (zipmap ctors-uid (repeatedly ->->id))]
                     (fn ->call-idx [ctor-uid] ((get mp ctor-uid))))
        seen (volatile! #{})
        mark-used-calls (fn mark-used-calls [ts ctor-e e]
                          (if (@seen e)
                            ts
                            (let [nd (ts/->node ts e)]
                              (vswap! seen conj e)
                              (case (::type nd)
                                (::literal ::var ::lookup ::node ::ctor ::frame) ts
                                (::ap ::comp) (reduce #(mark-used-calls % ctor-e %2) ts (get-children-e ts e))
                                (::site ::join ::pure ::mklocal) (recur ts ctor-e (get-child-e ts e))
                                (::bindlocal) (recur ts ctor-e (->bindlocal-body-e ts e))
                                (::call) (if (::ctor-call nd)
                                           ts
                                           (-> (mark-used-calls ts ctor-e (get-child-e ts e))
                                             (ts/asc e ::ctor-call (::uid (ts/->node ts ctor-e)))))
                                (::let) (recur ts ctor-e (->bindlocal-body-e ts e))
                                (::localref) (let [nx-e (->> (::ref nd) (->localv-e ts) (get-ret-e ts))]
                                               (recur ts (find-ctor-e ts nx-e) nx-e))
                                #_else (throw (ex-info (str "cannot mark-used-calls on " (::type nd)) (or nd {})))))))
        mark-used-calls2 (fn [ts]
                           (reduce (fn [ts ctor-e] (mark-used-calls ts ctor-e (get-ret-e ts (get-child-e ts ctor-e))))
                             ts (->> ts :ave ::ctor-idx vals (reduce into))))
        index-calls (fn [ts]
                      (reduce (fn [ts e] (ts/asc ts e ::call-idx (->call-idx (::ctor-call (ts/->node ts e)))))
                        ts (sort-by #(get-program-order ts %) (->> ts :ave ::ctor-call vals (reduce into)))))
        ts (-> ts mark-used-calls2 index-calls reroute-local-aliases (optimize-locals (get-root-e ts))
             inline-locals order-nodes order-frees collapse-ap-with-only-pures)]
    (when (::print-db env) (prn :db) (run! prn (ts->reducible ts)))
    ts))

(defn compile* [nm env ts]
  (let [ts (analyze-electric env ts)
        ret `(fn
               ([] {0 (r/ctor ~nm 0)})
               ([idx#]
                (case idx#
                  ~@(->> (get-ordered-ctors-e ts)
                      (map #(emit-ctor ts % env nm))
                      (interleave (range))))))]
    (when (and (::print-clj-source env) (= :clj (->env-type env))) (fipp.edn/pprint ret))
    (when (and (::print-cljs-source env) (= :cljs (->env-type env))) (fipp.edn/pprint ret))
    ret))

(defn ->ts [] (ts/->ts {::->id (->->id), ::->uid (->->id)}))

(defn compile [nm form env]
  (compile* nm env
    (analyze (expand-all env `(::ctor ~form))
      '_ env (->ts))))

(defn ->source [env root-key efn]
  (let [expanded (expand-all env efn)
        _ (when (::print-expansion env) (fipp.edn/pprint expanded))
        ts (analyze expanded '_ env (->ts))
        _  (when (::print-analysis env) (run! prn (->> ts :eav vals (sort-by :db/id))))
        ts (analyze-electric env ts)
        ctors (mapv #(emit-ctor ts % env root-key) (get-ordered-ctors-e ts))
        deps-set (emit-deps ts (get-root-e ts))
        deps (into {} (map (fn [dep] [(keyword dep) dep])) deps-set)
        source `(fn ([] ~(emit-fn ts (get-root-e ts) root-key))
                  ([idx#] (case idx# ~@(interleave (range) ctors)))
                  ([get# deps#] ~deps))]
    (when (and (::print-clj-source env) (= :clj (->env-type env))) (fipp.edn/pprint source))
    (when (and (::print-cljs-source env) (= :cljs (->env-type env))) (fipp.edn/pprint source))
    source))
