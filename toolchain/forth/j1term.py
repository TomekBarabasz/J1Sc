#!/usr/bin/env python3
"""j1term - minimal terminal / loader for SwapForth on J1Sc (Python 3, pyserial).

Same tethered protocol as shell.py (`1 tth !`: every response ends with 0x1E),
but without the DTR/RTS reset, so it works with boards whose USB-UART bridge
does not expose those lines (Numato Mimas V2: press SW1 instead).

    python3 j1term.py -d /dev/ttyACM0 -b 19200 swapforth.fs     # load, then interactive
    python3 j1term.py -d COM5 -b 19200                           # interactive only

Files are sent line by line; `include <file>` / `#include <file>` are expanded on the host
(relative to the including file, then -p paths),
loading stops at the first line whose response contains "error".
Interactive: type Forth, Ctrl-D / Ctrl-C to quit, `#include <file>` to load a file.
"""
import argparse
import os
import sys

try:
    import serial
except ImportError:
    sys.exit("j1term needs pyserial: pip install pyserial")

EOT = b"\x1e"


class J1:
    def __init__(self, port, baud):
        self.ser = serial.Serial(port, baud, timeout=None, rtscts=False)

    def connect(self):
        """Puts the nucleus into tethered mode and waits for the first 0x1E."""
        sys.stdout.write("Contacting... ")
        sys.stdout.flush()
        self.ser.reset_input_buffer()
        self.ser.write(b"\r 1 tth !\r")
        self._read_until_eot()
        self.ser.reset_input_buffer()
        print("established")

    def _read_until_eot(self):
        buf = bytearray()
        while True:
            c = self.ser.read(max(1, self.ser.in_waiting))
            buf += c
            if EOT in c:
                return buf.replace(EOT, b"").decode("utf-8", "replace")

    def command(self, line):
        self.ser.write(line.encode("utf-8") + b"\r")
        r = self._read_until_eot()
        if r.startswith(" "):
            r = r[1:]
        return r

    def include(self, path, searchpath):
        for d in [""] + searchpath:
            p = os.path.join(d, path)
            if os.path.exists(p):
                break
        else:
            raise FileNotFoundError(path)
        with open(p, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                line = line.rstrip("\r\n").expandtabs(4)
                words = line.split()
                if words[:1] in (["include"], ["#include"]):      # expanded on the host, like shell.py
                    if not self.include(words[1], searchpath + [os.path.dirname(p)]):
                        return False
                    continue
                if line.startswith("#"):                           # other shell.py host commands
                    print(f"[{p}:{n}: host command ignored: {line}]")
                    continue
                r = self.command(line)
                if "error" in r:
                    print(f"\n--- ERROR in {p}:{n} ---\n{line}\n{r}")
                    return False
                out = r.rstrip()
                if out.endswith("ok"):
                    out = out[:-2].rstrip()
                if out:                                            # only real output, not every "ok"
                    print(out)
        print(f"[{p} loaded]")
        return True

    def shell(self, searchpath):
        while True:
            try:
                line = input()
            except (EOFError, KeyboardInterrupt):
                print()
                return
            if line.startswith("#include "):
                self.include(line.split()[1], searchpath)
            else:
                sys.stdout.write(self.command(line))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-d", "--device", required=True, help="serial port, e.g. /dev/ttyACM0 or COM5")
    ap.add_argument("-b", "--baud", type=int, default=19200)
    ap.add_argument("-p", "--path", action="append", default=[], help="search path for files")
    ap.add_argument("files", nargs="*", help="Forth sources to load before the shell")
    a = ap.parse_args()

    j1 = J1(a.device, a.baud)
    j1.connect()
    for f in a.files:
        if not j1.include(f, a.path):
            break
    j1.shell(a.path)


if __name__ == "__main__":
    main()
