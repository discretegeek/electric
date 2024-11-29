(ns hyperfiddle.electric.impl.pures-fns)

(def pure-fns
  '#{clojure.core/*
     clojure.core/+
     clojure.core/-
     clojure.core//
     clojure.core/<
     clojure.core/<=
     clojure.core/=
     clojure.core/==
     clojure.core/>
     clojure.core/>=
     clojure.core/aget
     clojure.core/alength
     clojure.core/any?
     clojure.core/apply
     clojure.core/array-map
     clojure.core/assoc
     clojure.core/assoc-in
     clojure.core/atom
     clojure.core/boolean
     clojure.core/boolean?
     clojure.core/butlast
     clojure.core/cat
     clojure.core/char
     clojure.core/class
     clojure.core/class?
     clojure.core/coll?
     clojure.core/comp
     clojure.core/comparator
     clojure.core/compare
     clojure.core/complement
     clojure.core/completing
     clojure.core/concat
     clojure.core/conj
     clojure.core/cons
     clojure.core/constantly
     clojure.core/contains?
     clojure.core/count
     clojure.core/counted?
     clojure.core/cycle
     clojure.core/dec
     clojure.core/dedupe
     clojure.core/delay
     clojure.core/delay?
     clojure.core/disj
     clojure.core/dissoc
     clojure.core/distinct
     clojure.core/distinct?
     clojure.core/drop
     clojure.core/drop-last
     clojure.core/drop-while
     clojure.core/eduction
     clojure.core/empty
     clojure.core/empty?
     clojure.core/even?
     clojure.core/every-pred
     clojure.core/every?
     clojure.core/ex-cause
     clojure.core/ex-data
     clojure.core/ex-info
     clojure.core/ex-message
     clojure.core/ffirst
     clojure.core/filter
     clojure.core/filterv
     clojure.core/find
     clojure.core/first
     clojure.core/flatten
     clojure.core/fnext
     clojure.core/fnil
     clojure.core/format
     clojure.core/frequencies
     clojure.core/gensym
     clojure.core/get
     clojure.core/get-in
     clojure.core/group-by
     clojure.core/hash
     clojure.core/hash-map
     clojure.core/hash-set
     clojure.core/identical?
     clojure.core/identity
     clojure.core/inc
     clojure.core/instance?
     clojure.core/interleave
     clojure.core/interpose
     clojure.core/into
     clojure.core/iterate
     clojure.core/juxt
     clojure.core/keep
     clojure.core/keep-indexed
     clojure.core/key
     clojure.core/keys
     clojure.core/keyword
     clojure.core/last
     clojure.core/list*
     clojure.core/list?
     clojure.core/map
     clojure.core/map-indexed
     clojure.core/mapcat
     clojure.core/mapv
     clojure.core/max
     clojure.core/max-key
     clojure.core/merge
     clojure.core/merge-with
     clojure.core/meta
     clojure.core/min
     clojure.core/min-key
     clojure.core/mod
     clojure.core/name
     clojure.core/namespace
     clojure.core/next
     clojure.core/nfirst
     clojure.core/nil?
     clojure.core/nnext
     clojure.core/not
     clojure.core/not-empty
     clojure.core/not=
     clojure.core/nth
     clojure.core/nthnext
     clojure.core/nthrest
     clojure.core/odd?
     clojure.core/partial
     clojure.core/partition
     clojure.core/partition-all
     clojure.core/partition-by
     clojure.core/peek
     clojure.core/pop
     clojure.core/pos-int?
     clojure.core/pos?
     clojure.core/quot
     clojure.core/range
     clojure.core/reduce
     clojure.core/reduce-kv
     clojure.core/reductions
     clojure.core/remove
     clojure.core/repeat
     clojure.core/repeatedly
     clojure.core/rest
     clojure.core/reverse
     clojure.core/second
     clojure.core/select-keys
     clojure.core/seq
     clojure.core/seq?
     clojure.core/sequence
     clojure.core/str
     clojure.core/string?
     clojure.core/subs
     clojure.core/symbol
     clojure.core/take
     clojure.core/take-last
     clojure.core/take-nth
     clojure.core/take-while
     clojure.core/transduce
     clojure.core/type
     clojure.core/update
     clojure.core/update-in
     clojure.core/vals
     clojure.core/vec
     clojure.core/vector
     clojure.core/zero?
     clojure.core/zipmap
     clojure.core/list
     missionary.core/absolve
     missionary.core/ap
     missionary.core/compel
     missionary.core/cp
     missionary.core/dfv
     missionary.core/eduction
     missionary.core/group-by
     missionary.core/join
     missionary.core/latest
     missionary.core/mbx
     missionary.core/never
     missionary.core/none
     missionary.core/observe
     missionary.core/race
     missionary.core/rdv
     missionary.core/reduce
     missionary.core/reductions
     missionary.core/relieve
     missionary.core/sample
     missionary.core/seed
     missionary.core/sem
     missionary.core/sleep
     missionary.core/sp
     missionary.core/timeout
     missionary.core/via-call
     missionary.core/watch
     missionary.core/zip
     hyperfiddle.incseq/count
     hyperfiddle.incseq/mount-items
     hyperfiddle.incseq/combine
     hyperfiddle.incseq/compose
     hyperfiddle.incseq/cycle
     hyperfiddle.incseq/diff-by
     hyperfiddle.incseq/empty-diff
     hyperfiddle.incseq/fixed
     hyperfiddle.incseq/inverse
     hyperfiddle.incseq/items
     hyperfiddle.incseq/latest-concat
     hyperfiddle.incseq/latest-product
     hyperfiddle.incseq/patch-vec
     hyperfiddle.incseq/spine
     hyperfiddle.electric.impl.runtime3/bind
     hyperfiddle.electric.impl.runtime3/dispatch
     hyperfiddle.electric.impl.runtime3/drain
     hyperfiddle.electric.impl.runtime3/get-destructure-map
     hyperfiddle.electric.impl.runtime3/incseq
     hyperfiddle.electric.impl.runtime3/invariant
     hyperfiddle.electric.impl.runtime3/pure
     hyperfiddle.electric.impl.runtime3/effect
     hyperfiddle.electric.impl.runtime3/fixed-signals
     hyperfiddle.electric-dom3/attach!
     hyperfiddle.electric-dom3/await-element
     hyperfiddle.electric-dom3/await-elements
     })
