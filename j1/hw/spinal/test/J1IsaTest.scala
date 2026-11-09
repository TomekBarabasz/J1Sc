package j1sc

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

// =====================================================================
//  Regresja ISA po porcie na SpinalHDL 1.12: male programy w ROM-ie,
//  wynik obserwowany jako zapis na magistrali I/O (cpuBus). Kodowanie
//  instrukcji i komentarze jak w J1ISA16 (J1Config.scala).
// =====================================================================
object J1IsaTest {
  def push(v : Int) = { require(v >= 0 && v < 0x8000); 0x8000 | v }
  def jmp(a : Int)  = 0x0000 | a
  def jz(a : Int)   = 0x2000 | a
  def call(a : Int) = 0x4000 | a
  val addDrop   = 0x6203   // T+N, dstack -1
  val ioWrNip   = 0x6043   // N -> io[T], dstack -1
  val ioRd      = 0x6D50   // T <- io[T]
  val pop       = 0x6103
  val ret       = 0x608C
  val nop       = 0x6000
  val invert    = 0x6600   // ~T
  val over      = 0x6111   // N T->N d+1
  // J1MulUnit (bit 12 instrukcji ALU)
  val mul       = 0x7003   // P0 := N*T bez znaku,  T := N, d-1
  val muls      = 0x7103   // P0 := N*T ze znakiem
  val mulL      = 0x7200   // T := P0[15:0]
  val mulH      = 0x7311   // push P0[31:16]   (T->N d+1)
  val mac       = 0x7403   // P1 += N*T bez znaku,  T := N, d-1
  val macs      = 0x7503   // P1 += N*T ze znakiem
  val accSt     = 0x7603   // P1 := N*T bez znaku
  val accL      = 0x7800   // T := P1[15:0]
  val accH      = 0x7911   // push P1[31:16]
  val accX      = 0x7A11   // push P1[47:32]

  /** Wypchniecie dowolnej wartosci 16 b: literal ma 15 b, wieksze przez ~T. */
  def lit(v : Int) : Seq[Int] = if ((v & 0x8000) == 0) Seq(push(v)) else Seq(push(~v & 0x7FFF), invert)

  val adrWidth = 8

  def cfg(prog : Seq[Int], hasMul : Boolean = false, dsp : Boolean = false, hasMac : Boolean = false) = J1Config(
    wordSize            = 16,
    dataStackIdxWidth   = 5,
    returnStackIdxWidth = 4,
    hasJtag             = false,
    jtagConfig          = null,
    timerConfig         = TimerConfig.default,
    irqConfig           = IRQCtrlConfig.default,
    adrWidth            = adrWidth,
    numOfRAMs           = 2,
    startAddress        = 0,
    bootCode            = () => (prog ++ Seq.fill((1 << adrWidth) - prog.size)(0)).map(B(_, 16 bits)).toList,
    hasMul              = hasMul,
    hasMac              = hasMac,
    mulDsp48a1          = dsp)

  val spinal = SpinalConfig(defaultConfigForClockDomains =
    ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH))
}

class J1IsaTest extends AnyFunSuite {
  import J1IsaTest._

  /** Uruchamia program, zwraca zapisy I/O (adres, dane). readData = ioIn. */
  def ioWrites(name : String, prog : Seq[Int], ioIn : Int = 0, cycles : Int = 200, hasMul : Boolean = false,
               dsp : Boolean = false, hasMac : Boolean = false) : Seq[(Int, Int)] = {
    var writes = Vector.empty[(Int, Int)]
    val sc = SimConfig.withConfig(spinal).withVerilator.workspacePath("simWorkspace").workspaceName(name)
    (if (dsp) sc.addRtl("hw/sim/DSP48A1.v") else sc)                // model prymitywu DSP48A1
      .compile(new J1(cfg(prog, hasMul, dsp, hasMac))).doSim(name) { d =>
        d.internal.stall  #= false
        d.internal.irq    #= false
        d.internal.intVec #= 0
        d.bus.cpuBus.readData #= ioIn
        d.clockDomain.forkStimulus(10)
        d.clockDomain.assertReset()
        d.clockDomain.waitSampling(4)
        d.clockDomain.deassertReset()
        for (_ <- 0 until cycles) {
          d.clockDomain.waitSampling()
          val b = d.bus.cpuBus
          if (b.enable.toBoolean && b.writeMode.toBoolean)
            writes :+= (b.address.toInt -> b.writeData.toInt)
        }
      }
    writes
  }

