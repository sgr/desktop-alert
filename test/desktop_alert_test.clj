(ns desktop-alert-test
  (:require [clojure.test :refer :all]
            [desktop-alert :refer :all]
            [decorator :refer :all]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log])
  (:import [java.awt Color Dimension]
           [java.awt.event MouseAdapter WindowEvent]
           [java.util.concurrent TimeUnit]
           [javax.swing BorderFactory JFrame JLabel JPanel]))

(def ALERT-SIZE (Dimension. 270 60))
(def SHAPE (round-rect ALERT-SIZE 8 8))

(defn- apanel [^String s]
  (doto (JPanel.)
    (.setMaximumSize ALERT-SIZE)
    (.setBackground Color/BLUE)
    (.add (doto (JLabel. s) (.setForeground Color/LIGHT_GRAY)))))

(defn- bpanel [^String s]
  (let [p (apanel s)]
    (doto p
      (.addMouseListener (proxy [MouseAdapter] []
                           (mouseClicked [_]
                             (let [dlg (.getParent p)]
                               (when (and dlg (instance? java.awt.Window dlg))
                                 (log/infof "dlg is Window: %s" (pr-str dlg))
                                 (.dispatchEvent dlg (WindowEvent. dlg WindowEvent/WINDOW_CLOSING))
                                 (.removeMouseListener p this))))))
      (.setBackground Color/DARK_GRAY))))

(defn- cpanel [^String s]
  (doto (bpanel s)
    (.setBackground (Color. (rand-int 256) (rand-int 256) (rand-int 256)))))

(defn- tiling [num duration mode column wait]
  (let [parent (JFrame.)
        [ach ar] (init-alert parent (.width ALERT-SIZE) (.height ALERT-SIZE) mode column 100 (float 0.8) SHAPE)]
    (doseq [n (range 0 num)]
      (ca/>!! ach {:cmd :alert :content (apanel (format "Alert: %d" n)) :duration duration}))
    (ca/close! ach)
    (ca/<!! ar)))

(deftest arg-test
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
      (is (thrown? AssertionError (init-alert p -80 10 10 :rl-tb 1 1000 (float 0.5) nil)))
      (is (thrown? AssertionError (max-columns 0))))))

(deftest ^:interactive re-init-test
  (testing "re-init test"
    (let [parent (JFrame.)
          f1 (future
               (doall
                (map (fn [mode]
                       (let [[cch ar] (init-alert parent (.width ALERT-SIZE) (.height ALERT-SIZE)
                                                  mode 2 100 (float 0.8) SHAPE)]
                         (.sleep TimeUnit/SECONDS 10)
                         ar))
                     [:rl-tb :lr-tb :rl-bt :lr-bt :rl-tb :lr-tb :rl-bt :lr-bt])))
          f2 (future
               (doseq [n (range 300)]
                 (let [wait-msec (rand-int 500)
                       c (bpanel (format "Alert[%d]: %.2f" n (float (/ wait-msec 1000))))]
                   (.sleep TimeUnit/MILLISECONDS wait-msec)
                   (alert c 5000))))]
      (deref f2)
      (log/info "finished alert")
      (close-alert)
      (log/info "waiting to finish all alerter...")
      (loop [ars (deref f1)]
        (when-not (empty? ars)
          (let [[c ch] (ca/alts!! ars)]
            (recur (remove #(= ch %) ars))))))))

(deftest ^:interactive col-test
  (let [duration 1000]
    (testing "Tiling rl-bt"
      (tiling 64 duration :rl-bt 2 true))
    (testing "Tiling rl-tb"
      (tiling 64 duration :rl-tb 2 true))
    (testing "Tiling lr-tb"
      (tiling 64 duration :lr-tb 2 true))
    (testing "Tiling lr-bt"
      (tiling 64 duration :lr-bt 2 true))))

(deftest ^:interactive fill-test
  (let [mcol (max-columns (.width ALERT-SIZE))
        parent (JFrame.)
        num 300
        duration 10000]
    (testing "Fill display"
      (init-alert parent (.width ALERT-SIZE) (.height ALERT-SIZE) :rl-bt 0 100 (float 0.8) SHAPE)
      (doseq [c (->> (range num)
                     (map #(cpanel (format "Fill test (rl-bt): %d" %))))]
        (alert c duration))
      (init-alert parent (.width ALERT-SIZE) (.height ALERT-SIZE) :lr-tb mcol 100 (float 0.8) SHAPE)
      (doseq [c (->> (range num)
                     (map #(cpanel (format "Fill test (rl-bt): %d" %))))]
        (alert c duration))
      (close-alert))))

(deftest ^:interactive interruption-test
  (let [mcol (max-columns (.width ALERT-SIZE))]
    (testing "Interruption"
      (tiling 60 4000 :rl-bt 0 true)
      (tiling 60 4000 :lr-bt 0 false)
      (tiling 60 4000 :rl-tb 0 true)
      (tiling 60 4000 :lr-tb 0 false)
      (tiling (* 20 mcol) 10000 :lr-tb mcol true))))

(deftest ^:interactive saturation-test
  (testing "saturation"
    (let [parent (JFrame.)
          [ach ar] (init-alert parent 200 (.width ALERT-SIZE) (.height ALERT-SIZE)
                               :rl-bt 1 100 (float 0.8) SHAPE)]
      (doseq [c (->> (range 10000)
                     (map #(apanel (format "Saturation test (%d)" %))))]
        (alert c 1000))
      (close-alert)
      (ca/<!! ar))))

(deftest ^:stress stress-test
  (testing "stress test"
    (let [duration 2000
          interval 100
          columns  1
          rows (max-rows (.height ALERT-SIZE))
          mode :rl-bt
          parent (JFrame.)
          panels (for [i (range (* 3 rows))] (cpanel (format "Stress test (%d)" i)))
          [ach ar] (init-alert parent (.width ALERT-SIZE) (.height ALERT-SIZE)
                               mode columns interval (float 0.6) SHAPE)]
      (loop []
        (doseq [i (range rows)]
          (ca/>!! ach {:cmd :alert :content (nth panels i) :duration duration}))
        (recur))
      )))
