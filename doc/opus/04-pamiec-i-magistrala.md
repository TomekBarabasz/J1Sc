# 04 · Pamięć główna i magistrala peryferiów

[← spis treści](README.md) · źródła: [J1.scala](../src/main/scala/J1.scala), [MainMemory.scala](../src/main/scala/MainMemory.scala), [J1Bus.scala](../src/main/scala/J1Bus.scala), [J1BusSlaveFactory.scala](../src/main/scala/J1BusSlaveFactory.scala)

## 1. `J1`: procesor jako całość

[J1.scala](../src/main/scala/J1.scala) to `Component`, który łączy `J1Core` z `MainMemory` i wystawia na zewnątrz
**magistralę `cpuBus`** (master) oraz wejścia `stall`, `irq`, `intVec` i (opcjonalnie) port JTAG do pamięci.

```mermaid
graph LR
    subgraph J1["J1"]
        CORE["J1Core"]
        MM["MainMemory"]
        WMUX{"stall?<br/>(tylko gdy hasJtag)"}
        RMUX{"ioReadMode?"}

        CORE -- "nextInstrAdr" --> MM
        MM -- "readData → memInstr" --> CORE

        CORE -- "memWriteMode,<br/>extAdr[12:1], extToWrite" --> WMUX
        JTAGM["jtagMemBus<br/>captureMemory, adr, word"] --> WMUX
        WMUX -- "writeEnable,<br/>writeDataAdr, writeData" --> MM

        RMUX -- "toRead" --> CORE
    end

    CORE -- "enable = ioWrite or ioRead<br/>writeMode = ioWrite<br/>address = Delay(extAdr, 1)<br/>writeData = extToWrite" --> BUS["cpuBus (J1Bus master)"]
    BUS -- "readData" --> RMUX
    ZERO["0"] --> RMUX
```

* **Port instrukcji** (tylko odczyt): `nextInstrAdr` → `readDataAdr`, `readData` → `memInstr`.
* **Port zapisu**: normalnie steruje nim rdzeń. Gdy `hasJtag` i CPU jest zatrzymany (`stall`), przejmuje go JTAG.
  Tak wgrywa się program bez resyntezy (patrz [07](07-jtag-i-debug.md)).
* **Adres bajtowy → adres słowa**: `extAdr(cfg.adrWidth downto 1)` odrzuca bit 0. Forth adresuje bajty, RAM słowa.
* **Odczyt I/O**: `toRead = ioReadMode ? cpuBus.readData : 0`.
* **Adres na magistrali** to `Delay(extAdr, 1)`. Dlaczego, wyjaśnia §4.

## 2. `MainMemory`: RAM sklejony z banków

[MainMemory.scala](../src/main/scala/MainMemory.scala). Komentarz w nagłówku tłumaczy powód: Vivado 2016.4 nie umiał
poprawnie wygenerować dwuportowego BRAM > 64K, więc pamięć dzielona jest na `numOfRAMs` banków (potęga 2, min. 2).

```mermaid
graph TD
    RA["readDataAdr[11:0]"] --> SPLITR{"podział adresu"}
    SPLITR -- "[11] = numer banku" --> REGB["RegNext(bank)<br/>(wyrównanie z readSync)"]
    SPLITR -- "[10:0] = adres w banku" --> B0["ramList(0)<br/>2048 słów<br/>init: bootCode[0..2047]"]
    SPLITR -- "[10:0]" --> B1["ramList(1)<br/>2048 słów<br/>init: bootCode[2048..4095]"]
    B0 -- "readSync" --> VEC["rPortsVec"]
    B1 -- "readSync" --> VEC
    REGB --> SEL["rPortsVec(bank)"]
    VEC --> SEL
    SEL --> RD["readData"]

    WA["writeDataAdr[11:0]"] --> SPLITW{"podział adresu"}
    SPLITW -- "[11] == i  &&  writeEnable" --> WEN["enable zapisu banku i"]
    WEN --> B0
    WEN --> B1
```

* Każdy bank to `Mem(Bits(16 bits), initialContent)`. Spinal wypełnia go fragmentem `cfg.bootCode()`, więc
  **program jest "wypalony" w bitstreamie**. W Verilogu powstają pliki `*.v_toplevel_coreArea_cpu_mainMem_ramList_N.bin`
  ładowane przez `$readmemb` (stąd "ugly workaround" z kopiowaniem w `build.sbt`).
* Numer banku przy odczycie jest **zarejestrowany** (`RegNext`), bo dane z `readSync` przychodzą takt później.
  Multiplekser musi wybrać bank według adresu z *poprzedniego* taktu.
