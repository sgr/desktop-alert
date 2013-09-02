(defproject desktop-alert "0.3.0"
  :description "A Clojure library designed for your application to add desktop alert easily."
  :url "https://github.com/sgr/desktop-alert"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "[1.3,)"]]
  :test-selectors {:rawclass :rawclass
                   :api :api
                   :all (constantly true)}
  :aot :all
  :omit-source true)
