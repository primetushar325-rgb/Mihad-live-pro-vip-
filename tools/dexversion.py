#!/usr/bin/env python3
"""Print the dex version string (e.g. '035') of a .dex file."""
import sys

with open(sys.argv[1], "rb") as f:
    data = f.read(8)
print(data[4:7].decode("ascii"))
