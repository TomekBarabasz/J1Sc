# J1 + SwapForth na Numato Mimas V2

Top `J1MimasV2` (`boards/hw/spinal/main/J1MimasV2.scala`): CPU J1 z interpreterem
SwapForth w RAM-ie, UART przez mostek USB w PIC-u, LED-y, DIP-y, przyciski, 2 timery,
kontroler przerwań. Zegar 100 MHz → DCM_SP → 50 MHz.

| Adres | Co | Forth (po `hex`) |
| --- | --- | --- |
| `40` | LED D1..D8 (zapis/odczyt) | `55 40 io!` |
| `80` | DIP, 1 = ON (bit 0 = DP1 na nadruku, pin F17) | `80 io@ .` |
| `90` | przyciski SW1..SW6, 1 = wciśnięty (SW1 to też reset) | `90 io@ .` |
| `C0` / `D0` | timer A / B | |
| `E0` | kontroler przerwań | |
| `F0` | UART, 19200 8N1 | `key`, `emit` |

## 1. Obraz Fortha i Verilog
```
cd toolchain/forth && make && cd ../..          # gforth -> toolchain/forth/build/16bit/nuc.binary
sbt "boards/runMain j1sc.boards.J1MimasV2"      # -> gen/src/verilog/J1MimasV2.v
```
`J1MimasV2.v` ma zawartość RAM-u (jądro SwapForth) wpisaną w blok `initial`
— pliki `.bin` obok nie są potrzebne do syntezy.

## 2. ISE 14.7
- Nowy projekt: XC6SLX9, CSG324, speed **-3** (jak w voice-changer), top `J1MimasV2`.
- Źródła: `gen/src/verilog/J1MimasV2.v`, `src/main/xilinx/mimasv2/J1MimasV2.ucf`.
- Generate Programming File → Properties → **Create Binary Configuration File**
  (`-g Binary:Yes`), żeby powstał `.bin` dla konfiguratora Numato.

## 3. Wgranie
SW7 w pozycji programowania (USB config), wgranie `J1MimasV2.bin` narzędziem Numato
(Mimas V2 Configuration Tool / `MimasV2Config.py`), potem **SW7 w pozycję UART**.

## 4. Rozmowa z Forthem
Gołe jądro wystarczy do prostych rzeczy — dowolny terminal 19200 8N1
(`picocom -b 19200 /dev/ttyACM0`, PuTTY na COMx), SW1 = reset:
```
1 2 + .x          \ 0003  ok    (jądro drukuje hex: .x)
85 64 io!         \ LED-y 01010101
128 io@ .x        \ stan DIP-ów
```
Pełny SwapForth (dziesiętne `.`, `do`/`loop`, `words` itd.) wgrywa się z PC — 554 linie,
przy 19200 bodach kilkanaście–kilkadziesiąt sekund:
```
pip install pyserial
cd toolchain/forth
python3 j1term.py -d /dev/ttyACM0 -b 19200 swapforth.fs     # Windows: -d COM5
```
Po `[swapforth.fs loaded]` zostajesz w terminalu (`#include plik.fs` dogrywa kolejne
pliki, Ctrl-D kończy). Wgrany SwapForth jest w RAM-ie: ponowna konfiguracja FPGA
(wyłączenie zasilania, wgranie bitstreamu) wraca do gołego jądra. Reset SW1 restartuje
CPU, ale nie zeruje RAM-u — czy słownik po nim przetrwa, do sprawdzenia na płytce.

Przykład po wgraniu `swapforth.fs`:
```
hex
: led!  ( n -- )  40 io! ;
: dips  ( -- n )  80 io@ ;
: mirror  ( -- )  begin dips led! key? until ;   \ DIP-y na LED-y, dowolny klawisz kończy
mirror
```

## Gdy coś nie działa
- **`mimas>` w terminalu** — to konsola mikrokontrolera PIC, nie FPGA: SW7 jest w pozycji
  programowania. Przestaw SW7 na UART (port COM zwykle zostaje ten sam). J1 nie ma
  znaku zachęty — po Enter odpowiada samym `ok`.
- **`j1term.py`: `Contacting... no answer`** — po 3 s pokazuje, co przyszło z portu
  (`mimas>` → SW7; nic → SW7, port, czy bitstream J1 jest wgrany, SW1 = reset).
- W trybie UART terminal ustaw na **19200 8N1** (tyle ma UART w FPGA).

## Sprawdzone
- Symulacja (`sbt "boards/testOnly j1sc.boards.J1MimasV2SocTest"`, Verilator): jądro
  odpowiada na `1 2 + .x`, zapis LED-ów, odczyt DIP-ów, wgranie całego `swapforth.fs`
  protokołem `j1term.py` i potem `1 2 + .` → `3`.
- yosys `synth_xilinx -family xc6s`: ok. 880 LUT, 4 RAMB16, 10 RAM32M, DCM_SP, 2 BUFG.
- **Niesprawdzone na płytce**: timing w ISE (50 MHz), kierunek i baud UART-u PIC-a
  (19200 wg dokumentacji Numato). Numeracja DIP-ów jak w NewHope/aht10 (DP1 = F17).

## Rozszerzenia
Wersja z wyświetlaczem 7-seg (rejestr `0x60` + `SevenSegMux` z fpga-hw-lib) jest w
FPGA-snake: `hw/demo/mimas_j1` — tam też wzorzec dokładania własnych peryferiów.
Pozostałe pomysły: GPIO na złączach P6..P8 (`GPIO`, jak pmod w Nexys4; P9 to bank 1,8 V).
