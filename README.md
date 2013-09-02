desktop-alert
=============

A Clojure library designed for your application to add desktop alert easily.

## Install

logutil is available in [Clojars.org](https://clojars.org/desktop-alert).
Your leiningen project.clj:

   [desktop-alert "0.3.0"]

## Usage

```clojure
  (def frm (JFrame.))
  
  (init-alert frm (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil) ;; call initialize function once
  (alert dlg 10000) ;; display alert dialog during 1000 milliseconds. dlg is a JDialog or subclass. 

  (max-columns (.getWidth sz)) ; -> alert dialog can display within 1 to max columns.
```

## License

Copyright (C) Shigeru Fujiwara All Rights Reserved.

Distributed under the Eclipse Public License, the same as Clojure.
