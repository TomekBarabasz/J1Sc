# 10 · Obserwacje z lektury kodu

[← spis treści](README.md)

Rzeczy zauważone podczas analizy, które nie są oczywiste albo wyglądają na błąd. **Nie były testowane** w symulacji
ani na sprzęcie. To hipotezy do sprawdzenia i dobre tematy na ćwiczenia (Etap 8).

## Możliwe błędy

### 1. Stos powrotów dziedziczy szerokość wskaźnika stosu *danych*

[J1RStack.scala:11](../src/main/scala/J1RStack.scala#L11):

```scala
case class J1RStack(cfg : J1Config) extends J1Stack(cfg.dataStackIdxWidth)  {   // ← data, nie return
  ...
  val stackMem = Mem(Bits(cfg.wordSize bits), wordCount = (1 << cfg.returnStackIdxWidth))
  val stackPtrInc = SInt(cfg.returnStackIdxWidth bits)
```

Wskaźnik ma `dataStackIdxWidth` bitów, a pamięć `2^returnStackIdxWidth` słów.
* W presetach Forth (`forth16Jtag`, `forth16`) obie wartości to 5, więc **nie ma skutków**.
* W `default16` (8 vs 4) i presetach debug (5 vs 4) szerokości się różnią. Wskaźnik nie zawija się wtedy na
  granicy pamięci, a Spinal może zgłosić niezgodność szerokości adresu.
* Poprawka: `extends J1Stack(cfg.returnStackIdxWidth)`.

### 2. Wybór wektora przerwania ignoruje maskę

[InterruptCtrl.scala:77-83](../src/main/scala/InterruptCtrl.scala#L77-L83):

```scala
val intNo = OHToUInt(OHMasking.first(irqSync))        // ← bez maski
internal.irq := (irqSync & irqMask).orR.rise(False)   // ← z maską
```

Scenariusz: UART (IRQ 0, najwyższy priorytet) ma dane, ale jest **zamaskowany**, a Timer A (IRQ 1) jest włączony.
Kiedy timer zgłosi przerwanie, `irq` się pojawi, ale `intVec` wskaże wektor **UART-a**. Poprawka:
`OHMasking.first(irqSync & irqMask)`.

### 3. Przerwania wyzwalane zboczem sumy

`irq = (irqSync & irqMask).orR.rise`. Jeśli jedno źródło trzyma stan wysoki (np. `readInt` UART-a, dopóki FIFO nie
jest puste), żadne inne przerwanie nie wygeneruje zbocza. Timery dają impulsy 1-taktowe, więc problem dotyczy
głównie UART-a i pinu zewnętrznego.

### 4. GPIO: wejścia "lepkie"

[GPIO.scala:63](../src/main/scala/GPIO.scala#L63): bez zapisu `dataRegN := dataReg | (io.dataIn & ~io.directions)`.
Jedynka na wejściu zostaje w rejestrze na zawsze (OR), a wyzerować ją może tylko zapis pod `0x74`. Nie wiadomo, czy
to zamierzone ("latch na zdarzenie") czy błąd. Spodziewana wersja to raczej
`(dataReg & io.directions) | (io.dataIn & ~io.directions)`.

### 5. "ISA bug" w instrukcjach ALU z `d+1` bez `T->N`

Opisany przez autora w komentarzu ([J1DStack.scala:54](../src/main/scala/J1DStack.scala#L54)), szczegóły w
[02 §7](02-isa.md#7-przykład-isatest-zdekodowany). SwapForth tego nie używa.

## Pytania otwarte (do zbadania w symulacji)

### 6. Drugie żądanie odczytu w `io@`

Druga instrukcja `6D50` wysyła na magistralę żądanie odczytu pod adres równy "śmieciowemu" T
([04 §4.2](04-pamiec-i-magistrala.md#42-odczyt-io-io-6d50-dwa-razy)). Czy może to przypadkiem trafić w rejestr z
efektem ubocznym odczytu (FIFO RX UART)?

### 7. Przerwanie wewnątrz sekwencji dwuinstrukcyjnej

Jeśli `irq` przyjdzie między dwiema instrukcjami `io@` (albo między `6033` a `6103` w `!`), druga część wykona się
dopiero po ISR. Dla `io@` druga instrukcja liczy na odpowiedź na żądanie z *poprzedniego taktu*. Po powrocie z ISR
tego żądania już nie ma.

## Rzeczy nieużywane lub nieaktualne

| Co | Gdzie | Uwagi |
|----|-------|-------|
| `SingleStepUnit` | [SingleStepUnit.scala](../src/main/scala/SingleStepUnit.scala) | nigdzie nie instancjonowany |
| zakomentowane pola | [J1DStack.scala:13-18](../src/main/scala/J1DStack.scala#L13-L18) | ślad po refaktoryzacji do `J1Stack` |
| komentarz "makes a 80Mhz clock" | [Board_Nexys4DDR.v](../src/main/verilog/arch/Nexys4DDR/Board_Nexys4DDR.v) | PLL ustawiony na 100 MHz (`CLKOUT0_DIVIDE_F = 10.0`), zgodnie z `CoreConfig.nexys4DDR` |
| komunikat "IcoBoard" | [J1BoardSim.scala](../src/test/scala/J1BoardSim.scala) | symulacja kompiluje `J1Ice` |
| obcinanie końca RAM o `noOfInterrupts` | presety Forth w `J1Config` | wektory przerwań nie są już w RAM, tylko w `InterruptCtrl` |
| `lazy val J1Sc = RootProject(uri("git://..."))` | [build.sbt](../build.sbt) | protokół `git://` GitHub wyłączył w 2022, a wartość i tak nie jest używana |
| asercja "Too many pwm channels!" | [LEDArray.scala:16](../src/main/scala/LEDArray.scala#L16) | skopiowany komunikat, dotyczy diod |
| `numOfInterrupts = 8` przy `noOfInternalInterrupts = 1` | `J1Config.default16` | topy podłączają na sztywno 3 wewnętrzne źródła (UART, 2× timer) do `irqReqs(0..2)`. Z tym presetem bity 1–2 byłyby sterowane jednocześnie przez `extInt` i timery |