  test("alu: 7 + 3 -> io[0x40]") {
    val prog = Seq(push(7), push(3), addDrop, push(0x40), ioWrNip, pop, jmp(6))
    assert(ioWrites("isa_alu", prog) == Seq(0x40 -> 10))
  }

  test("call / ret") {
    val prog = Seq(call(6), push(0x41), ioWrNip, pop, jmp(4), nop,
                   push(0x55), ret)
    assert(ioWrites("isa_call", prog) == Seq(0x41 -> 0x55))
  }

  test("skok warunkowy (jz)") {
    val prog = Seq(push(0), jz(4), push(0xBA), jmp(5),
                   push(0x77), push(0x42), ioWrNip, pop, jmp(8))
    assert(ioWrites("isa_jz", prog) == Seq(0x42 -> 0x77))
  }

  test("odczyt I/O -> zapis I/O") {
    val prog = Seq(push(0x43), ioRd, ioRd, push(0x44), ioWrNip, pop, jmp(6))
    assert(ioWrites("isa_ioread", prog, ioIn = 0x1234) == Seq(0x44 -> 0x1234))
  }

  // ---- mnozenie / MAC na DSP48A1 (J1Config.hasMul, J1MulUnit) ----------------------
  def mask48(x : BigInt) = x & ((BigInt(1) << 48) - 1)
  def s16(v : Int) = if ((v & 0x8000) != 0) v - 0x10000 else v
  /** Zapis T pod io[addr] i zdjecie T ze stosu. */
  def out(addr : Int) = Seq(push(addr), ioWrNip, pop)
  /** Odczyt calego akumulatora: lo -> 0x40, hi -> 0x41, x -> 0x42 (stos bez zmian). */
  val readAcc = Seq(push(0), accL) ++ out(0x40) ++ Seq(accH) ++ out(0x41) ++ Seq(accX) ++ out(0x42)
  def accWrites(p : BigInt) = { val m = mask48(p); Seq(0x40 -> (m & 0xFFFF).toInt, 0x41 -> ((m >> 16) & 0xFFFF).toInt, 0x42 -> ((m >> 32) & 0xFFFF).toInt) }

