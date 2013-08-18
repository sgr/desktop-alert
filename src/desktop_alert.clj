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

(gen-class
 :name DesktopAlerter
 :exposes-methods {}
 :constructors {[int int clojure.lang.Keyword int] []}
 :state state
 :init init
 :methods [[displayAlert [javax.swing.JDialog long] void]
           [alert [javax.swing.JDialog long] void]
           [shutdown [] void]
           [shutdownAndWait [] void]]
 :prefix "da-")

(defn- da-init [dlg-width dlg-height mode column]
  (let [queue (LinkedBlockingQueue.)
        pool (interval-executor 1 INTERVAL-DISPLAY TimeUnit/MILLISECONDS queue)
        plats (divide-plats dlg-width dlg-height mode column)]
    [[] (atom {:queue queue
               :pool  pool
               :plats plats  ;; アラートダイアログの表示領域
               :last-modified (.getTime (Date.))
               :last-plat-idx nil ;; 前回使った区画のインデックス
               :rest-msec 0})])) ;; 残り時間

(let [timer (Timer. "Alert sweeper" true)]
  (defn- da-displayAlert [^DesktopAlerter this ^JDialog dlg ^long duration]
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
          (do
            (swap! da-state assoc-in [:plats i] (assoc plat :used true))
            (swap! da-state assoc :last-plat-idx i :last-modified now)
            (SwingUtilities/invokeAndWait
             #(doto dlg
                (.setLocation (+ 2 (:x plat)) (+ 2 (:y plat)))
                (.setVisible true)))
            (.schedule timer
                       (proxy [TimerTask] []
                         (run []
                           (SwingUtilities/invokeAndWait
                            (fn []
                              (try
                                (doto dlg
                                  (.setVisible false)
                                  (.dispatchEvent (WindowEvent. dlg WindowEvent/WINDOW_CLOSING))
                                  (.dispose))
                                (swap! da-state assoc :rest-msec (- (:rest-msec @da-state) duration))
                                (swap! da-state assoc-in [:plats i :used] false)
                                (catch Exception _))))))
                       duration))
          (do
            (.sleep TimeUnit/SECONDS 1)
            (recur (.getTime (Date.)))))))))

(defn- da-alert [^DesktopAlerter this ^JDialog dlg ^long duration]
  (.setDefaultCloseOperation dlg WindowConstants/DISPOSE_ON_CLOSE)
  (swap! (.state this) assoc :rest-msec (+ duration (:rest-msec @(.state this))))
  (.execute ^ThreadPoolExecutor (:pool @(.state this))
            #(.displayAlert this dlg duration)))

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

  (defn init-alert [dlg-width dlg-height mode column]
    (when (some zero? [dlg-width dlg-height])
      (throw (IllegalArgumentException. "width and height must not be zero")))
    (when (nil? mode) (throw (IllegalArgumentException. "mode must not be nil")))
    (when (neg? column) (throw (IllegalArgumentException. "column must not be negative")))
    (let [old-alerter @alerter]
      (reset! alerter (DesktopAlerter. dlg-width dlg-height mode column))
      (when old-alerter (.shutdown old-alerter))))

  (defn alert
    "dlg: ダイアログ, duration: 表示時間（ミリ秒）"
    [^JDialog dlg ^long duration]
    (when @alerter (.alert @alerter dlg duration))))
