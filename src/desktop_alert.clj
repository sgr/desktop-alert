;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  desktop-alert
  (:require [clojure.tools.logging :as log]
            [decorator :as deco])
  (:import [java.awt AWTEvent Component Dialog Dialog$ModalityType Dimension EventQueue GraphicsEnvironment Shape Window]
           [java.awt.event WindowAdapter]
           [java.util Date Timer TimerTask]
           [java.util.concurrent BlockingQueue Future ArrayBlockingQueue LinkedBlockingQueue
            ThreadPoolExecutor ThreadPoolExecutor$DiscardPolicy TimeUnit]))

(def KEEP-ALIVE-TIME-SEC 10)
(def DEFAULT-OPACITY (float 0.9))
(def DLG-MARGIN 5)

(def ^{:private true} INTERVAL-DISPLAY 150) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)

(defn max-columns
  "画面上に表示可能な最大列数を返す。
   dlg-width: ダイアログの幅"
  [dlg-width]
  {:pre [(pos? dlg-width)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ DLG-MARGIN dlg-width)]
    (quot (.width r) aw)))

(defn max-rows
  "画面上に表示可能な最大行数を返す。
   dlg-height: ダイアログの高さ"
  [dlg-height]
  {:pre [(pos? dlg-height)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        ah (+ DLG-MARGIN dlg-height)]
    (quot (.height r) ah)))

(defn max-plats
  "画面上に表示可能なダイアログの表示区画数を返す。
   dlg-width: ダイアログの幅
   dlg-height: ダイアログの高さ"
  [dlg-width dlg-height]
  {:pre [(pos? dlg-width)
         (pos? dlg-height)]}

  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ DLG-MARGIN dlg-width)
        ah (+ DLG-MARGIN dlg-height)]
    (* (quot (.width r) aw) (quot (.height r) ah))))

(defn ^ThreadPoolExecutor interval-executor [max-pool-size ^long interval ^TimeUnit unit ^BlockingQueue queue]
  (if (instance? ArrayBlockingQueue queue)
    (proxy [ThreadPoolExecutor] [0 max-pool-size KEEP-ALIVE-TIME-SEC TimeUnit/SECONDS queue (ThreadPoolExecutor$DiscardPolicy.)]
      (afterExecute [r e]
        (try
          (when e
            (log/warn e "failed execution."))
          (proxy-super afterExecute r e)
          (.sleep unit interval)
          (catch InterruptedException ex
            (log/warn ex "Interrputed.")))))
    (proxy [ThreadPoolExecutor] [0 max-pool-size KEEP-ALIVE-TIME-SEC TimeUnit/SECONDS queue]
      (afterExecute [r e]
        (try
          (when e
            (log/warn e "failed execution."))
          (proxy-super afterExecute r e)
          (.sleep unit interval)
          (catch InterruptedException ex
            (log/warn ex "Interrputed.")))))))

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
      (.setModalityType Dialog$ModalityType/MODELESS)
      (.setAlwaysOnTop true)
      (.setResizable false)
      (.setUndecorated true))))

(defn- divide-plats
  "parent: アラートダイアログの親ウィンドウ
   dlg-width:  ダイアログの幅
   dlg-height: ダイアログの高さ
   mode: アラートモード。←↓(:rl-tb) ←↑(:rl-bt) →↓(:lr-tb) →↑(:lr-bt)
   column: 列数。0が指定された場合は制限なし(画面全体を使って表示する)。"
  [parent dlg-width dlg-height mode column opacity shape]
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ DLG-MARGIN dlg-width), ah (+ DLG-MARGIN dlg-height)
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
                          (deco/set-opacity opacity)
                          (fn [dlg] (when shape (deco/set-shape dlg shape)))
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

(gen-class
 :name DesktopAlerter
 :exposes-methods {}
 :constructors {[java.awt.Window int int int clojure.lang.Keyword int int float java.awt.Shape] []
                [java.awt.Window int int clojure.lang.Keyword int int float java.awt.Shape] []}
 :state state
 :init init
 :methods [[displayAlert [java.awt.Component long] void]
           [alert [java.awt.Component long] void]
           [shutdown [] void]
           [shutdownAndWait [] void]]
 :prefix "da-")

