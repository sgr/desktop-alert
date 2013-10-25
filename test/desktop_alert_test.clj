(ns desktop-alert-test
  (:require [clojure.test :refer :all]
            [desktop-alert :refer :all]
            [clojure.tools.logging :as log])
  (:import [java.awt Color Dimension]
           [java.awt.event MouseEvent MouseListener WindowEvent]
           [java.util Date Random]
           [java.util.concurrent TimeUnit]
           [javax.swing BorderFactory JFrame JLabel JPanel]))

(def DLG-SIZE (Dimension. 270 60))
(def PANEL-SIZE (Dimension. 100 30))

(defn- apanel [^String s]
  (doto (JPanel.)
    (.setMaximumSize PANEL-SIZE)
    (.setBorder (BorderFactory/createLineBorder Color/YELLOW))
    (.setBackground Color/BLUE)
    (.add (doto (JLabel. s) (.setForeground Color/LIGHT_GRAY)))))

(defn- bpanel [^String s]
  (let [p (apanel s)]
    (doto p
      (.addMouseListener (proxy [MouseListener] []
                           (mouseClicked [_]
                             (let [dlg (.getParent p)]
                               (when (and dlg (instance? java.awt.Window dlg))
                                 (log/info (format "dlg is Window: %s" (pr-str dlg)))
                                 (.dispatchEvent dlg (WindowEvent. dlg WindowEvent/WINDOW_CLOSING))
                                 (.removeMouseListener p this))))
                           (mouseEntered [_])
                           (mouseExited [_])
                           (mousePressed [_])
                           (mouseReleased [_])))
      (.setBackground Color/DARK_GRAY))))


(defn- tiling [num duration mode column wait]
  (let [parent (JFrame.)
        da (DesktopAlerter. parent (.width DLG-SIZE) (.height DLG-SIZE) mode column 100 (float 0.8) nil)]
    (doseq [n (range 0 num)]
      (.alert da (apanel (format "Alert: %d" n)) duration))
    (if wait
      (.shutdownAndWait da)
      (do
        (.sleep TimeUnit/SECONDS 5)
        (.shutdown da)))))

(defn- tiling2 [num duration column]
  (let [parent (JFrame.)
        rdm (Random. (.getTime (Date.)))
        th1 (Thread. (fn []
                       (doseq [mode [:rl-tb :lr-tb :rl-bt :lr-bt :rl-tb :lr-tb :rl-bt :lr-bt]]
                         (init-alert parent (.width DLG-SIZE) (.height DLG-SIZE) mode column 100 (float 0.9) nil)
                         (.sleep TimeUnit/SECONDS 10))))
        th2 (Thread. (fn []
                       (doseq [n (range 0 num)]
                         (let [wait-msec (inc (.nextInt rdm 3000))]
                           (.sleep TimeUnit/MILLISECONDS wait-msec)
                           (alert (bpanel (format "Alert: %d,  %.2f" n (float (/ wait-msec 1000)))) duration)))
                       (.sleep TimeUnit/MILLISECONDS (* 2 duration))))]
    (.start th1)
    (.start th2)
    (.join th1)
    (.join th2)))

(defn- tiling3 [num duration mode column wait capacity]
  (let [parent (JFrame.)
        da (DesktopAlerter. parent capacity (.width DLG-SIZE) (.height DLG-SIZE) mode column 100 (float 0.8) nil)]
    (doseq [n (range 0 num)]
      (.alert da (apanel (format "Alert3: %d" n)) duration))
    (if wait
      (.shutdownAndWait da)
      (do
        (.sleep TimeUnit/SECONDS 5)
        (.shutdown da)))))

(comment
(deftest memory-leak-test
  (testing "memory leak"
    (let [duration 1000
          capacity 100
          interval 100
          columns  1
          rows (max-rows (.height DLG-SIZE))
          mode :rl-bt
          parent (JFrame.)
          panels (for [i (range 0 rows)] (apanel (format "Alert(%d)" i)))
          da (DesktopAlerter. parent capacity (.width DLG-SIZE) (.height DLG-SIZE) mode columns interval (float 0.6) nil)]
      (loop [n 0]
        ;;(dotimes [i rows] (.alert da (nth panels i) duration))
        (doseq [i (range 0 rows)] (.alert da (apanel (format "Alert4: %d - %d" n i)) duration))
        (.sleep TimeUnit/MILLISECONDS (+ duration (* rows interval)))
        (recur (inc n)))))) ;; infinite loop
)

(deftest ^:api re-init-test
  (testing "re-init"
    (tiling2 64 5000 2)))

(deftest ^:api arg-test
  (testing "illegal argument"
    (let [p (JFrame.)]
      (is (thrown? AssertionError (init-alert p 0 10 :rl-tb 1 100 (float 0.9) nil)))
      (is (thrown? AssertionError (init-alert p 10 0 :rl-tb 1 100 (float 0.9) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 nil 1 100 (float 0.9) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb -1 100 (float 0.9) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb 1 0 (float 0.9) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb 1 100 9 nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb 1 1000 (float 1.1) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb 1 1000 (float -0.2) nil)))
      (is (thrown? AssertionError (init-alert p 10 10 :rl-tb 1 1000 (float 0.5) 1)))
      (is (thrown? AssertionError (max-columns 0))))))

(deftest ^:rawclass col-test
  (let [duration 1000]
    (testing "Tiling rl-bt"
      (tiling 54 duration :rl-bt 2 true))
    (testing "Tiling rl-tb"
      (tiling 64 duration :rl-tb 2 true))
    (testing "Tiling lr-tb"
      (tiling 64 duration :lr-tb 2 true))
    (testing "Tiling lr-bt"
      (tiling 64 duration :lr-bt 2 true))))

(deftest ^:rawclass fill-test
  (let [mcol (max-columns (.width DLG-SIZE))]
    (testing "Fill display"
      (tiling (* 20 mcol) 10000 :rl-bt 0 true)
      (tiling (* 20 mcol) 10000 :lr-tb mcol true))))

(deftest ^:rawclass interruption-test
  (let [mcol (max-columns (.width DLG-SIZE))]
    (testing "Interruption"
      (tiling 60 4000 :rl-bt 0 true)
      (tiling 60 4000 :lr-bt 0 false)
      (tiling 60 4000 :rl-tb 0 true)
      (tiling 60 4000 :lr-tb 0 false)
      (tiling (* 20 mcol) 10000 :lr-tb mcol true))))

(deftest saturation-test
  (testing "saturation"
    (tiling3 1000 1000 :rl-bt 1 true 100)))

