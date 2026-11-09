package j1sc.boards

import j1sc._
import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

// =====================================================================
//  End-to-end: J1MimasV2Soc with the real SwapForth nucleus (forth16),
//  commands typed over the UART, answers decoded from tx. The nucleus
//  prints in hex (.x); decimal . comes with swapforth.fs from the host.
//  Faster UART than on the board (625 kbaud = 10 cycles per sample at
//  50 MHz) only to keep the simulation short; everything else as built.
//  Needs toolchain/forth/build/16bit/nuc.binary (cd toolchain/forth && make).
// =====================================================================
class J1MimasV2SocTest extends AnyFunSuite {
  val freq   = 50e6
  val baud   = 625000
  val bitCyc = (freq / baud).toInt

  def compileSoc(name : String, j1 : => J1Config) = SimConfig
    .withConfig(SpinalConfig(defaultConfigForClockDomains = J1MimasV2.globalClockConfig,
                             defaultClockDomainFrequency  = FixedFrequency(50 MHz)))
    .withVerilator.workspacePath("simWorkspace").workspaceName(name)
    .compile(new J1MimasV2Soc(j1, CoreConfig.mimasV2(baud)))
  lazy val compiled    = compileSoc("j1mimasv2_soc", J1Config.forth16)
  lazy val compiledMul = compileSoc("j1mimasv2_soc_mul", J1Config.forth16Mul)    // mnozenie na DSP, nuc-mul.binary

