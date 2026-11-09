( J1 base words + mnozenie sprzetowe              newhope 2026 )
\ Dla rdzenia z J1Config.hasMul = true (J1Config.forth16Mul): bit 12 instrukcji ALU
\ wlacza operacje rozszerzone, liczone na jednym DSP48A1 (J1Alu.scala):
\   N*T   h# 1000   mlodsze 16 b iloczynu N * T
\   N*Th  h# 1100   starsze 16 b iloczynu bez znaku
\ nuc.fs wybiera przez `hwmul [if]` sprzetowe * i um* zamiast petli mulstep.
\   gforth cross.fs basewords-mul.fs nuc.fs   (make nucleus-mul -> build/16bit/nuc-mul.binary)

include basewords.fs

: N*T       h# 1000 ;
: N*Th      h# 1100 ;

:: mul-lo    N*T                 d-1 alu ;     ( u1 u2 -- lo )
:: mul-hi    N*Th                d-1 alu ;     ( u1 u2 -- hi )
:: hwmul     true ;
