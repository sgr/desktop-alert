;; -*- coding: utf-8-unix -*-
(ns desktop-alert
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [decorator :as deco])
  (:import [java.awt AWTEvent Component Dimension EventQueue GraphicsEnvironment Shape Window]
           [java.awt.event WindowAdapter WindowEvent]))

(def DEFAULT-OPACITY (float 0.9))
(def MARGIN 5)

(defn max-columns
  "Returns max displayable columns in current screen.
   width is width of the alert."
  [width]
  {:pre [(pos? width)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ MARGIN width)]
    (quot (.width r) aw)))

(defn max-rows
  "Returns max displayable rows in current screen.
   height is height of the alert."
  [height]
  {:pre [(pos? height)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        ah (+ MARGIN height)]
    (quot (.height r) ah)))

(defn max-plats
  "Returns max displayable plats in current screen.
   width is width of the alert.
   height is height of the alert."
  [width height]
  {:pre [(pos? width)
         (pos? height)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ MARGIN width)
        ah (+ MARGIN height)]
    (* (quot (.width r) aw) (quot (.height r) ah))))

(defn- create-alert [parent size x y opacity shape]
  (let [alert (Window. parent)]
    (when opacity (deco/set-opacity alert (float opacity)))
    (when shape (deco/set-shape alert shape))
    (doto alert
      (.addWindowListener
       (proxy [WindowAdapter] []
         (windowClosing [_]
           (.setVisible alert false)
           (doseq [child (.getComponents alert)]
             (.remove alert child)
             (try
               (.dispose child)
               (catch Exception _))))
         ))
      (.setPreferredSize size)
      (.setMinimumSize size)
      (.setLocation x y)
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true))))

(defn- divide-plats
  "parent: アラートダイアログの親ウィンドウ
   width:  ダイアログの幅
   height: ダイアログの高さ
   mode: アラートモード。←↓(:rl-tb) ←↑(:rl-bt) →↓(:lr-tb) →↑(:lr-bt)
   column: 列数。0が指定された場合は制限なし(画面全体を使って表示する)。"
  [parent width height mode column opacity shape]
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ MARGIN width), ah (+ MARGIN height)
        rx (.x r) ry (.y r)
        rw (.width r), w (quot rw aw)
        rh (.height r), h (quot rh ah)
        idxs (for [x (range 1 (inc (if (= 0 column) w (min column w))))
                   y (range 1 (inc h))]
               [x y])
        f (condp = mode
            :rl-bt (fn [[x y]] {:x (+ rx (- rw (* x aw))) :y (+ ry (- rh (* y ah)))})
            :rl-tb (fn [[x y]] {:x (+ rx (- rw (* x aw))) :y (+ ry (* (dec y) ah))})
            :lr-tb (fn [[x y]] {:x (+ rx (* (dec x) aw)) :y (+ ry (* (dec y) ah))})
            :lr-bt (fn [[x y]] {:x (+ rx (* (dec x) aw)) :y (+ ry (- rh (* y ah)))}))
        size (Dimension. width height)]
    (->> idxs
         (map #(let [addr (f %)]
                 (assoc addr
                   :use? nil
                   :x (+ 2 (:x addr))
                   :y (+ 2 (:y addr))
                   :size size
                   :alert (create-alert parent size (+ 2 (:x addr)) (+ 2 (:y addr)) opacity shape))))
         vec)))

(defn- seek-open-plat-backward [plats]
  (or (->> (map-indexed vector plats) reverse (filter (fn [[i plat]] (when-not (:use? plat)))) last)
      [nil nil]))

(defn- seek-open-plat-forward [plats]
  (or (->> plats (keep-indexed (fn [i plat] (when-not (:use? plat) [i plat]))) first)
      [nil nil]))

(defn- abs [n]
  {:pre [(number? n)]}
  (if (neg? n) (* -1 n) n))

(defn- open-plat [plats last-plat-idx interval]
  (let [[ai aplat] (seek-open-plat-backward plats)
        [bi bplat] (seek-open-plat-forward plats)]
    ;; どちらの区画を採用するかは最近使用区画や使用時刻で決める
    (cond
     (and ai bi) (cond
                  (= ai bi) [ai aplat]
                  (> 2000 interval) (if (< (abs (- ai last-plat-idx))
                                           (abs (- bi last-plat-idx)))
                                      [ai aplat] [bi bplat])
                  :else (if (> 5 (abs (- ai bi))) [ai aplat] [bi bplat]))
     (and ai (nil? bi)) [ai aplat]
     (and (nil? ai) bi) [bi bplat]
     (and (nil? ai) (nil? bi)) [nil nil])))

