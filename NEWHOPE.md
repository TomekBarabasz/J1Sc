# Fork J1Sc dla newhope (FPGA-snake)

Fork https://github.com/SteffenReith/J1Sc (BSD-3, copyright Steffen Reith — patrz `LICENSE`).
Gałąź robocza: `newhope`. Upstream: `git remote add upstream https://github.com/SteffenReith/J1Sc`.

## Co się zmieniło względem upstreamu (`master`, 7a0056e, 2020-06)

| | upstream | newhope |
| --- | --- | --- |
| Scala / SpinalHDL / sbt | 2.11.8 / 1.4.2 / 0.13.16 | 2.13.14 / 1.12.3 / 1.12.4 (jak fpga-hw-lib) |
| pakiet | domyślny | `j1sc` (rdzeń), `j1sc.boards` (płytki) |
| źródła | `src/main/scala`, `src/test/scala` | `j1/hw/spinal/{main,test}`, `boards/hw/spinal/{main,test}` |
| projekty sbt | jeden + taski iceGen/icePnr/icoProg/… | `j1`, `boards`, root `j1sc` |
| jssc | org.scream3r 2.8.0 | io.github.java-native 2.9.6 |

Poprawki (osobne commity):
- `J1RStack`: wskaźnik stosu powrotu miał szerokość z `dataStackIdxWidth`;
  wszystkie konfiguracje z różnymi głębokościami stosów (`default16`, `debug16`, …)
  nie elaborowały się. `forth16` (5/5) to maskował.
- `*.ys` (iCE40): `attrmap -remove ram_style` — Spinal ≥ 1.5 oznacza stosy
  (`readAsync`) jako `ram_style = "distributed"`, a iCE40 nie ma LUTRAM.

Logika poza tym bez zmian (API: `Bool()`, `FlowCCUnsafeByToggle`, `lazyZip`, …).

## Budowanie
```
cd toolchain/forth && make && cd ../..          # gforth: build/16bit/nuc.binary (forth16)
sbt j1/test                                     # J1IsaTest (Verilator)
sbt "boards/runMain j1sc.boards.J1Ice"          # -> gen/src/{verilog,vhdl}/J1Ice.*
yosys src/main/lattice/IceBreaker/J1Ice.ys      # synteza jak w upstreamie
```
Taski sbt iceSynth/icePnr/iceProg upstreamu zostały usunięte — flow z yosysa
i nextpnr przejdzie do Makefile'i (jak w FPGA-snake).

Zajętość (yosys + nextpnr-ice40, UP5K): cały SoC `J1Ice` (CPU, 4 k słów RAM z
Forthem, UART, timery, PWM, LED-y, przyciski, JTAG) — 2 009 / 5 280 LC, 18 / 30 EBR.

## Numato Mimas V2 (J1 + SwapForth + UART)
`boards/hw/spinal/main/J1MimasV2.scala` (SoC `J1MimasV2Soc` + top z DCM), UCF i instrukcja:
`src/main/xilinx/mimasv2/`. Terminal/loader dla PC: `toolchain/forth/j1term.py` (Python 3).
Test end-to-end w symulacji: `sbt "boards/testOnly j1sc.boards.J1MimasV2SocTest"`.

## Mnożenie i MAC na DSP48A1, `J1Config.hasMul`
Operacje rozszerzone na **bicie 12** instrukcji ALU (w upstreamie nieużywanym), kod w bitach 11..8.
Dwa niezależne kanały (`J1MulUnit.scala`), każdy w osobnym DSP48A1: **P0** (32 b) dla `*`, `um*`,
`m*` — jądro Fortha używa go samo (parsowanie liczb), więc nie może dzielić rejestru z MAC — i **P1**
(48 b), akumulator MAC.

| Kod (alu) | Nazwa | Działanie |
| --- | --- | --- |
| `0x1000` / `0x1100` | `mul` / `muls` | P0 := N × T bez znaku / ze znakiem; ALU: T := N |
| `0x1200` / `0x1300` | `mul@` / `mul@h` | T := P0[15:0] / push P0[31:16] |
| `0x1400` / `0x1500` | `mac` / `macs` | P1 += N × T bez znaku / ze znakiem; ALU: T := N |
| `0x1600` | `acc!` | P1 := N × T |
| `0x1800` / `0x1900` / `0x1a00` | `acc@` / `acc@h` / `acc@x` | P1[15:0] / [31:16] / [47:32] |

Potok jak w DSP48A1: A/B → M → P (wynik po 3 taktach), mnożenia co takt. **Odczyt P przy
niepustym potoku wstrzymuje rdzeń** przez `stall` (PC stoi, instrukcja wraca z pamięci) — bez NOP-ów.
Mnożnik nie leży na ścieżce jednego taktu ALU. `hasMul = false`: bit 12 ignorowany, ISA jak w upstreamie.

`J1Config.mulDsp48a1`: `true` = prymityw DSP48A1 (Spartan-6; rejestry A1/B1, M, OPMODE i P oraz
sumator akumulatora w bloku DSP), `false` = wersja behawioralna (dowolne FPGA). Symulacja prymitywu
potrzebuje modelu `j1/hw/sim/DSP48A1.v` (kopia z fpga-hw-lib, `SimConfig.addRtl`).

Forth: `toolchain/forth/basewords-mul.fs` (makra `dsp-*`, `hwmul`) i `hwmul [if]` w `nuc.fs`:
`*` = 2 instrukcje, `um*` / `m*` = 3, plus słowa MAC `acc0 acc! mac macs acc@ acc@x`
(`acc@` ( -- d ) = P1[31:0]). `make` buduje `nuc.binary` (bajt w bajt bez zmian) i `nuc-mul.binary`.
Konfiguracja: `J1Config.forth16Mul`; generator Mimasa: `sbt "boards/runMain j1sc.boards.J1MimasV2 mul"`
(= `forth16Mul` + `mulDsp48a1`).

Testy: `J1IsaTest` (oba warianty kanału: brzegowe mul, losowe mac/macs vs akumulator 48 b w Scali,
mac co takt, mul między mac nie rusza P1, bit 12 bez `hasMul`), `J1MimasV2SocTest` (`*`, `um*`, `m*`
na obu jądrach; `acc0 mac macs acc@ acc@x acc!` przez UART na prymitywie z modelem).
yosys xc6s (`J1MimasV2`): bez mnożenia 745 LUT / 461 FF; z `mul` (prymityw) 935 LUT / 476 FF / 2 DSP48A1.

## Jako submodule
```
git submodule add -b newhope https://github.com/TomekBarabasz/J1Sc hw/cpu/j1
```
`hw/build.sbt` konsumenta:
```scala
lazy val j1 = ProjectRef(file("cpu/j1"), "j1")
lazy val snake = (...).dependsOn(j1)
```
scalaVersion w obu buildach musi być ta sama (`build.sbt` tutaj vs `HwBuild.scala`).

## Do zrobienia
- ROM z firmware w .v: zrobione dla J1MimasV2 (`InlineReadmem` po generacji);
  J1Ice/J1Ico/J1Nexys4X dalej z `$readmemb`.
- `bootCode` z pliku firmware bez `sys.exit` przy braku pliku (dziś `forth16`
  zabija JVM, gdy `nuc.binary` nie ma).
- Top-levele dla Tang Nano 9K i iCESugar (w FPGA-snake albo w `boards/`).
