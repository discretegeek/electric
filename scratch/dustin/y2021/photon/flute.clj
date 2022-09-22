(ns dustin.y2022.flute
  (:require [hyperfiddle.photon :as p]))

(p/defn lineseg [])

(p/defn flute [dur freq amp vfreq]
  (let [amp1 (linseg)
        amp2 (linseg)
        ampv (linseg)
        flow (rand 1 amp1)
        vibr (oscils vfreq (* 0.1 ampv))]
    (let [x (delay (/ 1 freq 2) (+ (* breath flow) amp1 vibr feedbk))
          out (tone (+ x (- (* x x x)) feedbk) 2000)
          feedbk (* body .4)
          body (delay (/ 1 freq) out)])
    (* out (* amp amp2))))
