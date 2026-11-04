# 01 · Architektura: widok z lotu ptaka

[← spis treści](README.md)

## 1. Hierarchia sprzętu (co zawiera co)

Na przykładzie płytki **Nexys4DDR**, czyli najpełniejszego wariantu. Prostokąty to `Component` (osobne moduły w
wygenerowanym HDL). Zaokrąglone węzły to obiekty Scali lub `Area`, które **nie** tworzą osobnego modułu, tylko
"wklejają" logikę do rodzica.

```mermaid
graph TD
    B["Board_Nexys4DDR.v<br/>(ręczny Verilog)"]
    B --> P["PLL.v<br/>clk100Mhz → boardClk"]
    B --> T["J1Nexys4X<br/>(generowany przez Spinal)"]

    T --> JA("jtagIface.jtagArea<br/>ClockingArea @ TCK")
    JA --> JT["J1Jtag"]

    T --> CC("clkCoreCtrl<br/>generator resetu")
    T --> CA("coreArea<br/>ClockingArea @ core")

    CA --> CPU["J1"]
    CPU --> CORE["J1Core"]
    CPU --> MM["MainMemory"]
    CORE --> PC("J1PC")
    CORE --> DS("J1DStack")
    CORE --> RS("J1RStack")
    CORE --> ALU("J1Alu")
    CORE --> DEC("J1Decoder")

    CA --> FAB("J1BusSlaveFactory<br/>dekoder adresów")
    CA --> LED["LEDArray @0x40"]
    CA --> PWM["PWM @0x50"]
    CA --> SSD["SSD @0x60"]
    CA --> GPIO["GPIO pmodA @0x70"]
    CA --> SW["DBPinArray switche @0x80"]
    CA --> BTN["DBPinArray przyciski @0x90"]
    CA --> TA["Timer A @0xC0"]
    CA --> TB["Timer B @0xD0"]
    CA --> IRQ["InterruptCtrl @0xE0"]
    CA --> UART["UartCtrl @0xF0<br/>(spinal.lib)"]
    CA --> CDC("FlowCCByToggle<br/>JTAG → core")
```

**Jak to czytać:**

* `Board_Nexys4DDR.v` to jedyny plik HDL pisany ręcznie dla tej płytki. Robi trzy rzeczy: odwraca reset
  (`reset = !nreset`), instancjonuje PLL i zamienia trzy sygnały GPIO (`read`/`write`/`writeEnable`) na prawdziwy pin
  trójstanowy (`inout`). Wnętrza FPGA Spinal generuje sam.
* `J1Nexys4X` ([J1Nexys4X.scala](../src/main/scala/J1Nexys4X.scala)) to "płyta główna" SoC. Tworzy domeny zegarowe,
  CPU i peryferia, a potem łączy je magistralą i liniami przerwań.
* `J1` ([J1.scala](../src/main/scala/J1.scala)) to procesor razem z pamięcią: rdzeń + RAM + wyjście magistrali.
* `J1Core` ([J1Core.scala](../src/main/scala/J1Core.scala)) to sam procesor. Jego podzespoły (`J1PC`, `J1Alu`...) to
  zwykłe klasy Scali, które *wywołane* (`apply`) dobudowują logikę wewnątrz `J1Core`. Nie są osobnymi modułami.

## 2. Zależności między plikami Scali

Kto kogo tworzy (strzałka ciągła) i kto czyje konfiguracje czyta (przerywana).

