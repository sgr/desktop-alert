;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  desktop-alert
  (:import [java.awt GraphicsEnvironment]
           [java.awt.event WindowEvent]
           [java.util Date Timer TimerTask]
           [java.util.concurrent BlockingQueue Future LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [javax.swing JDialog SwingUtilities WindowConstants]))

(def ^{:private true} INTERVAL-DISPLAY 200) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)

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

(defn ^ThreadPoolExecutor interval-executor [max-pool-size ^long interval ^TimeUnit unit ^BlockingQueue queue]
  (proxy [ThreadPoolExecutor] [1 max-pool-size 5 TimeUnit/SECONDS queue]
    (afterExecute
      [r e]
      (proxy-super afterExecute r e)
      (.sleep unit interval))))

(let [plats (atom nil)  ;; アラートダイアログの表示領域
      queue (LinkedBlockingQueue.)
      pool  (interval-executor 1 INTERVAL-DISPLAY TimeUnit/MILLISECONDS queue)
      timer (Timer. "Alert sweeper" true)
      last-plat-idx (ref nil) ;; 前回使った区画のインデックス
      last-modified (ref (.getTime (Date.)))]
  (defn init-alert [dlg-width dlg-height mode column]
    (reset! plats (divide-plats dlg-width dlg-height mode column)))
  (defn- reserve-plat-aux [i]
    (if-not (:used (nth @plats i))
      [i (nth @plats i)]
      [nil nil]))
  (defn- reserve-plat-A
    "左上から逆方向に走査する"
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
    "右下から順方向に走査する"
    []
    (let [iplat (some #(let [[i plat] %] (if-not (:used plat) [i plat] nil))
                      (map-indexed vector @plats))]
      (if iplat iplat [nil nil])))
  (defn- release-plat [i plat]
    (swap! plats assoc i (assoc plat :used false)))
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
            (swap! plats assoc i (assoc plat :used true))
            (dosync
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
                       (* duration 1000)))
          (do
            (.sleep TimeUnit/SECONDS 1)
            (recur (.getTime (Date.))))))))
  (defn alert
    "dlg: ダイアログ, duration: 表示時間（秒）"
    [^JDialog dlg ^long duration]
    (when-not @plats
      (let [d (.getPreferredSize dlg)]
        (init-alert (.width d) (.height d) :rl-bt 0)))
    (.setDefaultCloseOperation dlg WindowConstants/DISPOSE_ON_CLOSE)
    (.execute ^ThreadPoolExecutor pool #(display-alert dlg duration))))