(let [alert-ch (atom nil)
      alert-go (atom nil)]
  (defn init-alert
    "Initialize alerter.
     Returns a vector consists of control channel and alert go-routine, and keep return values as current alerter.

     parent is a parent window which must be an instance of java.awt.Window.
     capacity is a size of alert buffer. (OPTIONAL)
        When alert requests are over capacity, oldest request will be dropped.
        If it is not specified, alert request is blocked until a plat to display alert is allocated.
     width is a width of alert.
     height is a height of alert.
     mode is orientation to display alert. (:rl-tb, :lr-tb, :rl-bt and :lr-bt)
     column is a columns number of displaying alert.
     interval is an interval for displaying between one alert and another. [msec]
     opacity is an opacity value of alert. opacity must be a float.
     shape is a shape of alert which must be an instance of java.awt.Shape."

    ([parent width height mode column interval opacity shape]
       (init-alert parent nil width height mode column interval opacity shape))

    ([parent capacity width height mode column interval opacity shape]
       {:pre [(not (nil? parent))
              (or (nil? capacity) (pos? capacity))
              (every? pos? [width height])
              (contains? #{:rl-tb :lr-tb :rl-bt :lr-bt} mode)
              (<= 0 column)
              (pos? interval)
              (float? opacity) (<= 0 opacity 1)
              (or (nil? shape) (instance? Shape shape))]}

       (let [CLOSE-EVENT (WindowEvent. parent WindowEvent/WINDOW_CLOSING)
             CLOSING-WAIT 500
             old-ch @alert-ch
             cch (if capacity (ca/chan (ca/sliding-buffer capacity)) (ca/chan))
             ar (ca/go-loop [plats (divide-plats parent width height mode column opacity shape)
                             last-plat-idx nil ;; 前回使った区画のインデックス
                             last-alerted (System/currentTimeMillis)]
                  ;; 空きがあれば受け付ける
                  (let [elapsed (- (System/currentTimeMillis) last-alerted)
                        waiting (->> plats (filter :use?) (map :use?))
                        zch (if (> interval elapsed) (ca/timeout (- interval elapsed)) cch)
                        [c ch] (ca/alts! (if (empty? (->> plats (remove :use?)))
                                           waiting
                                           (conj waiting zch)))]
                    (if c
                      (condp = (:cmd c)
                        :alert (let [{:keys [content duration]} c
                                     [i plat] (open-plat plats last-plat-idx elapsed)
                                     alert (:alert plat)
                                     {:keys [size x y]} plat
                                     pch (ca/go
                                           (EventQueue/invokeAndWait
                                            (fn []
                                              (.add alert content)
                                              (.setVisible alert true)))
                                           (ca/<! (ca/timeout duration))
                                           (.dispatchEvent alert CLOSE-EVENT)
                                           (ca/<! (ca/timeout CLOSING-WAIT))
                                           (.dispose alert)
                                           (ca/<! (ca/timeout CLOSING-WAIT))
                                           {:cmd :release :plat-idx i})]
                                 (recur (assoc-in plats [i :use?] pch) i (System/currentTimeMillis)))
                        :release (let [i (:plat-idx c)]
                                   (recur (assoc-in plats [i :use?] nil) last-plat-idx last-alerted)))
                      (if (not= cch ch)
                        (recur plats last-plat-idx last-alerted)
                        (do
                          (log/trace "Closed alert control channel")
                          (loop [waiting waiting]
                            (when-not (empty? waiting)
                              (log/tracef "-- Waiting alert dialogs (%d) are closed..." (count waiting))
                              (let [[c ch] (ca/alts! waiting)]
                                (recur (remove #(= ch %) waiting)))))
                          (log/tracef "-- Releasing all plats")
                          (doseq [plat plats]
                            (.dispose (:alert plat)))
                          (log/trace "Closing alert has done")
                          )))))]

         (reset! alert-ch cch)
         (reset! alert-go ar)
         (when old-ch
           (log/debugf "Close the old-ch: %s" (pr-str old-ch))
           (ca/close! old-ch))
         [cch ar])))

  (defn alert
    "Request alert to current alerter.
     content is a content of alert.
     duration [msec]"
    [^Component content ^long duration]
    {:pre [(pos? duration)]}
    (when @alert-ch
      (ca/>!! @alert-ch {:cmd :alert :content content :duration duration})))

  (defn close-alert
    "Close current alerter."
    []
    (when @alert-ch
      (ca/close! @alert-ch))
    (when @alert-go
      (ca/<!! @alert-go))))
