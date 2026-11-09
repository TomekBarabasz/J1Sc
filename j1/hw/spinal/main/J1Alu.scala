/*
 * Author: Steffen Reith (Steffen.Reith@hs-rm.de)
 *
 * Create Date:    Fri Apr  3 21:34:07 CEST 2020
 * Module Name:    J1Alu - The ALU for J1Sc
 * Project Name:   J1Sc - A simple J1 implementation in Scala using Spinal HDL
 *
 */
package j1sc

import spinal.core._

case class J1Alu(cfg : J1Config) {

  // Slice the ALU operation out of an instruction
  private def getALUOp(instr : Bits) = instr((instr.high - 4) downto ((instr.high - 8) + 1))

  // Bit 12 instrukcji ALU (nieuzywany w upstreamie): rozszerzone operacje przy cfg.hasMul
  private def isExtOp(instr : Bits) = instr(instr.high - 3)

  // Create and execute a ALU operation
  def apply(instr : Bits, dtos : Bits, dnos : Bits, dStackPtr : UInt, rtos : Bits, toRead : Bits) : Bits = {
    val base = baseOps(instr, dtos, dnos, dStackPtr, rtos, toRead)
    if (!cfg.hasMul) base else {
      // Mnozenie 16 x 16 bez znaku -> 32 b, kombinacyjne w tym samym takcie co reszta ALU.
      // XST / yosys wnioskuja z tego jeden DSP48A1 (18 x 18 ze znakiem, argumenty rozszerzone zerami).
      //   0x1000 N*T  : mlodsze 16 b iloczynu (do *)
      //   0x1100 N*Th : starsze 16 b iloczynu bez znaku (do um*)
      // Pozostale kody z bitem 12 dzialaja jak bez niego (zarezerwowane).
      val product = (dnos.asUInt * dtos.asUInt).setName("aluProduct")
      product.addAttribute("use_dsp48", "yes")
      val op = getALUOp(instr)
      Mux(isExtOp(instr) && op === B"0000", product(cfg.wordSize - 1 downto 0).asBits,
      Mux(isExtOp(instr) && op === B"0001", product(2 * cfg.wordSize - 1 downto cfg.wordSize).asBits, base))
    }
  }

  private def baseOps(instr : Bits, dtos : Bits, dnos : Bits, dStackPtr : UInt, rtos : Bits, toRead : Bits) : Bits = {

    // Calculate difference (- dtos + dnos) and sign to be reused multiple times
    val difference = dnos.resize(cfg.wordSize + 1).asSInt - dtos.resize(cfg.wordSize + 1).asSInt
    val nosIsLess  = (dtos.msb ^ dnos.msb) ? dnos.msb | difference.msb

    // Calculate the ALU result (mux all possible cases)
    getALUOp(instr).mux(

      B"0000" -> dtos,
      B"0001" -> dnos,

      // Arithmetic and logical operations
      B"0010" -> (dtos.asUInt + dnos.asUInt).asBits,
      B"1100" -> difference.resize(cfg.wordSize).asBits,
      B"0011" -> (dtos & dnos),
      B"0100" -> (dtos | dnos),
      B"0101" -> (dtos ^ dnos),
      B"0110" -> (~dtos),
      B"1001" -> (dtos(dtos.high) ## dtos(dtos.high downto 1).asUInt),
      B"1010" -> (dtos(dtos.high - 1 downto 0) ## B"b0"),

      // Push rtos to dtos
      B"1011" -> rtos,

      // Compare operations (equal, dtos > dnos, signed and unsigned)
      B"0111" -> B(cfg.wordSize bits, default -> (difference === 0)),
      B"1000" -> B(cfg.wordSize bits, default -> nosIsLess),
      B"1111" -> B(cfg.wordSize bits, default -> difference.msb),

      // Memory / IO read operations
      B"1101" -> toRead,

      // Misc operations (depth of dstack)
      B"1110" -> dStackPtr.resize(cfg.wordSize bits).asBits)

  }

}
