;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  desktop-alert
  (:require [decorator :as deco])
  (:import [java.awt AWTEvent Component Dialog Dimension EventQueue GraphicsEnvironment Shape Window]
           [java.awt.event WindowAdapter]
           [java.util Date Timer TimerTask]
           [java.util.concurrent BlockingQueue Future LinkedBlockingQueue ThreadPoolExecutor TimeUnit]))

(def INTERVAL-DISPLAY 150) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)
(def DEFAULT-OPACITY (float 0.9))

(defn max-columns
  "画面上に表示可能な最大列数を返す。
   dlg-width: ダイアログの幅"
  [dlg-width]
  {:pre [(pos? dlg-width)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ 5 dlg-width)]
    (quot (.width r) aw)))

(defn ^ThreadPoolExecutor interval-executor [max-pool-size ^long interval ^TimeUnit unit ^BlockingQueue queue]
  (proxy [ThreadPoolExecutor] [0 max-pool-size 5 TimeUnit/SECONDS queue]
    (afterExecute
      [r e]
      (proxy-super afterExecute r e)
      (.sleep unit  interval))))

(defn- abs [n]
  {:pre [(number? n)]}
  (if (neg? n) (* -1 n) n))

(defn- create-dialog [parent]
  (let [dlg (Dialog. parent)]
    (doto dlg
      (.addWindowListener
       (proxy [WindowAdapter] []
         (windowClosing [_]
           (if (EventQueue/isDispatchThread)
             (do (.setVisible dlg false)
                 (.dispose dlg))
             (EventQueue/invokeLater
              #(do (.setVisible dlg false)
                   (.dispose dlg)))))))
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true)
      (.setResizable false)
      (.setUndecorated true))))

(defn- divide-plats
  "parent: アラートダイアログの親ウィンドウ
   dlg-width:  ダイアログの幅
   dlg-height: ダイアログの高さ
   mode: アラートモード。←↓(:rl-tb) ←↑(:rl-bt) →↓(:lr-tb) →↑(:lr-bt)
   column: 列数。0が指定された場合は制限なし(画面全体を使って表示する)。"
  [parent dlg-width dlg-height mode column]
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ 5 dlg-width), ah (+ 5 dlg-height)
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
        size (Dimension. dlg-width dlg-height)]
    (vec (map #(let [addr (f %)]
                 (assoc addr
                   :used false
                   :dlg (doto (create-dialog parent)
                          (.setPreferredSize size)
                          (.setMinimumSize size)
                          (.setLocation (+ 2 (:x addr)) (+ 2 (:y addr))))))
              idxs))))

(defn- reserve-plat-A
  "platsから空きplatを逆方向に走査する"
  [plats]
  (letfn [(reserve-plat-aux [plats i]
            (if-not (:used (nth plats i))
              [i (nth plats i)]
              [nil nil]))]
    (if-let [i (some #(let [[i plat] %] (if (:used plat) i nil))
                     (reverse (map-indexed vector plats)))]
      (if (< i (dec (count plats)))
        (reserve-plat-aux plats (inc i))
        (if-let [i (some #(let [[i plat] %] (if-not (:used plat) i nil))
                         (map-indexed vector plats))]
          (reserve-plat-aux plats i)
          [nil nil]))
      (reserve-plat-aux plats 0))))

(defn- reserve-plat-B
  "platsから空きplatを順方向に走査する"
  [plats]
  (let [iplat (some #(let [[i plat] %] (if-not (:used plat) [i plat] nil))
                    (map-indexed vector plats))]
    (if iplat iplat [nil nil])))

(defn- show [dlg opacity shape]
  (if shape
    (doto dlg
      (deco/set-opacity opacity)
      (deco/set-shape shape)
      (.setVisible true))
    (doto dlg
      (deco/set-opacity opacity)
      (.setVisible true))))

(defn- hide [dlg]
  (.setVisible dlg false))

(gen-class
 :name DesktopAlerter
 :exposes-methods {}
 :constructors {[java.awt.Window int int clojure.lang.Keyword int int float java.awt.Shape] []}
 :state state
 :init init
 :methods [[displayAlert [java.awt.Component long] void]
           [alert [java.awt.Component long] void]
           [shutdown [] void]
           [shutdownAndWait [] void]]
 :prefix "da-")

(defn- da-init [parent dlg-width dlg-height mode column interval opacity shape]
  (let [queue (LinkedBlockingQueue.)
        pool (interval-executor 1 interval TimeUnit/MILLISECONDS queue)
        plats (divide-plats parent dlg-width dlg-height mode column)]
    [[] (atom {:parent parent
               :opacity opacity
               :shape shape
               :queue queue
               :pool  pool
               :plats plats  ;; アラートダイアログの表示領域
               :last-modified (.getTime (Date.))
               :last-plat-idx nil ;; 前回使った区画のインデックス
               :rest-msec 0})])) ;; 残り時間

