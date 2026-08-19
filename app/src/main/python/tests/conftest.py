import os
import sys

# drive_monitor.py is a plain-stdlib module (see its own module docstring:
# only sqlite3/time/math/json/os/re/datetime at import time) meant to be
# loaded by Chaquopy on Android, but nothing about it actually requires
# Android/Chaquopy to import or exercise its logic directly. This lets the
# Ralph loop (and anyone else) run real, fast, no-emulator-needed tests
# against the actual production module instead of a reimplementation.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
