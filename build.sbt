organization := "com.ticketfly"

name := "async-zk-client"

version := "0.6.0-SNAPSHOT"

scalaVersion := "2.11.8"

resolvers += "TFly Release" at "http://build.ticketfly.com/artifactory/libs-release"

publishTo := {
  if ( version.value.trim.endsWith("SNAPSHOT") )
    Some("tfly-snaps"   at "http://build.ticketfly.com/artifactory/libs-snapshot-local")
  else
    Some("tfly-release" at "http://build.ticketfly.com/artifactory/libs-release-local")
}


credentials += Credentials(Path.userHome / ".artifactory" / ".credentials")

libraryDependencies ++= Seq(
  "org.apache.zookeeper"    % "zookeeper"         % "3.4.9"    exclude("org.slf4j", "slf4j-log4j12")
                                                               exclude("log4j",     "log4j"),
  "org.slf4j"               % "slf4j-simple"      % "1.7.23"  % Test,
  "org.scalatest"           %% "scalatest"        % "3.0.1"   % Test
)

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-encoding", "UTF-8", // yes, this is 2 args
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Xlint:-missing-interpolator", // Turn off missing-interpolator because of slick sql strings
  "-Yno-adapted-args",
  "-Ywarn-dead-code", // N.B. doesn't work well with the ??? hole
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ywarn-inaccessible",
  "-Xfuture",
  "-language:existentials",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:implicitConversions"
)

// show elapsed time
testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
logBuffered in Test := false