* Przy uruchomieniu generator wypisuje `[J1Sc] Create 2 RAMs which have 2048 cells each`.

## 3. `J1Bus` i `J1BusSlaveFactory`: magistrala peryferiów

### 3.1 Sygnały `J1Bus`

[J1Bus.scala](../src/main/scala/J1Bus.scala). Prosta magistrala synchroniczna (autorstwa Charlesa Papona, twórcy Spinala):

| Sygnał | Kierunek (z punktu widzenia mastera) | Znaczenie |
|--------|--------------------------------------|-----------|
| `enable` | out | transakcja w tym takcie |
| `writeMode` | out | 1 = zapis, 0 = odczyt |
| `address` | out | 16-bitowy adres I/O |
| `writeData` | out | dane do zapisu |
| `readData` | in | dane odczytane (kombinacyjnie, w tym samym takcie co `enable` widziany przez slave'a) |

Metody:
* `asMaster()`: ustala kierunki (`IMasterSlave`), więc `master(J1Bus(cfg))` w `J1` daje poprawne porty.
* `<<` / `>>`: połączenie dwóch magistrali.
* **`delayIt(ticks)`**: tworzy kopię, w której `address`, `enable`, `writeMode`, `writeData` są opóźnione o `ticks`
  taktów, a `readData` **nie jest** opóźniane. W ten sposób powstaje "wait state" dla peryferiów.

### 3.2 Fabryka slave'ów

[J1BusSlaveFactory.scala](../src/main/scala/J1BusSlaveFactory.scala) rozszerza `BusSlaveFactoryDelayed` ze
`spinal.lib`. To mechanizm, dzięki któremu peryferium pisze tylko:

```scala
busCtrl.read(io.leds, baseAddress + 0)          // rejestr do odczytu
busCtrl.nonStopWrite(bus.ledValue, 0)           // writeData zawsze podłączone do sygnału
bus.writeEnable := busCtrl.isWriting(baseAddress + 0)   // impuls przy zapisie pod adres
```

Fabryka **zbiera** te deklaracje od wszystkich peryferiów (`elementsPerAddress`), a w `build()` generuje jeden dekoder:

```mermaid
flowchart TD
    BUS["peripheralBus<br/>(po delayIt)"] --> ASK["askWrite = enable & writeMode<br/>askRead = enable & !writeMode"]
    ASK --> SW{"switch(bus.address)"}
    SW -- "0x40" --> E1["LEDArray: read / isWriting"]
    SW -- "0x50..0x55" --> E2["PWM"]
    SW -- "0x60..0x68" --> E3["SSD"]
    SW -- "..." --> E4["..."]
    SW -- "0xF0.." --> E5["UartCtrl"]
    ASK --> RANGE{"adresy zakresowe<br/>address.hit(...)"}
    E1 & E2 & E3 & E4 & E5 --> RD["bus.readData<br/>(domyślnie 0)"]
    RANGE --> RD
```

* `readHalt` / `writeHalt` rzucają wyjątek, bo peryferia nie mogą wstrzymać magistrali (brak sygnału `ready`).
* `bus.readData := 0` na początku zapobiega powstaniu zatrzasku (latch). Nieobsłużony adres czyta się jako 0.

## 4. Timing operacji na magistrali

Tu jest najwięcej subtelności. Trzeba pamiętać o trzech opóźnieniach:

1. `extAdr = dtosN`, czyli adres wyliczony **o takt za wcześnie** (wartość, którą T dopiero przyjmie).
2. `cpuBus.address = Delay(extAdr, 1)`. To kompensuje (1): w takcie wykonania instrukcji adres = bieżące T.
3. `peripheralBus = cpuBus.delayIt(ioWaitStates)` (domyślnie 1), czyli peryferium widzi żądanie **takt później**.

### 4.1 Zapis I/O: `io!` (`6043` + `6103`)

```mermaid
sequenceDiagram
    autonumber
    participant CPU as J1Core
    participant CB as cpuBus
    participant PB as peripheralBus (+1 takt)
    participant P as Peryferium (np. LEDArray)

    Note over CPU: takt n: T = adres, N = dana<br/>instr 6043 (T, N→io[T], d−1)
    CPU->>CB: enable=1, writeMode=1,<br/>address=T, writeData=N
    Note over CPU: takt n+1: instr 6103 (drop)
    CB->>PB: (opóźnienie delayIt)
    PB->>P: isWriting(0x40) = 1, nonStopWrite(dana)
    Note over P: takt n+2: rejestr zapisany
```

### 4.2 Odczyt I/O: `io@` (`6D50` dwa razy)

Dlaczego dwie identyczne instrukcje? Bo odpowiedź peryferium przychodzi takt po żądaniu:

```mermaid
sequenceDiagram
    autonumber
    participant CPU as J1Core
    participant CB as cpuBus
    participant PB as peripheralBus (+1 takt)
    participant P as Peryferium

    Note over CPU: takt n: T = adres<br/>instr 6D50 (io[T], _IORD_)
    CPU->>CB: enable=1, writeMode=0, address=T
    Note over CPU: ALU = toRead = śmieci<br/>(odpowiedź jeszcze nie przyszła),<br/>T := śmieci
    CB->>PB: żądanie z taktu n
    Note over CPU: takt n+1: znów 6D50
    PB->>P: askRead, adres z taktu n
    P-->>CPU: readData (kombinacyjnie, bez opóźnienia)
    Note over CPU: ioReadMode=1 → toRead = readData<br/>ALU op io[T] → T := wartość ✔
```

Druga instrukcja też wystawia żądanie odczytu, tyle że pod adres równy "śmieciowemu" T z pierwszej instrukcji.
Zwykle to nieszkodliwe, bo odczyt zwykłego rejestru nic nie zmienia. Teoretycznie, gdyby śmieć trafił w rejestr z
efektem ubocznym odczytu (FIFO RX UART pod `0xF0`), zostałby on wyzwolony. Dobre pytanie do zbadania w symulacji,
patrz [10-obserwacje.md](10-obserwacje.md).

### 4.3 Zapis do RAM: `!` (`6033` + `6103`)

Instrukcja `T N->[T] d-1`: ALU daje `T` (adres), więc `dtosN = T`, `extAdr = adres`, a `memWriteMode = 1`.
Port zapisu RAM używa `extAdr` **bez** opóźnienia (bo `dtosN == dtos`), więc zapis dzieje się w tym samym takcie.

## 5. Odczyt z RAM: sztuczka high call

ALU **nie ma** operacji "czytaj RAM pod adresem T" (`io[T]` czyta tylko magistralę I/O). Jak więc działa `@`?
Odpowiedź jest w [nuc.fs](../toolchain/forth/nuc.fs):

```forth
: execute  >r  : noop ;          \ execute = >r, potem exit (z noop)
: @        h# 2000 or execute ;  \ adres bajtowy | 0x2000, potem "skocz tam"
```

Krok po kroku dla `adres = 0x0100` (bajt 256 = słowo 128):

```mermaid
sequenceDiagram
    autonumber
    participant S as Stos danych
    participant R as Stos powrotów
    participant PC as J1PC
    participant M as MainMemory

    Note over S: T = 0x0100
    S->>S: h# 2000 or → T = 0x2100
    S->>R: >r: R = 0x2100 (adres bajtowy z ustawionym bitem 13)
    R->>PC: exit: pcN = R/2 = 0x1080<br/>bit 12 (MSB pc) = 1, adres słowa = 0x080
    PC->>M: nextInstrAdr = 0x080 (bez MSB)
    Note over PC: następny takt: pc.msb = 1 → tryb HIGH CALL
    M-->>S: memInstr = mem[0x80] → Decoder: T := instr (dsp+1)
    R->>PC: rsp−1, pcN := R/2 → powrót do wywołującego @
```

Czyli: ustawienie najstarszego bitu PC zamienia **słowo pobrane jako instrukcja** w **daną** wpychaną na stos. Od
razu następuje też powrót, tak jakby wykonano `exit`. Dodatkowy bit PC kosztuje jeden przerzutnik, a oszczędza
drugi port odczytu RAM.

`c@` jest zbudowane na `@`: czyta słowo i przesuwa o 8 bitów, jeśli adres jest nieparzysty.

## 6. Podsumowanie: kto adresuje co

| Operacja | Instrukcje | Adres z | Port | Opóźnienie |
|----------|-----------|---------|------|------------|
| pobranie instrukcji | — | `pcN` | RAM odczyt (readSync) | 0 (dzięki `pcN`) |
| `@` (odczyt RAM) | `or` `>r` `exit` | R/2 z MSB | RAM odczyt (ten sam!) | 1 takt high call |
| `!` (zapis RAM) | `6033 6103` | `dtosN` (= T) | RAM zapis | 0 |
| `io!` | `6043 6103` | `Delay(dtosN)` → `delayIt` | J1Bus | +1 (wait state) |
| `io@` | `6D50 6D50` | jw. | J1Bus | wynik w drugiej instrukcji |
| JTAG zapis RAM | — | `jtagMemAdr` | RAM zapis (gdy stall) | — |
