(ns gps-sim.satellite
  (:gen-class)
  (:refer-clojure :exclude [* - + == /])
  (:use clojure.core.matrix
        clojure.core.matrix.operators)
  (:require [gps-sim.constants :refer [read-constants! R s c tau]]
            [gps-sim.utils.coordinates :refer [dms->radians rad->cartesian cartesian->rad]]
            [gps-sim.utils.io :refer [file->matrix stdin->matrix matrix->stdout]]
            [gps-sim.utils.matrix :refer [join-1 rotation-matrix]]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(defn rotate-coordinates [times coordinates]
  (let [theta (/ (* @tau times) @s)
        rotations (emap rotation-matrix theta)
        rotated (map #(mmul %1 %2)
                     rotations
                     coordinates)]
    rotated))

(defn above-horizon?
  "To determine if a satellite is above the horizon,
we can project the satellite vector onto the vehicle
position vector. Then if the projection is greater
then the magnitude of the vehicle vector we know it's
above the horizon. This can be simplified as follows,

proj(x_s, x_V) = \\frac{x_s \\cdot x_V}{|| x_V ||} &> || x_V || \\\\
x_s \\cdot x_V &> || x_V ||^2
x_s \\cdot x_V &> x_V \\cdot x_V"
  [vehicle satellite]
  (> (dot (drop 2 satellite) vehicle)
     (dot vehicle vehicle)))

(defn satellite-location
  [satellites t]
  (let [u (mmul satellites (transpose [[0 1 0 0 0 0 0 0 0 0]
                                       [0 0 1 0 0 0 0 0 0 0]
                                       [0 0 0 1 0 0 0 0 0 0]]))
        v (mmul satellites (transpose [[0 0 0 0 1 0 0 0 0 0]
                                       [0 0 0 0 0 1 0 0 0 0]
                                       [0 0 0 0 0 0 1 0 0 0]]))
        period (get-column satellites 7)
        h (get-column satellites 8)
        phase (get-column satellites 9)
        theta (+ (/ (* @tau t) period)
             phase)
        coordinates (* (+ @R h)
                       (+ (* (transpose u) (cos theta))
                          (* (transpose v) (sin theta))))]
    (transpose coordinates)))

(defn satellite-time [{:keys [pseudorange vehicle-coordinates new-coordinates]}]
  (- (map #(distance vehicle-coordinates %) new-coordinates)
     (** pseudorange 2)))

(defn gradient [{:keys [pseudorange satellites
                        satellite-times new-coordinates
                        vehicle-coordinates]}]
  (let [u (mmul satellites (transpose [[0 1 0 0 0 0 0 0 0 0]
                                       [0 0 1 0 0 0 0 0 0 0]
                                       [0 0 0 1 0 0 0 0 0 0]]))
        v (mmul satellites (transpose [[0 0 0 0 1 0 0 0 0 0]
                                       [0 0 0 0 0 1 0 0 0 0]
                                       [0 0 0 0 0 0 1 0 0 0]]))
        periods (get-column satellites 7)
        heights (get-column satellites 8)
        phases (get-column satellites 9)
        theta (+ (/ (* @tau satellite-times) periods)
             phases)

        location-gradient (* (/ @tau periods)
                             (+ @R heights)
                             (+ (* (- (transpose u)) (sin theta))
                                (* (transpose v) (cos theta))))
        coordinate-diffs (- new-coordinates
                            (repeat (count satellites) vehicle-coordinates))
        out (* 2 (+ (* @c @c pseudorange)
                    (map dot
                         coordinate-diffs
                         (transpose location-gradient))))]
    out))

(defn newtons [{:keys [satellite-times] :as params}]
  (let [step (/ (satellite-time params) (gradient params))
        out (- satellite-times step)]
    out))

(defn convergent? [{:keys [error steps max-steps tolerance] :as params}]
  (or (every? #(< % tolerance) error)
      (> steps max-steps)))

(defn next-guess [{:keys [steps satellite-coordinates
                          satellite-times vehicle-times
                          satellites] :as params}]
  (let [new-coordinates (satellite-location satellites
                                            satellite-times)
        new-times (newtons (assoc params :new-coordinates new-coordinates))
        new-pseudorange (- vehicle-times new-times)
        error (map distance satellite-times new-times)]
    (assoc params
      :satellite-times new-times
      :satellite-coordinates new-coordinates
      :pseudorange new-pseudorange
      :error error
      :steps (inc steps))))

(defn solve [start]
  (->> start
       (iterate next-guess)
       (drop-while (comp not convergent?))
       first))

(defn run [data input]
  (read-constants! data)
  (let [satellites (->> data
                        (drop 4)
                        (partition 9))
        indexed-satellites (join-1 (transpose [(range 0 (count satellites))])
                                   satellites)
        input-radians (dms->radians input)             ; t, psi, lambda, h
        input-cartesian (rad->cartesian input-radians) ; x, y, z
        vehicles (rotate-coordinates (get-column input 0)
                                     input-cartesian)]
    (doall
     (mapcat (fn [[time psi lambda h] vehicle]
               (let [times (repeat (count satellites) time)
                     satellite-coordinates (satellite-location indexed-satellites times)
                     pseudorange (/ (map distance
                                         (repeat (count satellites) vehicle)
                                         satellite-coordinates)
                                    @c)
                     satellite-times (- time pseudorange)
                     solution (solve {:satellites indexed-satellites
                                      :satellite-coordinates satellite-coordinates
                                      :pseudorange pseudorange
                                      :vehicle-times times
                                      :vehicle-coordinates vehicle
                                      :satellite-times satellite-times
                                      :tolerance (/ 0.01 @c)
                                      :error [1]
                                      :max-steps 10
                                      :steps 0})
                     solved-satellites (join-1 (transpose [(range 0 (count satellites))
                                                           (:satellite-times solution)])
                                               (:satellite-coordinates solution))]
                 (filter (partial above-horizon? vehicle)
                         solved-satellites)))
             input-radians
             vehicles))))

(defn -main [& args]
  (let [data (-> "data.dat"
                 file->matrix
                 (get-column 0))]
    (->> (stdin->matrix)
         (run data)
         matrix->stdout)
    :ok))
