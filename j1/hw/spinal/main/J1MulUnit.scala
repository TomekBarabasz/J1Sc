package j1sc

import spinal.core._

// =====================================================================
//  J1MulUnit - mnozenie i MAC na DSP48A1 (J1Config.hasMul).
//
//  Dwa niezalezne kanaly, kazdy w osobnym DSP48A1 z rejestrami A/B, M i P:
//    kanal mul (P0, 32 b): *, um*, m* - jadro Fortha uzywa go sam (np. przy
//                          parsowaniu liczb), wiec nie moze dzielic P z MAC
//    kanal mac (P1, 48 b): akumulator MAC, ktorego nic poza slowami MAC nie rusza
//
//  Operacje rozszerzone ALU: bit 12 instrukcji ALU = 1, kod w bitach 11..8.
//    op 0000 mul    P0 := N * T  bez znaku      ALU: T := N (z d-1 zdejmuje T)
//       0001 muls   P0 := N * T  ze znakiem     ALU: T := N
//       0010 mul@   T := P0[15:0]
//       0011 mul@h  T := P0[31:16]
//       0100 mac    P1 := P1 + N * T bez znaku  ALU: T := N
//       0101 macs   P1 := P1 + N * T ze znakiem ALU: T := N
//       0110 acc!   P1 := N * T  bez znaku      ALU: T := N
//       1000 acc@   T := P1[15:0]
//       1001 acc@h  T := P1[31:16]
//       1010 acc@x  T := P1[47:32]
//  Pozostale kody z bitem 12 dzialaja jak bez niego (zarezerwowane).
//
//  Potok jak w DSP48A1: A/B (AREG/BREG = 1) -> M (MREG = 1) -> P (PREG = 1,
//  ladowanie M albo P + M). Wynik jest w P po 3 taktach od wydania; mnozenia
//  mozna wydawac co takt. Mnoznik nie lezy na sciezce jednego taktu ALU.
//
//  Blokada: odczyt P kanalu z niepustym potokiem wstrzymuje rdzen przez `stall`
//  (jak JTAG: PC stoi, instrukcja wraca z pamieci, stosy bez zmian).
//
//  Implementacja kanalu (J1Config.mulDsp48a1):
//    false  behawioralna (dowolne FPGA; syntezer sam decyduje, co wciagnie do DSP)
//    true   prymityw DSP48A1 (Spartan-6): A1/B1, M, OPMODE i P w bloku DSP, akumulator
//           i sumator 48 b tez - w logice zostaja tylko bity valid. Symulacja wymaga
//           modelu j1/hw/sim/DSP48A1.v (SimConfig.addRtl), jak DspBank w fpga-hw-lib.
// =====================================================================

/** Prymityw Xilinx DSP48A1 (UG389) - podzbior generikow obslugiwany przez model
  * j1/hw/sim/DSP48A1.v: A1REG = B1REG = CREG = MREG = OPMODEREG = PREG = 1. */
