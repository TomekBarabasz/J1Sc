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
| `F0` | UART 8N1, 115200 (albo 19200, patrz niżej) | `key`, `emit` |

## 0. Firmware PIC-a decyduje o UART-cie
Prędkość UART-u między PIC-iem a FPGA jest stała i ustala ją firmware PIC-a;
bitstream musi mieć tę samą:

| Firmware PIC-a | Porty COM | UART do FPGA | Generacja |
| --- | --- | --- | --- |
| [jimmo/numato-mimasv2-pic-firmware](https://github.com/jimmo/numato-mimasv2-pic-firmware) | **dwa**: programator (`mimas>`) i UART FPGA | **115200** | `J1MimasV2` (domyślnie) |
| fabryczny Numato | jeden, przełączany SW7 | 19200 | `J1MimasV2 19200` |

## 1. Obraz Fortha i Verilog
```
cd toolchain/forth && make && cd ../..              # gforth -> toolchain/forth/build/16bit/nuc.binary
sbt "boards/runMain j1sc.boards.J1MimasV2"          # 115200 -> gen/src/verilog/J1MimasV2.v
sbt "boards/runMain j1sc.boards.J1MimasV2 19200"    # firmware fabryczny
```
`J1MimasV2.v` ma zawartość RAM-u (jądro SwapForth) wpisaną w blok `initial`
— pliki `.bin` obok nie są potrzebne do syntezy.

## 2. ISE 14.7
- Nowy projekt: XC6SLX9, CSG324, speed **-3** (jak w voice-changer), top `J1MimasV2`.
- Źródła: `gen/src/verilog/J1MimasV2.v`, `src/main/xilinx/mimasv2/J1MimasV2.ucf`.
- Generate Programming File → Properties → **Create Binary Configuration File**
  (`-g Binary:Yes`), żeby powstał `.bin` dla konfiguratora Numato.

## 3. Wgranie
- **firmware jimmo**: `J1MimasV2.bin` przez port z konsolą `mimas>` (`programmer.py` z repo
  firmware'u albo XMODEM: `mimas> f`), SW7 bez znaczenia;
- **fabryczny**: SW7 w pozycji programowania, narzędzie Numato (`MimasV2Config.py`),
  potem **SW7 w pozycję UART**.

## 4. Rozmowa z Forthem
Terminal na porcie **UART FPGA** (firmware jimmo: drugi port płytki, nie ten z `mimas>`),
115200 8N1 (fabryczny: 19200). J1 nie ma znaku zachęty — po Enter odpowiada ` ok`.
Gołe jądro wystarczy do prostych rzeczy (`picocom -b 115200 /dev/ttyACM1`, PuTTY), SW1 = reset:
```
1 2 + .x          \ 0003  ok    (jądro drukuje hex: .x)
85 64 io!         \ LED-y 01010101
128 io@ .x        \ stan DIP-ów
```
Pełny SwapForth (dziesiętne `.`, `do`/`loop`, `words` itd.) wgrywa się z PC — 554 linie,
kilka sekund przy 115200 (przy 19200 kilkanaście–kilkadziesiąt):
```
pip install pyserial
cd toolchain/forth
python3 j1term.py -d /dev/ttyACM1 swapforth.fs      # Windows: -d COM14; fabryczny: -b 19200
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

## Mnożenie i MAC na DSP (opcja)
`sbt "boards/runMain j1sc.boards.J1MimasV2 mul"` (można łączyć z prędkością: `19200 mul`) buduje
J1 z mnożeniem na jednym prymitywie DSP48A1 i jądrem `nuc-mul.binary`: `*`, `um*`, `m*` działają
tak samo, szybciej. `mac` zamiast `mul` dokłada drugi DSP48A1 z akumulatorem 48 b i jądro
`nuc-mac.binary` (`make` w `toolchain/forth` buduje wszystkie obrazy):
```
acc0  3 4 mac  5 6 mac  acc@ d.      \ 42   (po wgraniu swapforth.fs; jądro: acc@ .x .x)
acc0  -3 4 macs  2 5 macs  acc@ d.   \ -2
acc@x .                              \ bity 47..32 akumulatora
7 9 acc!                             \ akumulator := 63
```
Szczegóły kodowania i potoku: `NEWHOPE.md`.

## Gdy coś nie działa
- **`mimas>` w terminalu** — to konsola PIC-a (programator flash), nie FPGA. Firmware
  jimmo: weź **drugi** port COM płytki. Fabryczny: przestaw SW7 na UART.
  J1 nie ma znaku zachęty — po Enter odpowiada samym ` ok`.
- **`j1term.py`: `Contacting... no answer`** — po 3 s pokazuje, co przyszło z portu
  (`mimas>` → zły port / SW7; nic → port, prędkość bitstreamu vs firmware PIC-a, czy
  bitstream J1 jest wgrany, SW1 = reset).
- **krzaki w terminalu** — prędkość bitstreamu nie zgadza się z firmware'em PIC-a (tabela w §0).

## Sprawdzone
- Symulacja (`sbt "boards/testOnly j1sc.boards.J1MimasV2SocTest"`, Verilator): jądro
  odpowiada na `1 2 + .x`, zapis LED-ów, odczyt DIP-ów, wgranie całego `swapforth.fs`
  protokołem `j1term.py` i potem `1 2 + .` → `3`.
- yosys `synth_xilinx -family xc6s`: ok. 880 LUT, 4 RAMB16, 10 RAM32M, DCM_SP, 2 BUFG.
- **Niesprawdzone na płytce**: timing w ISE (50 MHz), kierunek UART-u (A8/B8 ze schematu).
  Prędkości: 115200 ze źródeł firmware'u jimmo (`fpga_uart.c`, SPBRG = 0x67), 19200 wg
  dokumentacji Numato. Numeracja DIP-ów jak w NewHope/aht10 (DP1 = F17).

## Rozszerzenia
Wersja z wyświetlaczem 7-seg (rejestr `0x60` + `SevenSegMux` z fpga-hw-lib) jest w
FPGA-snake: `hw/demo/mimas_j1` — tam też wzorzec dokładania własnych peryferiów.
Pozostałe pomysły: GPIO na złączach P6..P8 (`GPIO`, jak pmod w Nexys4; P9 to bank 1,8 V).
