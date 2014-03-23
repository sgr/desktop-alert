;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr" :doc "Window decorator"}
  decorator
  (:require [clojure.tools.logging :as log])
  (:import [java.awt GraphicsEnvironment Shape Window]
           [java.awt.geom RoundRectangle2D$Double]))

(defn- decorate-aux-sun-java6 []
  (try
    (let [cAu (Class/forName "com.sun.awt.AWTUtilities")
          cWT (Class/forName "com.sun.awt.AWTUtilities$Translucency")
          mVO (.getMethod cWT "valueOf" (into-array Class [String]))
          TRANSLUCENT (.invoke mVO nil (to-array ["TRANSLUCENT"]))
          PERPIXEL_TRANSLUCENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSLUCENT"]))
          PERPIXEL_TRANSPARENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSPARENT"]))
          mIsTS (.getMethod cAu "isTranslucencySupported" (into-array Class [cWT]))
          supported-tl  (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [TRANSLUCENT])))
          supported-ptl (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [PERPIXEL_TRANSLUCENT])))
          supported-ptp (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [PERPIXEL_TRANSPARENT])))]
      (log/infof "This environment supports TRANSLUCENT: %s" supported-tl)
      (log/infof "This environment supports PERPIXCEL_TRANSLUCENT: %s" supported-ptl)
      (log/infof "This environment supports PERPIXCEL_TRANSPARENT: %s" supported-ptp)
      (let [cWindow (Class/forName "java.awt.Window")
            cShape (Class/forName "java.awt.Shape")
            mSetWO (.getMethod cAu "setWindowOpacity" (into-array Class [cWindow Float/TYPE]))
            mSetWS (.getMethod cAu "setWindowShape" (into-array Class [cWindow cShape]))]
        {:set-opacity (fn [^Window w opacity]
                        (try
                          (when supported-tl (.invoke mSetWO nil (to-array [w opacity])))
                          (catch Exception e
                            (log/warn e (format "failed invoking method [%s] with %s, %f"
                                                (pr-str mSetWO) (pr-str w) opacity)))))
         :set-shape (fn [^Window w ^Shape s]
                        (try
                          (when supported-ptp (.invoke mSetWS nil (to-array [w s])))
                          (catch Exception e
                            (log/warnf e "failed invoking method [%s] with %s %s"
                                       (pr-str mSetWS) (pr-str w) (pr-str s)))))}))
    (catch Exception e
      (log/warn e "This platform doesn't support AWTUtilities")
      {:set-opacity nil :set-shape nil})))

(defn- decorate-aux-java7 []
  (try
    (let [cWT (Class/forName "java.awt.GraphicsDevice$WindowTranslucency")
          mVO (.getMethod cWT "valueOf" (into-array Class [String]))
          TRANSLUCENT (.invoke mVO nil (to-array ["TRANSLUCENT"]))
          PERPIXEL_TRANSLUCENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSLUCENT"]))
          PERPIXEL_TRANSPARENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSPARENT"]))
          ge (GraphicsEnvironment/getLocalGraphicsEnvironment)
          gd (.getDefaultScreenDevice ge)
          supported-tl  (.isWindowTranslucencySupported gd TRANSLUCENT)
          supported-ptl (.isWindowTranslucencySupported gd PERPIXEL_TRANSLUCENT)
          supported-ptp (.isWindowTranslucencySupported gd PERPIXEL_TRANSPARENT)]
      (log/infof "This environment supports TRANSLUCENT: %s" supported-tl)
      (log/infof "This environment supports PERPIXCEL_TRANSLUCENT: %s" supported-ptl)
      (log/infof "This environment supports PERPIXCEL_TRANSPARENT: %s" supported-ptp)
      {:set-opacity (fn [^Window w opacity]
                      (try
                        (when supported-tl  (.setOpacity w opacity))
                        (catch Exception e
                          (log/warn e "failed invoking method[setOpacity]"))))
       :set-shape (fn [^Window w ^Shape s]
                      (try
                        (when supported-ptp (.setShape w s))
                        (catch Exception e
                          (log/warn e "failed invoking method[setShape]"))))})
    (catch Exception e
      (log/warn e "This platform doesn't support Java7 API")
      {:set-opacity nil :set-shape nil})))

(let [decorators (atom nil)]
  (letfn [(init-decorator []
            (let [v (System/getProperty "java.version")]
              (log/debug "java.version: " v)
              (condp #(.startsWith ^String %2 ^String %1) v
                "1.6" (do (log/info "use Sun Java SE6 Update 10 API (AWTUtilities)")
                          (reset! decorators (decorate-aux-sun-java6)))
                "1.7" (do (log/info "use Java 7 API")
                          (reset! decorators (decorate-aux-java7)))
                "1.8" (do (log/info "use Java 7 API")
                          (reset! decorators (decorate-aux-java7))))
              (when-not @decorators (reset! decorators
                                            {:set-opacity (fn [w opacity])
                                             :set-shape (fn [w shape])}))))]

    (defn set-opacity [^Window w opacity]
      (when-not @decorators (init-decorator))
      ((:set-opacity @decorators) w opacity))

    (defn set-shape [^Window w ^Shape shape]
      (when-not @decorators (init-decorator))
      ((:set-shape @decorators) w shape))))

(defn round-rect [rect arcw arch]
  (RoundRectangle2D$Double. 0 0 (.getWidth rect) (.getHeight rect) arcw arch))
