// J1Sc - fork dla projektow newhope (FPGA-snake).
//
//   j1/      rdzen J1 + peryferia (pakiet j1sc), bez zaleznosci od plytek
//   boards/  top-levele upstreamu: IceBreaker (J1Ice), IcoBoard (J1Ico),
//            Nexys4/Nexys4 DDR (J1Nexys4X) + J1BoardSim (interaktywna symulacja)
//
// Samodzielnie:   sbt test,  sbt "boards/runMain j1sc.boards.J1Ice"
// Jako submodule: ProjectRef(file("cpu/j1"), "j1") w buildzie konsumenta.
//
// Wersje MUSZA byc te same co w fpga-hw-lib/sbt/HwBuild.scala (oba buildy
// laduja sie w jednej sesji sbt konsumenta; rozne scalaVersion = brak
// kompatybilnosci binarnej). Celowo bez zaleznosci od HwBuild, zeby fork
// budowal sie bez fpga-hw-lib.

val spinalVersion = "1.12.3"
val spinal = Seq(
  "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
  "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion,
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
)

ThisBuild / organization := "j1sc"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version      := "0.2-newhope"

// <blok>/hw/spinal/{main,test}, fork z katalogiem roboczym = katalog bloku
// (jak hwLayout + forkSettings w HwBuild): gen/ i simWorkspace/ laduja w <blok>/.
def hwProject(id : String) = Project(id, file(id)).settings(
  Compile / scalaSource       := baseDirectory.value / "hw" / "spinal" / "main",
  Test    / scalaSource       := baseDirectory.value / "hw" / "spinal" / "test",
  Compile / resourceDirectory := baseDirectory.value / "hw" / "spinal" / "resources",
  libraryDependencies ++= spinal,
  Test / fork                   := true,
  Test / baseDirectory          := baseDirectory.value,
  Test / parallelExecution      := false,
  Compile / run / fork          := true,
  Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,   // gen/ w korzeniu repo, jak w upstreamie
  // J1Config.forthBase szuka toolchain/forth/build/16bit/nuc.binary w $J1SCBASE
  // (dziala tez, gdy J1Sc jest submodule'em i sbt startuje z katalogu konsumenta)
  Test          / envVars += "J1SCBASE" -> (ThisBuild / baseDirectory).value.getAbsolutePath,
  Compile / run / envVars += "J1SCBASE" -> (ThisBuild / baseDirectory).value.getAbsolutePath,
  publish / skip := true
)

lazy val j1 = hwProject("j1")
  .settings(libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test)

lazy val boards = hwProject("boards")
  .dependsOn(j1)
  .settings(libraryDependencies ++= Seq("io.github.java-native" % "jssc" % "2.9.6",            // UART w J1BoardSim
                                         "org.scalatest" %% "scalatest" % "3.2.19" % Test))

// id inne niz "root" i "hwlib" konsumenta
lazy val j1sc = (project in file("."))
  .aggregate(j1, boards)
  .settings(publish / skip := true)
