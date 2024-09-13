(ns hyperfiddle.electric-svg3
  "SVG support is experimental, API subject to change"
  (:refer-clojure :exclude [filter set symbol use])
  (:require [hyperfiddle.electric-dom3 :as dom]
            [hyperfiddle.electric-dom3-props :as props])
  #?(:cljs (:require-macros [hyperfiddle.electric-svg3])))

(defn element* [tag forms] (dom/element* props/SVG-NS tag forms))

(defmacro element [tag & body] (element* tag body))

;;;;;;;;;;;
;; Sugar ;;
;;;;;;;;;;;

(defmacro a                   [& body] (element* :a body))
(defmacro altGlyph            [& body] (element* :altGlyph body))
(defmacro altGlyphDef         [& body] (element* :altGlyphDef body))
(defmacro altGlyphItem        [& body] (element* :altGlyphItem body))
(defmacro animate             [& body] (element* :animate body))
(defmacro animateMotion       [& body] (element* :animateMotion body))
(defmacro animateTransform    [& body] (element* :animateTransform body))
(defmacro circle              [& body] (element* :circle body))
(defmacro clipPath            [& body] (element* :clipPath body))
(defmacro color-profile       [& body] (element* :color-profile body))
(defmacro cursor              [& body] (element* :cursor body))
(defmacro defs                [& body] (element* :defs body))
(defmacro desc                [& body] (element* :desc body))
(defmacro ellipse             [& body] (element* :ellipse body))
(defmacro feBlend             [& body] (element* :feBlend body))
(defmacro feColorMatrix       [& body] (element* :feColorMatrix body))
(defmacro feComponentTransfer [& body] (element* :feComponentTransfer body))
(defmacro feComposite         [& body] (element* :feComposite body))
(defmacro feConvolveMatrix    [& body] (element* :feConvolveMatrix body))
(defmacro feDiffuseLighting   [& body] (element* :feDiffuseLighting body))
(defmacro feDisplacementMap   [& body] (element* :feDisplacementMap body))
(defmacro feDistantLight      [& body] (element* :feDistantLight body))
(defmacro feFlood             [& body] (element* :feFlood body))
(defmacro feFuncA             [& body] (element* :feFuncA body))
(defmacro feFuncB             [& body] (element* :feFuncB body))
(defmacro feFuncG             [& body] (element* :feFuncG body))
(defmacro feFuncR             [& body] (element* :feFuncR body))
(defmacro feGaussianBlur      [& body] (element* :feGaussianBlur body))
(defmacro feImage             [& body] (element* :feImage body))
(defmacro feMerge             [& body] (element* :feMerge body))
(defmacro feMergeNode         [& body] (element* :feMergeNode body))
(defmacro feMorphology        [& body] (element* :feMorphology body))
(defmacro feOffset            [& body] (element* :feOffset body))
(defmacro fePointLight        [& body] (element* :fePointLight body))
(defmacro feSpecularLighting  [& body] (element* :feSpecularLighting body))
(defmacro feSpotLight         [& body] (element* :feSpotLight body))
(defmacro feTile              [& body] (element* :feTile body))
(defmacro feTurbulence        [& body] (element* :feTurbulence body))
(defmacro filter              [& body] (element* :filter body))
(defmacro font                [& body] (element* :font body))
(defmacro font-face           [& body] (element* :font-face body))
(defmacro font-face-format    [& body] (element* :font-face-format body))
(defmacro font-face-name      [& body] (element* :font-face-name body))
(defmacro font-face-src       [& body] (element* :font-face-src body))
(defmacro font-face-uri       [& body] (element* :font-face-uri body))
(defmacro foreignObject       [& body] (element* :foreignObject body))
(defmacro g                   [& body] (element* :g body))
(defmacro glyph               [& body] (element* :glyph body))
(defmacro glyphRef            [& body] (element* :glyphRef body))
(defmacro hkern               [& body] (element* :hkern body))
(defmacro image               [& body] (element* :image body))
(defmacro line                [& body] (element* :line body))
(defmacro linearGradient      [& body] (element* :linearGradient body))
(defmacro marker              [& body] (element* :marker body))
(defmacro mask                [& body] (element* :mask body))
(defmacro metadata            [& body] (element* :metadata body))
(defmacro missing-glyph       [& body] (element* :missing-glyph body))
(defmacro mpath               [& body] (element* :mpath body))
(defmacro path                [& body] (element* :path body))
(defmacro pattern             [& body] (element* :pattern body))
(defmacro polygon             [& body] (element* :polygon body))
(defmacro polyline            [& body] (element* :polyline body))
(defmacro radialGradient      [& body] (element* :radialGradient body))
(defmacro rect                [& body] (element* :rect body))
(defmacro script              [& body] (element* :script body))
(defmacro set                 [& body] (element* :set body))
(defmacro stop                [& body] (element* :stop body))
(defmacro style               [& body] (element* :style body))
(defmacro svg                 [& body] (element* :svg body))
(defmacro switch              [& body] (element* :switch body))
(defmacro symbol              [& body] (element* :symbol body))
(defmacro text                [& body] (element* :text body))
(defmacro textPath            [& body] (element* :textPath body))
(defmacro title               [& body] (element* :title body))
(defmacro tref                [& body] (element* :tref body))
(defmacro tspan               [& body] (element* :tspan body))
(defmacro use                 [& body] (element* :use body))
(defmacro view                [& body] (element* :view body))
(defmacro vkern               [& body] (element* :vkern body))

