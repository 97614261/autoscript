"""Compatibility test loader; tests are maintained with the standalone tool."""
import pathlib
import runpy
import sys

tool = pathlib.Path(__file__).resolve().parents[1] / "tools" / "legacy-converter"
sys.path.insert(0, str(tool))
LegacyProjectTests = runpy.run_path(str(tool / "tests" / "test_core.py"))["LegacyProjectTests"]
