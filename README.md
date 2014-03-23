desktop-alert
=============

A Clojure library designed for your application to add desktop alert easily.

## Install

logutil is available in [Clojars.org](https://clojars.org/desktop-alert).
Your leiningen project.clj:

   [desktop-alert "0.5.0"]

## Usage

```clojure
  (def frm (JFrame.))
  (def sz (Dimension. 240 60))
  (def duration 1000)
  (def content (doto (JPanel.) (.add (JLabel. "Alert!"))))
  
  (init-alert frm (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil) ;; init alerter
  ;;(init-alert frm capacity (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil) ;; fixed capacity queue

  (alert content duration) ;; display alert during 1000 milliseconds. content is a java.awt.Component or subclass. 

  (close-alert) ;; close alerter.
```

```clojure
  (require '[clojure.core.async :as ca])
  (def frm (JFrame.))
  (def sz (Dimension. 240 60))
  (def duration 1000)
  (def content (doto (JPanel.) (.add (JLabel. "Alert!"))))
  
  (let [[ach ar] (init-alert frm (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil)] ;; init alerter
    (ca/>!! ach {:cmd :alert :content content :duration duration})
    (ca/close! ach) ;; close the alerter control channel.
    (ca/<!! ar))    ;; wait the alerter go routine is finished.
```


## License

Copyright (C) Shigeru Fujiwara All Rights Reserved.

Distributed under the Eclipse Public License, the same as Clojure.
