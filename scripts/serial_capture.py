#!/usr/bin/env python
"""Capture the bench unit's serial console to a log file with timestamps.

  python scripts/serial_capture.py [--port COM7] [--baud 115200] [--seconds 0]
                                    [--no-reset] [--out serial-capture.log]

A seconds value of 0 (the default) runs until the process is stopped. Each line
is prefixed with the wall-clock time so a serial line and a phone-side event can
be put next to each other - which is the whole point: an install that "failed"
in the app and one that "timed out" on the unit are the same claim only when the
timestamps say which happened first.

This is a debug aid, not part of the firmware or the app.
"""
import argparse
import sys
import time
from datetime import datetime

import serial


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="COM7")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--seconds", type=float, default=0.0,
                    help="stop after this many seconds (0 = run until killed)")
    ap.add_argument("--out", default="serial-capture.log")
    ap.add_argument("--no-reset", action="store_true",
                    help="do not pulse DTR/RTS to restart the board on open")
    args = ap.parse_args()

    ser = serial.Serial(args.port, args.baud, timeout=0.2)
    if not args.no_reset:
        # The classic Arduino-style reset pulse: DTR/RTS low, then released.
        ser.dtr = False
        ser.rts = True
        time.sleep(0.2)
        ser.rts = False
        time.sleep(0.1)
        ser.dtr = True

    started = time.time()
    buf = b""
    with open(args.out, "ab", buffering=0) as out:
        out.write(b"\n==== capture started %s port=%s baud=%d ====\n" % (
            datetime.now().isoformat(timespec="milliseconds").encode(),
            args.port.encode(), args.baud))
        while True:
            chunk = ser.read(4096)
            if chunk:
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                    out.write(("%s %s\n" % (stamp, line.decode("utf-8", "replace"))).encode())
            if args.seconds and time.time() - started > args.seconds:
                break
    ser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
