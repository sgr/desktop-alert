;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  desktop-alert
  (:import [java.awt GraphicsEnvironment]
           [java.awt.event WindowEvent]
           [java.util Date Timer TimerTask]
           [java.util.concurrent BlockingQueue Future LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [javax.swing JDialog SwingUtilities WindowConstants]))

(def INTERVAL-DISPLAY 200) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)
(def TERMINATION-TIMEOUT 120) ; スレッドプールのシャットダウン待ちタイムアウト時間(秒)

(defn- abs [n] (when (number? n) (if (neg? n) (* -1 n) n)))

(defn- divide-plats
  "dlg-width:  ダイアログの幅
   dlg-height: ダイアログの高さ
   mode: アラートモード。←↓(:rl-tb) ←↑(:rl-bt) →↓(:lr-tb) →↑(:lr-bt)
   column: 列数。0が指定された場合は制限なし(画面全体を使って表示する)。"
  [dlg-width dlg-height mode column]
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ 5 dlg-width), ah (+ 5 dlg-height)
        rx (.x r) ry (.y r)
        rw (.width r), w (quot rw aw)
        rh (.height r), h (quot rh ah)
        addrs (for [x (range 1 (inc (if (= 0 column) w (min column w))))
                    y (range 1 (inc h))]
                [x y])
        f (condp = mode
            :rl-bt (fn [[x y]] {:used false :x (+ rx (- rw (* x aw))) :y (+ ry (- rh (* y ah)))})
            :rl-tb (fn [[x y]] {:used false :x (+ rx (- rw (* x aw))) :y (+ ry (* (dec y) ah))})
            :lr-tb (fn [[x y]] {:used false :x (+ rx (* (dec x) aw)) :y (+ ry (* (dec y) ah))})
            :lr-bt (fn [[x y]] {:used false :x (+ rx (* (dec x) aw)) :y (+ ry (- rh (* y ah)))}))]
    (vec (map f addrs))))

(defn max-columns
  "画面上に表示可能な最大列数を返す。
   dlg-width: ダイアログの幅"
  [dlg-width]
  (when (zero? dlg-width) (throw (IllegalArgumentException. "width and height must not be zero")))
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ 5 dlg-width)]
    (quot (.width r) aw)))

(defn ^ThreadPoolExecutor interval-executor [max-pool-size ^long interval ^TimeUnit unit ^BlockingQueue queue]
  (proxy [ThreadPoolExecutor] [0 max-pool-size 5 TimeUnit/SECONDS queue]
    (afterExecute
      [r e]
      (proxy-super afterExecute r e)
      (.sleep unit interval))))

(let [plats (ref nil)  ;; アラートダイアログの表示領域
      pool  (ref nil)
      timer (Timer. "Alert sweeper" true)
      last-plat-idx (ref nil) ;; 前回使った区画のインデックス
      last-modified (ref (.getTime (Date.)))]

  (defn shutdown-and-wait
    ([timeout]
       (when (and @pool (not (.isShutdown @pool)))
         (.shutdown @pool)
         (.awaitTermination @pool timeout TimeUnit/SECONDS)))
    ([]
       (shutdown-and-wait TERMINATION-TIMEOUT)))

  (defn init-alert [dlg-width dlg-height mode column]
    (when (some zero? [dlg-width dlg-height])
      (throw (IllegalArgumentException. "width and height must not be zero")))
    (when (nil? mode) (throw (IllegalArgumentException. "mode must not be nil")))
    (when (neg? column) (throw (IllegalArgumentException. "column must not be negative")))
    (shutdown-and-wait)
    (let [queue (LinkedBlockingQueue.)
          p (interval-executor 1 INTERVAL-DISPLAY TimeUnit/MILLISECONDS queue)]
      (dosync
       (ref-set pool p)
       (ref-set plats (divide-plats dlg-width dlg-height mode column)))))

  (defn- reserve-plat-aux [i]
    (if-not (:used (nth @plats i))
      [i (nth @plats i)]
      [nil nil]))

  (defn- reserve-plat-A
    "逆方向に走査する"
    []
    (if-let [i (some #(let [[i plat] %] (if (:used plat) i nil))
                     (reverse (map-indexed vector @plats)))]
      (if (< i (dec (count @plats)))
        (reserve-plat-aux (inc i))
        (if-let [i (some #(let [[i plat] %] (if-not (:used plat) i nil))
                         (map-indexed vector @plats))]
          (reserve-plat-aux i)
          [nil nil]))
      (reserve-plat-aux 0)))

  (defn- reserve-plat-B
    "順方向に走査する"
    []
    (let [iplat (some #(let [[i plat] %] (if-not (:used plat) [i plat] nil))
                      (map-indexed vector @plats))]
      (if iplat iplat [nil nil])))

  (defn- release-plat [i plat]
    (dosync (alter plats assoc i (assoc plat :used false))))

  (defn- display-alert [^JDialog dlg ^long duration]
    (loop [now (.getTime (Date.))]
      (let [[ai aplat] (reserve-plat-A)
            [bi bplat] (reserve-plat-B)
            ;; どちらから走査した区画を採用するか以下で決定している。
            [i plat] (cond
                      (and ai bi) (cond
                                   (= ai bi) [ai aplat]
                                   (> 2000 (- now @last-modified)) (if (< (abs (- ai @last-plat-idx))
                                                                          (abs (- bi @last-plat-idx)))
                                                                     [ai aplat] [bi bplat])
                                   :else (if (> 5 (abs (- ai bi))) [ai aplat] [bi bplat]))
                      (and ai (nil? bi)) [ai aplat]
                      (and (nil? ai) bi) [bi bplat]
                      (and (nil? ai) (nil? bi)) [nil nil])]
        (if i
          (do
            (dosync
             (alter plats assoc i (assoc plat :used true))
             (ref-set last-plat-idx i)
             (ref-set last-modified now))
            (SwingUtilities/invokeAndWait
             #(doto dlg
                (.setLocation (:x plat) (:y plat))
                (.setVisible true)))
            (.schedule timer
                       (proxy [TimerTask] []
                         (run []
                           (SwingUtilities/invokeAndWait
                            (fn []
                              (doto dlg
                                (.setVisible false)
                                (.dispatchEvent (WindowEvent. dlg WindowEvent/WINDOW_CLOSING))
                                (.dispose))
                              (release-plat i plat)))))
                       (* duration)))
          (do
            (.sleep TimeUnit/SECONDS 1)
            (recur (.getTime (Date.))))))))

  (defn alert
    "dlg: ダイアログ, duration: 表示時間（ミリ秒）"
    [^JDialog dlg ^long duration]
    (when-not @plats
      (let [d (.getPreferredSize dlg)]
        (init-alert (.width d) (.height d) :rl-bt 0)))
    (.setDefaultCloseOperation dlg WindowConstants/DISPOSE_ON_CLOSE)
    (.execute ^ThreadPoolExecutor @pool #(display-alert dlg duration))))
