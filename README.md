desktop-alert
=============

A Clojure library designed for your application to add desktop alert easily.

## Install

logutil is available in [Clojars.org](https://clojars.org/desktop-alert).
Your leiningen project.clj:

   [desktop-alert "0.4.2"]

## Usage

```clojure
  (def frm (JFrame.))
  
  (init-alert frm (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil) ;; call initialize function once
  ;;(init-alert frm capacity (.getWidth sz) (.getHeight sz) :rl-bt 1 200 (float 0.9) nil) ;; fixed capacity queue
  (alert dlg 1000) ;; display alert dialog during 1000 milliseconds. dlg is a JDialog or subclass. 

  (max-columns (.getWidth sz)) ; -> returns max columns to display alert dialogs
  (max-rows (.getHeight sz))   ; -> returns max rows to display alert dialogs
  (max-plats (.getWidth sz) (.getHeight sz)) ; -> returns max plats to display alert dialogs
```

## License

Copyright (C) Shigeru Fujiwara All Rights Reserved.

Distributed under the Eclipse Public License, the same as Clojure.
