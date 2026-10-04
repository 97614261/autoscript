"""Standalone CLI and GUI entrypoint."""
import argparse
import json
import pathlib
import sys
import zipfile

from .core import Cancelled, Converter, LegacyArchive
from .validation import bundled_validator, compiler_gate
from .labels import failure_message
from . import __version__


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="独立旧工程转换器 · 原文件不修改 · 未支持项明确报告")
    parser.add_argument("--version", action="version", version="智构工程转换工具 " + __version__)
    parser.add_argument("archive", nargs="?", type=pathlib.Path, help="旧工程ZIP")
    parser.add_argument("--gui", action="store_true", help="打开独立图形界面")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--inspect", action="store_true", help="只分析，不写转换工程")
    mode.add_argument("--output", type=pathlib.Path, help="不存在的新输出目录")
    parser.add_argument("--entry", help="明确指定入口插件文件名，例如 main.cj")
    parser.add_argument("--validator", type=pathlib.Path, help="可信的只读Rust编译检查器，默认使用便携包自带版本")
    args = parser.parse_args(argv)
    if args.gui or args.archive is None:
        from .gui import main as gui_main
        return gui_main()
    if not args.inspect and args.output is None:
        parser.error("请选择 --inspect 或 --output")
    try:
        archive = LegacyArchive(args.archive)
        try:
            validator = args.validator or bundled_validator()
            result = archive.inspect() if args.inspect else Converter(archive).convert(
                args.output, args.entry, compiler_gate(validator) if validator else None)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 2 if result.get("status") == "blocked" else 0
        finally:
            archive.close()
    except Cancelled:
        print("转换已取消，原文件不修改。", file=sys.stderr)
        return 130
    except KeyboardInterrupt:
        print("已中断，原文件不修改。", file=sys.stderr)
        return 130
    except (OSError, ValueError, TimeoutError, zipfile.BadZipFile) as error:
        # Never print private source fields, paths, or archive decoder exception payloads.
        print("转换失败：" + failure_message(error) + " 原文件未修改。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
