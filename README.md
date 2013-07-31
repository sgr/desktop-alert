desktop-alert
=============

A Clojure library designed for your application to add desktop alert easily.

# Usage

```clojure
  (init-alert (.getWidth sz) (.getHeight sz) :rl-bt 1) ;; call initialize function once
  (alert dlg 10) ;; display alert dialog during 10 seconds. dlg is a JDialog or subclass. 
```

# License

Copyright (C) Shigeru Fujiwara All Rights Reserved.

Distributed under the Eclipse Public License, the same as Clojure.
