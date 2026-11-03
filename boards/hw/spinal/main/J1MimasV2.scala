package j1sc.boards

import j1sc._
import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

// =====================================================================
//  J1 + SwapForth on the Numato Mimas V2 (Spartan-6 XC6SLX9-CSG324).
//
//  J1MimasV2Soc  - pure Spinal, one clock domain (simulated in J1MimasV2SocTest)
//  J1MimasV2     - board top: DCM_SP 100 -> 50 MHz, reset from SW1 + DCM lock
//
//  I/O map (same as the upstream boards, so the SwapForth nucleus works as is):
//    0x40 LEDs D1..D8 (r/w)            0x80 DIP switches DP1..DP8 (r, 1 = ON)
//    0x90 push buttons SW1..SW6 (r, 1 = pressed; SW1 is also the reset)
//    0xC0 / 0xD0 timer A / B           0xE0 interrupt controller
//    0xF0 UART (19200 8N1 over the PIC USB bridge, slide switch SW7 = UART)
//
//    sbt "boards/runMain j1sc.boards.J1MimasV2"
//    -> gen/src/verilog/J1MimasV2.v + J1MimasV2.v_toplevel_*.bin (RAM image)
//    UCF: src/main/xilinx/mimasv2/J1MimasV2.ucf
// =====================================================================
class J1MimasV2Soc(j1Cfg : J1Config, boardCfg : CoreConfig) extends Component {

  val io = new Bundle {
    val leds    = out Bits(boardCfg.ledBankConfig.width bits)
    val dips    = in  Bits(boardCfg.sSwitchConfig.numOfPins bits)   // 1 = ON
    val buttons = in  Bits(boardCfg.pButtonConfig.numOfPins bits)   // 1 = pressed
    val extInt  = in  Bits(j1Cfg.irqConfig.numOfInterrupts - j1Cfg.irqConfig.numOfInternalInterrupts bits)
    val uart    = master(Uart())
  }

  require(!j1Cfg.hasJtag, "J1MimasV2Soc: no JTAG (use forth16)")

  val cpu = new J1(j1Cfg)
  cpu.internal.stall := False

  val peripheralBus     = cpu.bus.cpuBus.delayIt(boardCfg.ioWaitStates)
  val peripheralBusCtrl = J1BusSlaveFactory(peripheralBus)

  val ledArray  = new LEDArray(j1Cfg, boardCfg.ledBankConfig)
  ledArray.driveFrom(peripheralBusCtrl, baseAddress = 0x40)
  io.leds := ledArray.io.leds

  val dips = new DBPinArray(j1Cfg, boardCfg.sSwitchConfig)
  dips.driveFrom(peripheralBusCtrl, baseAddress = 0x80)
  dips.io.inputPins := io.dips
  dips.timeOut.stateRise.allowPruning()

  val buttons = new DBPinArray(j1Cfg, boardCfg.pButtonConfig)
  buttons.driveFrom(peripheralBusCtrl, baseAddress = 0x90)
  buttons.io.inputPins := io.buttons
  buttons.timeOut.stateRise.allowPruning()

  val timerA = new Timer(j1Cfg.timerConfig)
  timerA.driveFrom(peripheralBusCtrl, baseAddress = 0xC0)
  val timerB = new Timer(j1Cfg.timerConfig)
  timerB.driveFrom(peripheralBusCtrl, baseAddress = 0xD0)

  val u = boardCfg.uartConfig
  val uartGenerics = UartCtrlGenerics(dataWidthMax      = u.dataWidthMax,
                                      clockDividerWidth = u.clockDividerWidth,
                                      preSamplingSize   = u.preSamplingSize,
                                      samplingSize      = u.samplingSize,
                                      postSamplingSize  = u.postSamplingSize)
  val uartMemMap = UartCtrlMemoryMappedConfig(uartCtrlConfig                = uartGenerics,
                                              initConfig                    = UartCtrlInitConfig(baudrate   = u.baudrate,
                                                                                                 dataLength = u.dataLength,
                                                                                                 parity     = u.parity,
                                                                                                 stop       = u.stop),
                                              busCanWriteClockDividerConfig = false,
                                              busCanWriteFrameConfig        = false,
                                              txFifoDepth                   = u.fifoDepth,
                                              rxFifoDepth                   = u.fifoDepth)
  val uartCtrl   = new UartCtrl(uartGenerics)
  val uartBridge = uartCtrl.driveFrom(peripheralBusCtrl, uartMemMap, baseAddress = 0xF0)
  uartBridge.interruptCtrl.readIntEnable := True
  uartBridge.interruptCtrl.interrupt.allowPruning()
  io.uart <> uartCtrl.io.uart

  val intCtrl = new InterruptCtrl(j1Cfg)
  intCtrl.driveFrom(peripheralBusCtrl, baseAddress = 0xE0)
  intCtrl.io.irqReqs(intCtrl.io.irqReqs.high downto j1Cfg.irqConfig.numOfInternalInterrupts) <> io.extInt
  intCtrl.io.irqReqs(0) <> uartBridge.interruptCtrl.readInt
  intCtrl.io.irqReqs(1) <> timerA.io.interrupt
  intCtrl.io.irqReqs(2) <> timerB.io.interrupt
  cpu.internal.intVec <> intCtrl.internal.intVec.resize(j1Cfg.adrWidth)
  cpu.internal.irq    <> intCtrl.internal.irq
}

