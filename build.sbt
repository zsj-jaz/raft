val scala3Version = "3.3.5"
val akkaVersion = "2.10.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "raft",
    version := "0.1.0-SNAPSHOT",
    fork := true,

    // Replace deprecated `in` syntax
    run / connectInput := true,

    // Workaround for https://github.com/lampepfl/dotty/issues/14846
    compileOrder := CompileOrder.JavaThenScala,
    scalaVersion := scala3Version,
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.8",
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.15" % Test
    ),

    // ✅ Java 17 preview support for switch pattern matching
    Compile / javacOptions ++= Seq("--release", "17", "--enable-preview"),
    Compile / run / javaOptions += "--enable-preview",
    Test / javacOptions ++= Seq("--release", "17", "--enable-preview"),
    Test / run / javaOptions += "--enable-preview"
  )