(let [timer (Timer. "Alert sweeper" true)]
  (defn- da-displayAlert [^DesktopAlerter this ^Component content ^long duration]
    (loop [now (.getTime (Date.))]
      (let [da-state (.state this)
            plats (:plats @da-state)
            last-plat-idx (:last-plat-idx @da-state)
            last-modified (:last-modified @da-state)
            [ai aplat] (reserve-plat-A plats)
            [bi bplat] (reserve-plat-B plats)
            ;; どちらから走査した区画を採用するか以下で決定している。
            [i plat] (cond
                      (and ai bi) (cond
                                   (= ai bi) [ai aplat]
                                   (> 2000 (- now last-modified)) (if (< (abs (- ai last-plat-idx))
                                                                         (abs (- bi last-plat-idx)))
                                                                    [ai aplat] [bi bplat])
                                   :else (if (> 5 (abs (- ai bi))) [ai aplat] [bi bplat]))
                      (and ai (nil? bi)) [ai aplat]
                      (and (nil? ai) bi) [bi bplat]
                      (and (nil? ai) (nil? bi)) [nil nil])]
        (if i
          (let [dlg (:dlg (nth plats i))]
            (swap! da-state assoc-in [:plats i] (assoc plat :used true))
            (swap! da-state assoc :last-plat-idx i :last-modified now)
            (.add dlg content)
            (EventQueue/invokeAndWait #(show dlg (:opacity @da-state) (:shape @da-state)))
            (.schedule timer
                       (proxy [TimerTask] []
                         (run []
                           (try
                             (EventQueue/invokeAndWait #(hide dlg))
                             (swap! da-state assoc :rest-msec (- (:rest-msec @da-state) duration))
                             (swap! da-state assoc-in [:plats i :used] false)
                             (.purge (:pool @da-state))
                             (.removeAll dlg)
                             (try
                               (.dispose content)
                               (catch Exception _))
                             (.dispose dlg)
                             (catch Exception _))))
                       duration))
          (do
            (.sleep TimeUnit/SECONDS 1)
            (recur (.getTime (Date.)))))))))

(defn- da-alert [^DesktopAlerter this ^Component content ^long duration]
  (swap! (.state this) assoc :rest-msec (+ duration (:rest-msec @(.state this))))
  (.execute ^ThreadPoolExecutor (:pool @(.state this))
            #(.displayAlert this content duration)))

(defn- da-shutdown [^DesktopAlerter this]
  (let [pool (:pool @(.state this))]
    (when (and pool (not (.isShutdown pool)))
      (.shutdown pool))))

(defn- da-shutdownAndWait [^DesktopAlerter this]
  (let [pool (:pool @(.state this))]
    (when (and pool (not (.isShutdown pool)))
      (.shutdown pool)
      (.awaitTermination pool
                         (+ (* INTERVAL-DISPLAY (count (:queue @(.state this))))
                            (* 2 (:rest-msec @(.state this))))
                         TimeUnit/MILLISECONDS))))


(let [alerter (atom nil)]

  (defn init-alert
    "initialize alerter.

     parent is a parent window which is an instance of java.awt.Window.
     dlg-width is a width of alert dialog.
     dlg-height is a height of alert dialog.
     mode is able to select with :rl-tb, :lr-tb, :rl-bt and :lr-bt.
     column is a columns number of displaying alert dialog.
     interval is an interval for displaying between one alert dialog and another. [msec]
     opacity is an opacity value of alert dialog. opacity must be a float.
     shape is a shape of alert dialog which is an instance of java.awt.Shape."
    [parent dlg-width dlg-height mode column interval opacity shape]
    {:pre [(not (nil? parent))
           (every? pos? [dlg-width dlg-height])
           (contains? #{:rl-tb :lr-tb :rl-bt :lr-bt} mode)
           (pos? column)
           (pos? interval)
           (and (float? opacity) (< 0 opacity) (<= opacity 1))
           (or (nil? shape) (instance? Shape shape))]}

    (let [old-alerter @alerter]
      (reset! alerter (DesktopAlerter. parent dlg-width dlg-height mode column interval opacity shape))
      (when old-alerter (.shutdown old-alerter))))

  (defn alert
    "dlg: ダイアログ, duration: 表示時間（ミリ秒）"
    [^Component content ^long duration]
    {:pre [(pos? duration)]}
    (when @alerter (.alert @alerter content duration))))