```mermaid
graph LR
    J1Config["J1Config.scala<br/>J1Config, JTAGConfig,<br/>TimerConfig, IRQCtrlConfig,<br/>J1ISA16"]
    CoreConfig["CoreConfig.scala<br/>GPIOConfig, LEDArrayConfig,<br/>PWMConfig, SSDConfig,<br/>DBPinArrayConfig, J1UARTConfig"]

    Top["J1Nexys4X / J1Ico / J1Ice"]
    Top --> J1
    Top --> J1Jtag
    Top --> J1BusSlaveFactory
    Top --> LEDArray & PWM & SSD & GPIO & DBPinArray & Timer & InterruptCtrl

    J1 --> J1Core
    J1 --> MainMemory
    J1 --> J1Bus
    J1Core --> J1PC & J1DStack & J1RStack & J1Alu & J1Decoder
    J1DStack --> J1Stack
    J1RStack --> J1Stack
    J1Jtag --> J1JtagData
    J1BusSlaveFactory --> J1Bus

    J1Config -.-> Top
    CoreConfig -.-> Top
    J1Config -.-> J1Core
    J1Config -.-> MainMemory
    J1Config -.-> InterruptCtrl
    J1Config -.-> J1Jtag
    J1Config -.-> Timer
    CoreConfig -.-> LEDArray
    CoreConfig -.-> PWM
    CoreConfig -.-> SSD
    CoreConfig -.-> GPIO
    CoreConfig -.-> DBPinArray
```

Dwie konfiguracje mają rozdzielone role:

* **`J1Config`**: wszystko, co dotyczy *procesora*: szerokość słowa, głębokość stosów, wielkość RAM, obecność JTAG,
  liczba przerwań, zawartość pamięci przy starcie (`bootCode`).
* **`CoreConfig`**: wszystko, co dotyczy *płytki*: ile diod, jaki UART, jaka częstotliwość zegara, ile przycisków.

Dzięki temu ten sam CPU (`J1Config.forth16Jtag`) działa na trzech różnych płytkach.

## 3. Przepływ danych w działającym systemie

```mermaid
graph LR
    subgraph J1["J1 (CPU + RAM)"]
        MM["MainMemory<br/>4096 × 16 bit"]
        CORE["J1Core"]
        MM -- "instrukcja (readSync)" --> CORE
        CORE -- "adres następnej instrukcji (pcN)" --> MM
        CORE -- "zapis RAM (!)" --> MM
    end

    CORE -- "cpuBus (master)<br/>enable, writeMode,<br/>address, writeData" --> D["delayIt(ioWaitStates)"]
    D --> F["J1BusSlaveFactory"]
    F -- "readData (bez opóźnienia)" --> CORE

    F --> LED["LEDArray"]
    F --> PWM["PWM"]
    F --> SSD["SSD"]
    F --> GPIO["GPIO"]
    F --> DB["DBPinArray ×2"]
    F --> TIM["Timer ×2"]
    F --> IC["InterruptCtrl"]
    F --> U["UartCtrl"]

    U -- "readInt (IRQ 0)" --> IC
    TIM -- "interrupt (IRQ 1, 2)" --> IC
    EXT["extInt (pin)"] -- "IRQ 3" --> IC
    IC -- "irq + intVec" --> CORE

    JT["J1Jtag"] -- "stall, adres/dane RAM" --> J1
    JT -- "jtagReset (asynchr.)" --> RST["generator resetu"]
    RST --> J1
```

Najważniejsze obserwacje:

1. **Pamięć główna nie leży na magistrali peryferiów.** RAM jest wewnątrz `J1`. Instrukcje i dane idą bezpośrednio,
   a na magistralę `J1Bus` trafiają wyłącznie operacje `io@`/`io!`.
