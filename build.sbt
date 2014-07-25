import net.virtualvoid.sbt.graph.Plugin.graphSettings

organization := "com.github.bigtoast"

name := "async-zk-client"

version := "0.4.0.TFly-SNAPSHOT"

scalaVersion := "2.11.2"

scalacOptions ++= Seq("-feature", "-language:postfixOps")

resolvers += "TFly Release" at "http://build.ticketfly.com/artifactory/libs-release"

credentials += Credentials(Path.userHome / ".artifactory" / ".credentials")

libraryDependencies ++= Seq(
  "com.typesafe.akka"       %% "akka-actor"     % "2.3.4",
  "org.apache.zookeeper"    % "zookeeper"       % "3.4.6",
  "org.slf4j"               % "slf4j-simple"    % "1.7.7" % Test,
  "org.scalatest"           %% "scalatest"      % "2.2.0" % Test
)

graphSettings


publishTo := {
  if ( version.value.trim.endsWith("SNAPSHOT") )
    Some("tfly-snaps"   at "http://build.ticketfly.com/artifactory/libs-snapshot-local")
  else
    Some("tfly-release" at "http://build.ticketfly.com/artifactory/libs-release-local")
}

