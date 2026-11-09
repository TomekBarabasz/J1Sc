( J1 base words + mnozenie / MAC na DSP48A1        newhope 2026 )
\ Dla rdzenia z J1Config.hasMul = true (J1Config.forth16Mul): bit 12 instrukcji ALU
\ wlacza operacje J1MulUnit (j1/hw/spinal/main/J1MulUnit.scala). Dwa kanaly DSP48A1:
\   P0 (32 b) dla *, um*, m*  - uzywa go tez samo jadro (parsowanie liczb)
\   P1 (48 b) akumulator MAC  - ruszaja go tylko slowa MAC
\   MUL  h# 1000  P0 := N*T      MULS h# 1100  ze znakiem       ALU: T := N
\   MULL h# 1200  T := P0[15:0]  MULH h# 1300  T := P0[31:16]
\   MAC  h# 1400  P1 += N*T      MACS h# 1500  ze znakiem       ALU: T := N
\   ACCS h# 1600  P1 := N*T                                     ALU: T := N
\   ACCL h# 1800  T := P1[15:0]  ACCH h# 1900  [31:16]   ACCX h# 1a00  [47:32]
\ Odczyt P przy niepustym potoku wstrzymuje rdzen sprzetowo - bez NOP-ow.
\ nuc.fs wybiera przez `hwmul [if]` sprzetowe *, um*, m* i slowa MAC.
\   gforth cross.fs basewords-mul.fs nuc.fs   (make nucleus-mul -> build/16bit/nuc-mul.binary)

include basewords.fs

: MUL       h# 1000 ;
: MULS      h# 1100 ;
: MULL      h# 1200 ;
: MULH      h# 1300 ;
: MAC       h# 1400 ;
: MACS      h# 1500 ;
: ACCS      h# 1600 ;
: ACCL      h# 1800 ;
: ACCH      h# 1900 ;
: ACCX      h# 1a00 ;

:: dsp-mul     MUL                 d-1 alu ;     \ ( u1 u2 -- u1 )  P0 := u1*u2
:: dsp-muls    MULS                d-1 alu ;     \ ( n1 n2 -- n1 )  P0 := n1*n2
:: dsp-mul@    MULL                    alu ;     \ ( x -- lo )      T := P0[15:0]
:: dsp-mul@h   MULH  T->N          d+1 alu ;     \ ( -- hi )        push P0[31:16]
:: dsp-mac     MAC                 d-1 alu ;     \ ( u1 u2 -- u1 )  P1 += u1*u2
:: dsp-macs    MACS                d-1 alu ;     \ ( n1 n2 -- n1 )  P1 += n1*n2
:: dsp-acc!    ACCS                d-1 alu ;     \ ( u1 u2 -- u1 )  P1 := u1*u2
:: dsp-acc@    ACCL                    alu ;     \ ( x -- lo )      T := P1[15:0]
:: dsp-acc@h   ACCH  T->N          d+1 alu ;     \ ( -- hi )        push P1[31:16]
:: dsp-acc@x   ACCX  T->N          d+1 alu ;     \ ( -- x )         push P1[47:32]
:: hwmul       true ;
