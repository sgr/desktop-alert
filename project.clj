(defproject desktop-alert "0.3.0"
  :description "A Clojure library designed for your application to add desktop alert easily."
  :url "https://github.com/sgr/desktop-alert"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "[1.3,)"]
                 [org.clojure/tools.logging "0.2.6"]]
  :test-selectors {:rawclass :rawclass
                   :api :api
                   :all (constantly true)}
  ;; In Mac, Java SE 7u25 consume memory to excess.
  :java-cmd ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" "/System/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home/bin/java"
                 (or (System/getenv "JAVA_CMD") "java")))
  :aot :all
  :omit-source true)