class DSP48A1(rstType : String = "SYNC") extends BlackBox {
  val generic = new Generic {
    val A0REG = 0; val A1REG = 1; val B0REG = 0; val B1REG = 1
    val CARRYINREG = 0; val CARRYINSEL = "OPMODE5"; val CARRYOUTREG = 0
    val CREG = 1; val DREG = 0; val MREG = 1; val OPMODEREG = 1; val PREG = 1
    val RSTTYPE = rstType
  }
  val io = new Bundle {
    val CLK       = in  Bool()
    val A, B, D   = in  Bits(18 bits)
    val C, PCIN   = in  Bits(48 bits)
    val OPMODE    = in  Bits(8 bits)
    val CARRYIN   = in  Bool()
    val CEA, CEB, CEC, CED, CEM, CEP, CEOPMODE, CECARRYIN = in Bool()
    val RSTA, RSTB, RSTC, RSTD, RSTM, RSTP, RSTOPMODE, RSTCARRYIN = in Bool()
    val P, PCOUT  = out Bits(48 bits)
    val M         = out Bits(36 bits)
    val BCOUT     = out Bits(18 bits)
    val CARRYOUT, CARRYOUTF = out Bool()
  }
  noIoPrefix()
  mapClockDomain(clock = io.CLK)
}
case class J1MulUnit(cfg : J1Config) extends Area {
  require(cfg.wordSize == 16, "J1MulUnit: argumenty 16 b (DSP48A1 18 x 18)")

  val MUL   = B"0000"
  val MULS  = B"0001"
  val MULL  = B"0010"
  val MULH  = B"0011"
  val MAC   = B"0100"
  val MACS  = B"0101"
  val ACCST = B"0110"
  val ACCL  = B"1000"
  val ACCH  = B"1001"
  val ACCX  = B"1010"

  private def isExt(instr : Bits, pcMsb : Bool) : Bool =
    !pcMsb && instr(instr.high downto instr.high - 2) === B"011" && instr(instr.high - 3)
  private def op(instr : Bits) : Bits = instr(instr.high - 4 downto instr.high - 7)

  /** Jeden DSP48A1: A/B -> M -> P, ladowanie albo akumulacja. */
  class Channel(tag : String, pWidth : Int) extends Area {
    val aValid = RegInit(False).setName(s"${tag}_aValid")     // potok: v1 (A/B), v2 (M)
    val aAcc   = (Reg(Bool()) init False).setName(s"${tag}_aAcc")
    val mValid = RegInit(False).setName(s"${tag}_mValid")
    val busy   = aValid || mValid
    val p      = SInt(pWidth bits).setName(s"${tag}_p")

    def build(issue : Bool, acc : Bool, signed : Bool, n : Bits, t : Bits) : Unit = {
      def ext18(x : Bits) : SInt = signed ? x.asSInt.resize(18) | x.asUInt.resize(18).asSInt
      aValid := issue
      when(issue) { aAcc := acc }
      mValid := aValid

      if (cfg.mulDsp48a1) {
        // A/B w cyklu wydania (CEA/CEB), OPMODE takt pozniej (lapie go OPMODEREG razem z M),
        // CEP dwa takty po wydaniu - jak DspBank.primitive w fpga-hw-lib
        val sync = ClockDomain.current.config.resetKind == SYNC
        require(sync || ClockDomain.current.config.resetKind == ASYNC)
        val dsp = new DSP48A1(if (sync) "SYNC" else "ASYNC").setName(s"${tag}_dsp")
        dsp.io.A        := ext18(n).asBits
        dsp.io.B        := ext18(t).asBits
        dsp.io.D        := 0
        dsp.io.C        := 0
        dsp.io.PCIN     := 0
        dsp.io.CARRYIN  := False
        dsp.io.OPMODE   := aAcc ? B"8'h09" | B"8'h01"            // X = M; Z = P (MAC) albo 0 (ladowanie)
        dsp.io.CEA      := issue
        dsp.io.CEB      := issue
        dsp.io.CEM      := True
        dsp.io.CEOPMODE := True
        dsp.io.CEP      := mValid
        dsp.io.CEC := False; dsp.io.CED := False; dsp.io.CECARRYIN := False
        dsp.io.RSTP := ClockDomain.current.isResetActive         // P = 0 po resecie
        dsp.io.RSTA := False; dsp.io.RSTB := False; dsp.io.RSTC := False; dsp.io.RSTD := False
        dsp.io.RSTM := False; dsp.io.RSTOPMODE := False; dsp.io.RSTCARRYIN := False
        p := dsp.io.P.asSInt.resize(pWidth)
      } else {
        val aReg = Reg(SInt(18 bits)).setName(s"${tag}_a")
        val bReg = Reg(SInt(18 bits)).setName(s"${tag}_b")
        val mReg = Reg(SInt(36 bits)).setName(s"${tag}_m")
        val mAcc = (Reg(Bool()) init False).setName(s"${tag}_mAcc")
        val pReg = (Reg(SInt(pWidth bits)) init 0).setName(s"${tag}_preg")
        for (r <- Seq(aReg, bReg, mReg, pReg)) r.addAttribute("use_dsp48", "yes")
        when(issue) { aReg := ext18(n); bReg := ext18(t) }
        mAcc := aAcc
        mReg := aReg * bReg
        when(mValid) { pReg := mAcc ? (pReg + mReg.resize(pWidth)) | mReg.resize(pWidth) }
        p := pReg
      }
    }
  }

  val mulCh = new Channel("mulP0", 32)
  val macCh = new Channel("macP1", 48)

  /** Odczyt P kanalu z niepustym potokiem w instrukcji z pamieci -> stall. */
  def interlock(rawInstr : Bits, pcMsb : Bool) : Bool = {
    val o = op(rawInstr)
    isExt(rawInstr, pcMsb) && (((o === MULL || o === MULH) && mulCh.busy) ||
                               ((o === ACCL || o === ACCH || o === ACCX) && macCh.busy))
  }

  /** Wydanie z wykonywanej instrukcji (przy stall jest tu NOP) i przesuw potokow. */
  def build(instr : Bits, pcMsb : Bool, dtos : Bits, dnos : Bits) : Unit = {
    val o   = op(instr)
    val ext = isExt(instr, pcMsb)
    mulCh.build(issue = ext && (o === MUL || o === MULS), acc = False,
                signed = o === MULS, dnos, dtos)
    macCh.build(issue = ext && (o === MAC || o === MACS || o === ACCST), acc = o =/= ACCST,
                signed = o === MACS, dnos, dtos)
  }

  /** Wynik ALU dla operacji rozszerzonych; `base` dla pozostalych kodow. */
  def aluResult(instr : Bits, dnos : Bits, base : Bits) : Bits = {
    val o   = op(instr)
    val ext = instr(instr.high - 3)
    val p0  = mulCh.p.asBits
    val p1  = macCh.p.asBits
    Mux(!ext, base, o.mux[Bits](
      MUL     -> dnos, MULS -> dnos, MAC -> dnos, MACS -> dnos, ACCST -> dnos,
      MULL    -> p0(15 downto 0),
      MULH    -> p0(31 downto 16),
      ACCL    -> p1(15 downto 0),
      ACCH    -> p1(31 downto 16),
      ACCX    -> p1(47 downto 32),
      default -> base))
  }
}
