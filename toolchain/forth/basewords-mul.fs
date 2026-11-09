( J1 base words + mnozenie na DSP48A1              newhope 2026 )
\ Dla rdzenia z J1Config.hasMul = true (J1Config.forth16Mul): bit 12 instrukcji ALU
\ wlacza kanal mnozenia P0 (32 b) J1MulUnit (j1/hw/spinal/main/J1MulUnit.scala):
\   MUL  h# 1000  P0 := N*T      MULS h# 1100  ze znakiem       ALU: T := N
\   MULL h# 1200  T := P0[15:0]  MULH h# 1300  T := P0[31:16]
\ Odczyt P0 przy niepustym potoku wstrzymuje rdzen sprzetowo - bez NOP-ow.
\ nuc.fs wybiera przez `hwmul [if]` sprzetowe *, um*, m*.
\   gforth cross.fs basewords-mul.fs nuc.fs   (make nucleus-mul -> build/16bit/nuc-mul.binary)
\ MAC (kanal P1): basewords-mac.fs.

include basewords.fs

: MUL       h# 1000 ;
: MULS      h# 1100 ;
: MULL      h# 1200 ;
: MULH      h# 1300 ;

:: dsp-mul     MUL                 d-1 alu ;     \ ( u1 u2 -- u1 )  P0 := u1*u2
:: dsp-muls    MULS                d-1 alu ;     \ ( n1 n2 -- n1 )  P0 := n1*n2
:: dsp-mul@    MULL                    alu ;     \ ( x -- lo )      T := P0[15:0]
:: dsp-mul@h   MULH  T->N          d+1 alu ;     \ ( -- hi )        push P0[31:16]
:: hwmul       true ;
