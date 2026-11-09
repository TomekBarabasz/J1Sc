# Jadro SwapForth dla J1: dwa obrazy z tego samego nuc.fs
#   nuc.binary      mnozenie programowe (um* = petla mulstep)      - J1Config.forth16
#   nuc-mul.binary  mnozenie sprzetowe (N*T / N*Th, bit 12 ALU)    - J1Config.forth16Mul
all: nucleus nucleus-mul

nucleus: cross.fs basewords.fs nuc.fs
	gforth cross.fs basewords.fs nuc.fs
	mv build/nuc.binary build/16bit/nuc.binary
	mv build/nuc.hex    build/16bit/nuc.hex
	mv build/nuc.lst    build/16bit/nuc.lst

nucleus-mul: cross.fs basewords.fs basewords-mul.fs nuc.fs
	gforth cross.fs basewords-mul.fs nuc.fs
	mv build/nuc.binary build/16bit/nuc-mul.binary
	mv build/nuc.hex    build/16bit/nuc-mul.hex
	mv build/nuc.lst    build/16bit/nuc-mul.lst

clean:
	rm -f build/16bit/nuc*.hex build/16bit/nuc*.binary build/16bit/nuc*.lst

.PHONY: all nucleus nucleus-mul clean
