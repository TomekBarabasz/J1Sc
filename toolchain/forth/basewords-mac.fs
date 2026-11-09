( J1 base words + mnozenie i MAC na DSP48A1       newhope 2026 )
\ Dla rdzenia z J1Config.hasMul i hasMac (J1Config.forth16MulMac): basewords-mul.fs
\ plus kanal MAC P1 (akumulator 48 b) J1MulUnit:
\   MAC  h# 1400  P1 += N*T      MACS h# 1500  ze znakiem       ALU: T := N
\   ACCS h# 1600  P1 := N*T                                     ALU: T := N
\   ACCL h# 1800  T := P1[15:0]  ACCH h# 1900  [31:16]   ACCX h# 1a00  [47:32]
\ nuc.fs dodaje przez `hwmac [if]` slowa acc0 acc! mac macs acc@ acc@x.
\   gforth cross.fs basewords-mac.fs nuc.fs   (make nucleus-mac -> build/16bit/nuc-mac.binary)

include basewords-mul.fs

: MAC       h# 1400 ;
: MACS      h# 1500 ;
: ACCS      h# 1600 ;
: ACCL      h# 1800 ;
: ACCH      h# 1900 ;
: ACCX      h# 1a00 ;

:: dsp-mac     MAC                 d-1 alu ;     \ ( u1 u2 -- u1 )  P1 += u1*u2
:: dsp-macs    MACS                d-1 alu ;     \ ( n1 n2 -- n1 )  P1 += n1*n2
:: dsp-acc!    ACCS                d-1 alu ;     \ ( u1 u2 -- u1 )  P1 := u1*u2
:: dsp-acc@    ACCL                    alu ;     \ ( x -- lo )      T := P1[15:0]
:: dsp-acc@h   ACCH  T->N          d+1 alu ;     \ ( -- hi )        push P1[31:16]
:: dsp-acc@x   ACCX  T->N          d+1 alu ;     \ ( -- x )         push P1[47:32]
:: hwmac       true ;