// ---- Spartan-6 primitives -----------------------------------------------------

class DCM_SP(clkinPeriodNs : Double, clkdvDivide : Double) extends BlackBox {
  addGeneric("CLKIN_PERIOD", clkinPeriodNs)
  addGeneric("CLKDV_DIVIDE", clkdvDivide)
  addGeneric("CLK_FEEDBACK", "1X")
  addGeneric("CLKIN_DIVIDE_BY_2", "FALSE")
  addGeneric("STARTUP_WAIT", "FALSE")
  val CLKIN, CLKFB, RST, PSEN, PSINCDEC, PSCLK, DSSEN = in Bool()
  val CLK0, CLKDV, LOCKED = out Bool()
}

class BUFG extends BlackBox {
  val I = in Bool()
  val O = out Bool()
}

// ---- board top ----------------------------------------------------------------

class J1MimasV2(j1Cfg : J1Config, boardCfg : CoreConfig) extends Component {

  val io = new Bundle {
    val clk100  = in  Bool()           // V10, 100 MHz
    val btn_n   = in  Bits(6 bits)     // SW1..SW6, active low (PULLUP); SW1 = reset
    val dip_n   = in  Bits(8 bits)     // DP1..DP8, active low (PULLUP)
    val led     = out Bits(8 bits)     // D1..D8, active high
    val uart_rx = in  Bool()           // A8  <- PIC TX
    val uart_tx = out Bool()           // B8  -> PIC RX
  }
  noIoPrefix()

  // 100 MHz -> DCM_SP -> CLKDV = 50 MHz (CLK0 fed back through a BUFG)
  val clkdv = 100e6 / boardCfg.coreFrequency.getValue.toBigDecimal.toDouble
  require(Seq(1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0, 5.5, 6.0, 6.5, 7.0, 7.5, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0).contains(clkdv),
          s"DCM_SP CLKDV_DIVIDE = $clkdv is not a legal value")
  val dcm   = new DCM_SP(clkinPeriodNs = 10.0, clkdvDivide = clkdv)
  val fbBuf = new BUFG
  val dvBuf = new BUFG
  dcm.CLKIN    := io.clk100
  dcm.RST      := False
  dcm.PSEN     := False
  dcm.PSINCDEC := False
  dcm.PSCLK    := False
  dcm.DSSEN    := False
  fbBuf.I      := dcm.CLK0
  dcm.CLKFB    := fbBuf.O
  dvBuf.I      := dcm.CLKDV

  val coreCd = ClockDomain.internal(name = "core", frequency = boardCfg.coreFrequency)
  coreCd.clock := dvBuf.O
  coreCd.reset := coreCd(RegNext(ResetCtrl.asyncAssertSyncDeassert(input       = !io.btn_n(0) || !dcm.LOCKED,
                                                                   clockDomain = coreCd)))

  val core = coreCd(new J1MimasV2Soc(j1Cfg, boardCfg))
  core.io.dips    := ~io.dip_n
  core.io.buttons := ~io.btn_n
  core.io.extInt  := 0
  io.led          := core.io.leds
  core.io.uart.rxd := io.uart_rx
  io.uart_tx      := core.io.uart.txd
}

object J1MimasV2 {
  val globalClockConfig = ClockDomainConfig(clockEdge = RISING, resetKind = SYNC, resetActiveLevel = HIGH)

  def main(args : Array[String]) : Unit = {
    println("[J1Sc] Create the J1 for a Numato Mimas V2 board")
    val dir = "gen/src/verilog"
    SpinalConfig(mergeAsyncProcess            = true,
                 defaultConfigForClockDomains = globalClockConfig,
                 targetDirectory              = dir)
      .generateVerilog(new J1MimasV2(J1Config.forth16, CoreConfig.mimasV2()))
      .printPruned()
    InlineReadmem(s"$dir/J1MimasV2.v")
  }
}

/** Replaces `$readmemb("<file>", mem);` in a generated Verilog file with explicit
  * `mem[i] = 16'b...;` assignments, so the RAM image (SwapForth) lives in the .v
  * itself. XST (ISE) and yosys both infer initialised block RAM from that, and
  * the .bin files no longer have to sit next to the synthesis project. */
object InlineReadmem {
  def apply(verilogPath : String) : Unit = {
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets.UTF_8
    val path = Paths.get(verilogPath)
    val dir  = path.getParent
    val re   = """\$readmemb\("([^"]+)",\s*(\w+)\);""".r
    val src  = new String(Files.readAllBytes(path), UTF_8)
    var n    = 0
    val out  = re.replaceAllIn(src, m => {
      val words = new String(Files.readAllBytes(dir.resolve(m.group(1))), UTF_8)
        .split("\\s+").filter(_.nonEmpty)
      n += 1
      java.util.regex.Matcher.quoteReplacement(
        words.zipWithIndex.map { case (w, i) => s"${m.group(2)}[$i] = ${w.length}'b$w;" }.mkString("\n    "))
    })
    Files.write(path, out.getBytes(UTF_8))
    println(s"[J1Sc] Inlined $n memory image(s) into $verilogPath")
  }
}