  /** Starts the SoC, returns (send line, received text so far). */
  def session(dips : Int, sim : => SimCompiled[J1MimasV2Soc] = compiled)(body : (J1MimasV2Soc, String => Unit, () => String) => Unit) : Unit =
    sim.doSim { d =>
      val cd = d.clockDomain
      d.io.dips    #= dips
      d.io.buttons #= 0
      d.io.extInt  #= 0
      d.io.uart.rxd #= true
      cd.forkStimulus(20)
      cd.assertReset(); cd.waitSampling(10); cd.deassertReset()

      val rx = new StringBuilder
      fork {                                    // UART receiver on tx
        while (true) {
          cd.waitSamplingWhere(!d.io.uart.txd.toBoolean)
          cd.waitSampling(bitCyc + bitCyc / 2)
          var c = 0
          for (i <- 0 until 8) { if (d.io.uart.txd.toBoolean) c |= 1 << i; cd.waitSampling(bitCyc) }
          rx += c.toChar
        }
      }
      def sendByte(c : Int) : Unit = {
        d.io.uart.rxd #= false; cd.waitSampling(bitCyc)
        for (i <- 0 until 8) { d.io.uart.rxd #= ((c >> i) & 1) == 1; cd.waitSampling(bitCyc) }
        d.io.uart.rxd #= true; cd.waitSampling(bitCyc * 3)    // stop bit + gap
      }
      def send(line : String) : Unit = (line + "\r").foreach(c => sendByte(c.toInt))

      cd.waitSampling(20000)                    // boot
      body(d, send, () => rx.toString)
    }

  def waitFor(get : () => String, what : String, cd : ClockDomain, maxCycles : Int = 400000) : String = {
    var n = 0
    while (!get().contains(what) && n < maxCycles) { cd.waitSampling(100); n += 100 }
    assert(get().contains(what), s"'$what' not received, got: ${get().replace("\r", "\\r").replace("\n", "\\n")}")
    get()
  }

  test("interpreter (nucleus): 1 2 + .x  ->  0003") {
    session(dips = 0) { (d, send, rx) =>
      val before = rx().length
      send("1 2 + .x")
      waitFor(() => rx().drop(before).toUpperCase, "0003 ", d.clockDomain)
      waitFor(() => rx().drop(before), "ok", d.clockDomain)
      println(s"[J1MimasV2SocTest] transcript: ${rx().replace("\r", "\\r").replace("\n", "\\n")}")
    }
  }

  test("LEDs: 85 64 io!  ->  leds = 0x55") {
    session(dips = 0) { (d, send, rx) =>
      send("85 64 io!")
      waitFor(rx, "ok", d.clockDomain)
      assert(d.io.leds.toInt == 0x55)
    }
  }

  test("DIP switches: 128 io@ .x  ->  00A5") {
    session(dips = 0xA5) { (d, send, rx) =>
      d.clockDomain.waitSampling(100000)        // debounce (1 ms)
      val before = rx().length
      send("128 io@ .x")
      waitFor(() => rx().drop(before).toUpperCase, "00A5 ", d.clockDomain)
    }
  }

  // The same protocol as toolchain/forth/j1term.py: tethered mode (`1 tth !`,
  // every response ends with 0x1E), swapforth.fs line by line, stop on error.
  test("tethered load of swapforth.fs, then decimal: 1 2 + .  ->  3") {
    session(dips = 0) { (d, send, rx) =>
      val cd = d.clockDomain
      def eots = rx().count(_ == '\u001e')
      def command(line : String) : String = {
        val (n, from) = (eots, rx().length)
        send(line)
        var t = 0
        while (eots == n && t < 20000000) { cd.waitSampling(200); t += 200 }
        assert(eots > n, s"no 0x1E after '$line'")
        rx().substring(from).replace("\u001e", "")
      }
      command(" 1 tth !")
      val dir = new java.io.File(sys.env.getOrElse("J1SCBASE", "."), "toolchain/forth")
      var sent = 0
      def include(f : java.io.File) : Unit = {
        val src = scala.io.Source.fromFile(f)
        try for ((l0, n) <- src.getLines().zipWithIndex) {
          val l = l0.replace("\t", "    ")
          l.trim.split("\\s+").toList match {
            case ("include" | "#include") :: file :: _ => include(new java.io.File(dir, file))
            case _ =>
              val r = command(l); sent += 1
              assert(!r.contains("error"), s"${f.getName}:${n + 1}: '$l' -> $r")
          }
        } finally src.close()
      }
      include(new java.io.File(dir, "swapforth.fs"))
      println(s"[J1MimasV2SocTest] swapforth.fs: $sent lines sent")
      val r = command("1 2 + .")
      assert(r.contains("3 "), s"got: $r")
      println(s"[J1MimasV2SocTest] after swapforth.fs: 1 2 + . -> ${r.replace("\r", "\\r").replace("\n", "\\n")}")
    }
  }

  // Board default (CoreConfig.mimasV2(), 115200 = jimmo PIC firmware) at the real bit time.
  test("board default UART 115200: 1 2 + .x  ->  0003") {
    val bit = (freq / 115200).round.toInt
    SimConfig
      .withConfig(SpinalConfig(defaultConfigForClockDomains = J1MimasV2.globalClockConfig,
                               defaultClockDomainFrequency  = FixedFrequency(50 MHz)))
      .withVerilator.workspacePath("simWorkspace").workspaceName("j1mimasv2_soc_115200")
      .compile(new J1MimasV2Soc(J1Config.forth16, CoreConfig.mimasV2()))
      .doSim { d =>
        val cd = d.clockDomain
        d.io.dips #= 0; d.io.buttons #= 0; d.io.extInt #= 0; d.io.uart.rxd #= true
        cd.forkStimulus(20)
        cd.assertReset(); cd.waitSampling(10); cd.deassertReset()
        val rx = new StringBuilder
        fork {
          while (true) {
            cd.waitSamplingWhere(!d.io.uart.txd.toBoolean)
            cd.waitSampling(bit + bit / 2)
            var c = 0
            for (i <- 0 until 8) { if (d.io.uart.txd.toBoolean) c |= 1 << i; cd.waitSampling(bit) }
            rx += c.toChar
          }
        }
        cd.waitSampling(20000)
        for (c <- "1 2 + .x\r") {
          d.io.uart.rxd #= false; cd.waitSampling(bit)
          for (i <- 0 until 8) { d.io.uart.rxd #= ((c >> i) & 1) == 1; cd.waitSampling(bit) }
          d.io.uart.rxd #= true; cd.waitSampling(bit * 2)
        }
        waitFor(() => rx.toString.toUpperCase, "0003 ", cd, maxCycles = 2000000)
      }
  }

  // Mnozenie: te same polecenia na jadrze programowym (forth16) i sprzetowym (forth16Mul, DSP).
  // Jadro drukuje hex (.x); um* zostawia (lo hi), wiec .x .x drukuje najpierw hi.
  val mulCases = Seq(
    "300 7 * .x"      -> "0834 ",                   // 2100
    "-1 -1 um* .x .x" -> "FFFE 0001 ",              // 0xFFFF * 0xFFFF = 0xFFFE0001
    "-3 7 m* .x .x"   -> "FFFF FFEB ",              // -21 ze znakiem (m* przez um*)
    "4660 22136 * .x" -> "0060 ")                   // 0x1234 * 0x5678 = 0x06260060
  for ((name, sim) <- Seq("forth16 (programowo)" -> (() => compiled), "forth16Mul (DSP)" -> (() => compiledMul)))
    test(s"mnozenie, $name: *, um*, m*") {
      session(dips = 0, sim = sim()) { (d, send, rx) =>
        for ((cmd, want) <- mulCases) {
          val before = rx().length
          send(cmd)
          waitFor(() => rx().drop(before).toUpperCase, want, d.clockDomain)
        }
      }
    }
}
