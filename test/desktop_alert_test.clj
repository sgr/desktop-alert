(ns desktop-alert-test
  (:require [clojure.test :refer :all]
            [desktop-alert :refer :all])
  (:import [java.awt Color Dimension]
           [java.util Date Random]
           [java.util.concurrent TimeUnit]
           [javax.swing BorderFactory JLabel JPanel]))

(def DLG-SIZE (Dimension. 270 60))
(def PANEL-SIZE (Dimension. 100 30))

(defn- apanel [^String s]
  (doto (JPanel.)
    (.setMaximumSize PANEL-SIZE)
    (.setBorder (BorderFactory/createLineBorder Color/YELLOW))
    (.setBackground Color/BLUE)
    (.add (doto (JLabel. s) (.setForeground Color/LIGHT_GRAY)))))

(defn- tiling [num duration mode column]
  (let [da (DesktopAlerter. (.width DLG-SIZE) (.height DLG-SIZE) mode column)]
    (doseq [n (range 0 num)]
      (.alert da (apanel (format "Alert: %d" n)) duration))
    (.shutdownAndWait da)))

(defn- tiling2 [num duration column]
  (let [rdm (Random. (.getTime (Date.)))
        th1 (Thread. (fn []
                       (doseq [mode [:rl-tb :lr-tb :rl-bt :lr-bt :rl-tb :lr-tb :rl-bt :lr-bt]]
                         (init-alert (.width DLG-SIZE) (.height DLG-SIZE) mode column)
                         (.sleep TimeUnit/SECONDS 10))))
        th2 (Thread. (fn []
                       (doseq [n (range 0 num)]
                         (let [wait-msec (inc (.nextInt rdm 3000))]
                           (.sleep TimeUnit/MILLISECONDS wait-msec)
                           (alert (apanel (format "Alert: %d,  %.2f" n (float (/ wait-msec 1000)))) duration)))
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
    (is (thrown? AssertionError (init-alert 0 10 :rl-tb 1)))
    (is (thrown? AssertionError (init-alert 10 0 :rl-tb 1)))
    (is (thrown? AssertionError (init-alert 10 10 nil 1)))
    (is (thrown? AssertionError (init-alert 10 10 :rl-tb -1)))
    (is (thrown? AssertionError (max-columns 0)))))

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
 

