package j1sc.boards

import j1sc._
import spinal.core._

// =====================================================================
//  Sam rdzen J1 (bez peryferiow) do porownan syntezy na roznych FPGA:
//    sbt "boards/runMain j1sc.boards.J1CoreVerilog [mul|mac] [dsp48a1]"
//    -> gen/src/verilog/J1.v (obraz Fortha wpisany w .v)
//  mul / mac: J1MulUnit jak w J1MimasV2.j1FromArgs; dsp48a1: prymityw
//  (tylko Spartan-6), domyslnie wersja behawioralna (dowolne FPGA).
// =====================================================================
object J1CoreVerilog {
  def main(args : Array[String]) : Unit = {
    val base = if (args.contains("mac")) J1Config.forth16MulMac
               else if (args.contains("mul")) J1Config.forth16Mul else J1Config.forth16
    val cfg  = base.copy(mulDsp48a1 = args.contains("dsp48a1"))
    println(s"[J1Sc] J1 core: multiply ${cfg.hasMul}, MAC ${cfg.hasMac}, DSP48A1 primitive ${cfg.mulDsp48a1}")
    val dir = "gen/src/verilog"
    SpinalConfig(defaultConfigForClockDomains = J1MimasV2.globalClockConfig, targetDirectory = dir)
      .generateVerilog(new J1(cfg))
    InlineReadmem(s"$dir/J1.v")
  }
}
