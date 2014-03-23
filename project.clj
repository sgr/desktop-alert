(defproject desktop-alert "0.5.1"
  :description "A Clojure library designed for your application to add desktop alert easily."
  :url "https://github.com/sgr/desktop-alert"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "[1.5,)"]
                 [org.clojure/tools.logging "[0.2,)"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]]
  :test-selectors {:default (complement (fn [m] (some m [:stress])))
                   :stress :stress
                   :interactive :interactive
                   :all (constantly true)}
  ;; Oracle's Java SE 7/8 for Mac OS X has a serious memory-leak bug.
  ;; <https://bugs.openjdk.java.net/browse/JDK-8029147>
  :java-cmd ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 ;;#"mac" "/Library/Java/JavaVirtualMachines/jdk1.7.0_45.jdk/Contents/Home/bin/java"
                 ;;#"mac" "/Library/Java/JavaVirtualMachines/jdk1.8.0.jdk/Contents/Home/bin/java"
                 #"mac" "/System/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home/bin/java"
                 (or (System/getenv "JAVA_CMD") "java")))
  :plugins [[codox "[0.6,)"]]
  :codox {:sources ["src"]
          :output-dir "doc"
          :src-dir-uri "https://github.com/sgr/desktop-alert/blob/master"
          :src-linenum-anchor-prefix "L"}
  )
