"""Compatibility launcher; implementation lives in tools/legacy-converter."""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools" / "legacy-converter"))
from legacy_converter.core import Converter, LegacyArchive, bmp_png, fields, record_lines, tree
from legacy_converter.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