2. **Zapis do RAM** idzie osobnym portem zapisu. **Odczyt danych z RAM** używa portu *instrukcji* (sztuczka "high
   call", opisana w [04](04-pamiec-i-magistrala.md#5-odczyt-z-ram-sztuczka-high-call)).
3. **Każde peryferium** ma metodę `driveFrom(busCtrl, baseAddress)`, która rejestruje jego rejestry w fabryce
   `J1BusSlaveFactory`. Fabryka generuje z tego jeden duży dekoder adresów.
4. **Przerwania** wchodzą do CPU jako dwa sygnały: impuls `irq` i adres `intVec`. CPU nie ma osobnego mechanizmu
   ISR, po prostu "udaje", że z pamięci przyszła instrukcja `call intVec`.

## 4. Domeny zegarowe

```mermaid
graph TD
    OSC["Oscylator 100 MHz<br/>(clk100Mhz)"] --> PLL["PLL (MMCM)"]
    PLL -- "boardClk 100 MHz" --> CORE["core ClockDomain<br/>CPU, RAM, peryferia, UART"]
    PLL -- "boardClkLocked" --> RST
    NRST["przycisk CPU reset<br/>(nreset)"] --> RST["ResetCtrl.asyncAssertSyncDeassert<br/>+ RegNext"]
    JRST["jtagReset (z domeny TCK)"] --> RST
    RST -- "reset synchroniczny" --> CORE

    CORE --> PWMD["pwmClockDomain (spowolniona)<br/>200 Hz × 256 = 51,2 kHz"]
    CORE --> SSDD["ssdClockDomain (spowolniona)<br/>200 Hz × 8 = 1,6 kHz"]

    TCK["pin TCK (adapter JTAG)"] --> JD["jtagClockDomain<br/>J1Jtag"]
    JD -- "FlowCCByToggle<br/>(bezpieczne przejście)" --> CORE
```

* **core**: główna domena. Na Nexys4DDR 100 MHz, na IcoBoard 40 MHz, na iCEBreaker 18 MHz (`CoreConfig.coreFrequency`).
* **jtag**: taktowana pinem `TCK` z zewnętrznego adaptera, całkowicie asynchroniczna względem core. Dane przechodzą
  przez `FlowCCByToggle` (bezpieczne przekroczenie domen), a reset JTAG idzie przez synchronizator resetu.
* **Domeny spowolnione** (`newSlowedClockDomain`) w PWM i SSD. Spinal realizuje je jako tę samą linię zegarową z
  sygnałem *clock enable*, który generuje licznik dzielnika. Nie powstaje nowy zegar fizyczny.

Szczegóły resetu i zegarów znajdziesz w [08](08-konfiguracja-plyty-build.md#3-zegar-i-reset).

## 5. Droga od kodu do działającego Fortha

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Ty
    participant GF as gforth (toolchain/forth)
    participant SBT as sbt + SpinalHDL
    participant SYN as Vivado / yosys+nextpnr
    participant FPGA as FPGA
    participant TERM as Terminal (e4thcom)

    Dev->>GF: make (cross.fs basewords.fs nuc.fs)
    GF-->>Dev: build/16bit/nuc.binary (jądro Forth ~ kilka kB)
    Dev->>SBT: sbt "runMain J1Nexys4X"
    SBT->>SBT: J1Config.forthBase() wczytuje nuc.binary jako bootCode
    SBT->>SBT: elaboracja: J1Nexys4X → J1 → J1Core...
    SBT-->>Dev: gen/src/verilog/J1Nexys4X.v (+ pliki .bin z zawartością RAM)
    Dev->>SYN: synteza + Board_Nexys4DDR.v + PLL.v + ograniczenia .xdc
    SYN-->>FPGA: bitstream
    Dev->>FPGA: wciśnij reset
    FPGA->>FPGA: PC = 0, start jądra nuc
    FPGA->>TERM: UART: banner / "ok"
    TERM->>FPGA: include swapforth.fs (reszta systemu Forth)
    TERM->>FPGA: $ffff leds!
    FPGA->>FPGA: io! na adres 0x40 → LEDArray
```

## 6. Kluczowe parametry domyślnego systemu (`forth16Jtag` + `nexys4DDR`)

| Parametr | Wartość | Skąd |
|----------|---------|------|
| Szerokość słowa | 16 bit | `J1Config.forth16Jtag.wordSize` |
| Pamięć główna | 2¹² = 4096 słów = 8 KB, 2 banki po 2048 | `adrWidth = 12`, `numOfRAMs = 2` |
| PC | 13 bitów (12 adresu + 1 bit "high call") | `J1PC` |
| Stos danych | 2⁵ = 32 komórki (+ rejestr T) | `dataStackIdxWidth = 5` |
| Stos powrotów | 2⁵ = 32 komórki | `returnStackIdxWidth = 5` |
| Przerwania | 4 (3 wewnętrzne + 1 zewnętrzne) | `noOfInterrupts = 4`, `noOfInternalInterrupts = 3` |
| Zegar | 100 MHz | `CoreConfig.nexys4DDR` |
| UART | 115200 bodów, 8N1, FIFO 8 | `J1UARTConfig.slowUartConfig` |
| JTAG | IR 5 bitów, IDCODE 0x01234567 | `JTAGConfig.default` |