(letfn [(initial-state [parent queue interval pool plats]
          {:parent parent
           :queue queue
           :interval interval
           :pool  pool
           :plats plats  ;; アラートダイアログの表示領域
           :last-modified (.getTime (Date.))
           :last-plat-idx nil ;; 前回使った区画のインデックス
           :rest-msec 0})]    ;; 残り時間
  (defn- da-init
    ([parent capacity dlg-width dlg-height mode column interval opacity shape]
       (let [queue (ArrayBlockingQueue. capacity)
             pool (interval-executor 1 interval TimeUnit/MILLISECONDS queue)
             plats (divide-plats parent dlg-width dlg-height mode column opacity shape)]
         [[] (atom (initial-state parent queue interval pool plats))]))
    ([parent dlg-width dlg-height mode column interval opacity shape]
       (let [queue (LinkedBlockingQueue.)
             pool (interval-executor 1 interval TimeUnit/MILLISECONDS queue)
             plats (divide-plats parent dlg-width dlg-height mode column opacity shape)]
         [[] (atom (initial-state parent queue interval pool plats))]))))

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
                      (and (nil? ai) (nil? bi)) [nil nil])
            sweep (fn [dlg]
                    (EventQueue/invokeAndWait #(.setVisible dlg false))
                    (swap! da-state assoc :rest-msec (- (:rest-msec @da-state) duration))
                    (swap! da-state assoc-in [:plats i :used] false)
                    (.purge (:pool @da-state))
                    (.removeAll dlg)
                    (try
                      (.dispose content)
                      (catch Exception _))
                    (.dispose dlg))]
        (if i
          (let [dlg (:dlg (nth plats i))]
            (try
              (.add dlg content)
              (swap! da-state assoc-in [:plats i] (assoc plat :used true))
              (swap! da-state assoc :last-plat-idx i :last-modified now)
              (EventQueue/invokeAndWait #(.setVisible dlg true))
              (.schedule timer
                         (proxy [TimerTask] []
                           (run [] (sweep dlg)))
                         duration)
              (catch InterruptedException e
                (log/warn e "Interrputed. Sweep dlg immediately!")
                (sweep dlg))))
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
      (log/info "DesktopAlerter shutting down immediately...")
      (.shutdownNow pool))))

(defn- da-shutdownAndWait [^DesktopAlerter this]
  (let [pool (:pool @(.state this))
        interval (:interval @(.state this))
        queue (:queue @(.state this))]
    (when (and pool (not (.isShutdown pool)))
      (.shutdown pool)
      (.awaitTermination pool
                         (+ (* interval (count queue))
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
    ([parent capacity dlg-width dlg-height mode column interval opacity shape]
       {:pre [(not (nil? parent))
              (pos? capacity)
              (every? pos? [dlg-width dlg-height])
              (contains? #{:rl-tb :lr-tb :rl-bt :lr-bt} mode)
              (pos? column)
              (pos? interval)
              (and (float? opacity) (< 0 opacity) (<= opacity 1))
              (or (nil? shape) (instance? Shape shape))]}

       (let [old-alerter @alerter]
         (reset! alerter (DesktopAlerter. parent capacity dlg-width dlg-height mode column interval opacity shape))
         (when old-alerter (.shutdown old-alerter))))
    ([parent dlg-width dlg-height mode column interval opacity shape] ;; キューは可変長だがヒープを尽くす可能性あり
       {:pre [(not (nil? parent))
              (every? pos? [dlg-width dlg-height])
              (contains? #{:rl-tb :lr-tb :rl-bt :lr-bt} mode)
              (pos? column)
              (pos? interval)
              (and (float? opacity) (< 0 opacity) (<= opacity 1))
              (or (nil? shape) (instance? Shape shape))]}

       (let [old-alerter @alerter]
         (reset! alerter (DesktopAlerter. parent dlg-width dlg-height mode column interval opacity shape))
         (when old-alerter (.shutdown old-alerter)))))

  (defn alert
    "dlg: ダイアログ, duration: 表示時間（ミリ秒）"
    [^Component content ^long duration]
    {:pre [(pos? duration)]}
    (when @alerter (.alert @alerter content duration))))