  // kanaly J1MulUnit: wersja behawioralna i prymityw DSP48A1 (z modelem hw/sim/DSP48A1.v)
  for ((dsp, v) <- Seq(false -> "beh", true -> "dsp48a1")) {
    test(s"mul + mul@ / mul@h: wartosci brzegowe bez znaku, odczyt tuz po mul (blokada) ($v)") {
      val pairs = Seq((300, 7), (0xFFFF, 0xFFFF), (0x8000, 2), (0, 0x1234), (0x1234, 0x5678))
      val prog  = pairs.flatMap { case (a, b) => lit(a) ++ lit(b) ++ Seq(mul, mulL) ++ out(0x40) ++ Seq(mulH) ++ out(0x41) }
      val want  = pairs.flatMap { case (a, b) => val p = a.toLong * b; Seq(0x40 -> (p & 0xFFFF).toInt, 0x41 -> (p >> 16).toInt) }
      assert(ioWrites(s"isa_mul_$v", prog :+ jmp(prog.size), cycles = 600, hasMul = true, dsp = dsp) == want)
    }

    test(s"mac / macs: losowe ciagi vs akumulator 48 b w Scali ($v)") {
      val rng = new scala.util.Random(48)
      for ((signed, name) <- Seq(false -> "u", true -> "s")) {
        val pairs = Seq.fill(10)((rng.nextInt(0x10000), rng.nextInt(0x10000)))
        def prod(a : Int, b : Int) : BigInt = if (signed) BigInt(s16(a)) * s16(b) else BigInt(a) * b
        val prog = Seq(push(0), push(0), accSt, pop) ++ pairs.flatMap { case (a, b) =>
          lit(a) ++ lit(b) ++ Seq(if (signed) macs else mac, pop)
        } ++ readAcc
        val p = pairs.map { case (a, b) => prod(a, b) }.sum
        assert(ioWrites(s"isa_mac_${name}_$v", prog :+ jmp(prog.size), cycles = 800, hasMul = true, dsp = dsp, hasMac = true) == accWrites(p), name)
      }
    }

    test(s"mac co takt (back-to-back) i przepelnienie powyzej 32 b ($v)") {
      // stos (v0 .. v7), potem 7 x mac pod rzad: mac mnozy N*T i zdejmuje T
      val vals = Seq(0xFFFF, 0xFFFE, 0xF00D, 0xBEEF, 0x7FFF, 0x8001, 0xFFFF, 0xFFFF)
      val prog = Seq(push(0), push(0), accSt, pop) ++ vals.flatMap(lit) ++ Seq.fill(vals.size - 1)(mac) ++ Seq(pop) ++ readAcc
      var st = vals.toList; var p = BigInt(0)
      for (_ <- 1 until vals.size) { val t = st.last; val n = st(st.size - 2); p += BigInt(n) * t; st = st.init }
      assert(p > BigInt(0xFFFFFFFFL))
      assert(ioWrites(s"isa_mac_b2b_$v", prog :+ jmp(prog.size), cycles = 400, hasMul = true, dsp = dsp, hasMac = true) == accWrites(p))
    }

    test(s"mul / muls miedzy mac nie ruszaja akumulatora MAC (osobne kanaly) ($v)") {
      // P1 = 100*200 + 300*400; w srodku mul i muls na kanale P0, odczyt obu
      val prog = Seq(push(100), push(200), accSt, pop) ++
                 lit(0xFFFF) ++ lit(0xFFFF) ++ Seq(mul, pop) ++
                 Seq(push(300), push(400), mac, pop) ++
                 lit(0xFFFD) ++ Seq(push(7), muls, mulL) ++ out(0x43) ++ Seq(mulH) ++ out(0x44) ++   // -3 * 7 = -21
                 readAcc
      val want = Seq(0x43 -> 0xFFEB, 0x44 -> 0xFFFF) ++ accWrites(BigInt(100 * 200 + 300 * 400))
      assert(ioWrites(s"isa_mul_vs_mac_$v", prog :+ jmp(prog.size), cycles = 400, hasMul = true, dsp = dsp, hasMac = true) == want)
    }
  }

  test("samo hasMul (1 DSP): kody MAC dzialaja jak bez bitu 12") {
    // 0x7403 = op 0100 (T|N) z d-1: (300 7) -> 300 | 7 = 0x12F; mul dalej dziala
    val prog = Seq(push(300), push(7), mac) ++ out(0x40) ++ Seq(push(300), push(7), mul, mulL) ++ out(0x41)
    assert(ioWrites("isa_mul_only", prog :+ jmp(prog.size), hasMul = true) == Seq(0x40 -> 0x12F, 0x41 -> 2100))
  }

  test("bez hasMul bit 12 jest ignorowany (ISA jak w upstreamie)") {
    // 0x7003 = op T z d-1 (nip): (300 7) -> 7
    val prog = Seq(push(300), push(7), mul, push(0x40), ioWrNip, pop, jmp(6))
    assert(ioWrites("isa_mul_off", prog) == Seq(0x40 -> 7))
  }
}
