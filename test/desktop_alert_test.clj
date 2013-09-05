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


(defn- tiling [num duration mode column]
  (let [parent (JFrame.)
        da (DesktopAlerter. parent (.width DLG-SIZE) (.height DLG-SIZE) mode column 100 (float 0.8) nil)]
    (doseq [n (range 0 num)]
      (.alert da (apanel (format "Alert: %d" n)) duration))
    (.shutdownAndWait da)))

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
                       (.sleep TimeUnit/MILLISECONDS (+ duration INTERVAL-DISPLAY))))]
    (.start th1)
    (.start th2)
    (.join th1)
    (.join th2)))



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
      (tiling 54 duration :rl-bt 2))
    (testing "Tiling rl-tb"
      (tiling 64 duration :rl-tb 2))
    (testing "Tiling lr-tb"
      (tiling 64 duration :lr-tb 2))
    (testing "Tiling lr-bt"
      (tiling 64 duration :lr-bt 2))))

(deftest ^:rawclass fill-test
  (let [mcol (max-columns (.width DLG-SIZE))]
    (testing "Fill display"
      (tiling (* 20 mcol) 10000 :rl-bt 0)
      (tiling (* 20 mcol) 10000 :lr-tb mcol))))
