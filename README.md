# How do Fibers work: a peek under the hood

Slides for my talk at Scala World Summit 2019. You can look at them online at https://systemfw.github.io/Scala-World-2019/#/ (use the spacebar to advance). The video for the talk is [here](https://www.youtube.com/watch?v=x5_MmZVLiSM).
The images in the slides were drawn as ASCII diagrams with http://asciiflow.com, and converted to cool "hand-written" PNGs with http://shaky.github.bushong.net



## Description

In this talk we will go on a journey in the implementation of the
concurrency model at the heart of cats-effect and fs2, starting from a
conceptual model of concurrency all the way down to the low level `IO`
interpreter.

