(ns desktop-alert-test
  (:require [clojure.test :refer :all]
            [desktop-alert :refer :all])
  (:import [java.awt Dimension]
           [java.util.concurrent TimeUnit]
           [javax.swing JDialog JLabel]))

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

(defn tiling [num duration wait mode column]
    (let [sz (Dimension. 220 60)]
      (init-alert (.width sz) (.height sz) mode column)
      (doseq [n (range 0 num)]
        (alert (adlg (format "Alert: %d" n) sz) duration))
      (.sleep TimeUnit/SECONDS wait)))

(deftest col-test
  (testing "Tiling rl-tb"
    (tiling 64 10 60 :rl-tb 2)))

(comment
(deftest rl-bt-test
  (testing "Tiling rl-bt"
    (tiling 256 20 80 :rl-bt 0)))

(deftest rl-tb-test
  (testing "Tiling rl-tb"
    (tiling 256 20 80 :rl-tb 0)))

(deftest lr-tb-test
  (testing "Tiling lr-tb"
    (tiling 256 20 80 :lr-tb 0)))

(deftest lr-bt-test
  (testing "Tiling lr-bt"
    (tiling 256 20 80 :lr-bt 0)))
)
