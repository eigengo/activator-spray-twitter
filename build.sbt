name := "activator-spray-twitter"

version := "1.0"

scalaVersion := "2.10.2"

resolvers += "spray repo" at "http://repo.spray.io"

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "com.typesafe.akka"      %% "akka-actor"            % "2.2.3",
  "com.typesafe.akka"      %% "akka-slf4j"            % "2.2.3",
  "io.spray"                % "spray-can"             % "1.2.0",
  "io.spray"                % "spray-client"          % "1.2.0",
  "io.spray"                % "spray-routing"         % "1.2.0",
  "io.spray"               %% "spray-json"            % "1.2.5",
  "org.eigengo.monitor"     % "agent-akka"            % "0.2-SNAPSHOT",
  "org.eigengo.monitor"     % "output-statsd"         % "0.2-SNAPSHOT",
  "org.specs2"             %% "specs2"                % "2.2.2"        % "test",
  "io.spray"                % "spray-testkit"         % "1.2.0"        % "test",
  "com.typesafe.akka"      %% "akka-testkit"          % "2.2.3"        % "test"
)

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Ywarn-dead-code",
  "-language:_",
  "-target:jvm-1.7",
  "-encoding", "UTF-8"
)

parallelExecution in Test := false

javaOptions in run += "-javaagent:" + System.getProperty("user.home") + "/.ivy2/cache/org.aspectj/aspectjweaver/jars/aspectjweaver-1.7.3.jar"

fork in run := true

connectInput in run := true

outputStrategy in run := Some(StdoutOutput)
