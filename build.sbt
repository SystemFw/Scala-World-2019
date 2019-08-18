scalaVersion := "2.12.8"
libraryDependencies ++= Seq("core","io").map(m => "co.fs2" %% s"fs2-$m" % "1.1.0-M1")
initialCommands := s"import Examples._"
scalafmtOnCompile := true
scalacOptions -= "-Xfatal-warnings"
