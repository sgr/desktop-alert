(ns desktop-alert-test
  (:require [clojure.test :refer :all]
            [desktop-alert :refer :all])
  (:import [java.awt Dimension]
           [java.util.concurrent TimeUnit]
           [javax.swing JDialog JLabel]))

(def DLG-SIZE (Dimension. 220 60))

(defn adlg [s size]
  (let [dlg (JDialog.)
        label (JLabel. s)]
    (doto (.getContentPane dlg)
      (.add label))
    (doto dlg
      (.setDefaultCloseOperation JDialog/DISPOSE_ON_CLOSE)
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true)
      (.setPreferredSize size)
      (.setMinimumSize size)
      (.setUndecorated true))))

(defn tiling [num duration mode column]
  (init-alert (.width DLG-SIZE) (.height DLG-SIZE) mode column)
  (doseq [n (range 0 num)]
    (alert (adlg (format "Alert: %d" n) DLG-SIZE) duration))
  (shutdown-and-wait (* (inc num) (+ INTERVAL-DISPLAY duration)))
  (.sleep TimeUnit/MILLISECONDS duration))

(deftest arg-test
  (testing "illegal argument"
    (is (thrown? IllegalArgumentException (init-alert 0 10 :rl-tb 1)))
    (is (thrown? IllegalArgumentException (init-alert 10 0 :rl-tb 1)))
    (is (thrown? IllegalArgumentException (init-alert 10 10 nil 1)))
    (is (thrown? IllegalArgumentException (init-alert 10 10 :rl-tb -1)))
    (is (thrown? IllegalArgumentException (max-columns 0)))))

(deftest col-test
  (let [duration 10000]
    (testing "Tiling rl-bt"
      (tiling 64 duration :rl-bt 2))
    (testing "Tiling rl-tb"
      (tiling 64 duration :rl-tb 2))
    (testing "Tiling lr-tb"
      (tiling 64 duration :lr-tb 2))
    (testing "Tiling lr-bt"
      (tiling 64 duration :lr-bt 2))))

(deftest fill-test
  (let [mcol (max-columns (.width DLG-SIZE))]
    (testing "Fill display"
      (tiling (* 20 mcol) 10000 :rl-bt 0)
      (tiling (* 20 mcol) 10000 :lr-tb mcol))))
