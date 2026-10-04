"""Build a portable ZIP from an explicit source whitelist, never user outputs."""
import argparse
import hashlib
import json
import pathlib
import tempfile
import zipfile
from legacy_converter import __version__


def fingerprint(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def package(output, validator=None):
    root = pathlib.Path(__file__).resolve().parent
    output = pathlib.Path(output).absolute()
    if output.exists():
        raise ValueError("package output already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    modules = ("__init__.py", "__main__.py", "cli.py", "core.py", "diagnostics.py", "function_audit.py",
               "gui.py", "labels.py", "organization.py", "report.py", "requirements.py", "validation.py")
    files = [root / "start.cmd", root / "README.md"] + [root / "legacy_converter" / name for name in modules]
    if any(not path.is_file() for path in files):
        raise ValueError("portable source files missing")
    if validator:
        validator = pathlib.Path(validator)
        if not validator.is_file() or validator.stat().st_size > 128 * 1024 * 1024:
            raise ValueError("validator missing or exceeds budget")
    with tempfile.NamedTemporaryFile(prefix=".packaging-", suffix=".zip", dir=output.parent, delete=False) as stream:
        temporary = pathlib.Path(stream.name)
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as bundle:
        checksums = {}
        for path in files:
            bundle.write(path, "智构工程转换工具/" + path.relative_to(root).as_posix())
            checksums[path.relative_to(root).as_posix()] = fingerprint(path)
        if validator:
            bundle.write(validator, "智构工程转换工具/bin/validate_project.exe")
            checksums["bin/validate_project.exe"] = fingerprint(validator)
        bundle.writestr("智构工程转换工具/bundle.json", json.dumps({"version": 1, "toolVersion": __version__,
            "requiresPython": "3.8+ with Tk", "sha256": checksums}, ensure_ascii=False, indent=2))
    if output.exists() or output.is_symlink():
        raise FileExistsError("package output appeared during packaging")
    temporary.rename(output)
    return {"packaged": True, "toolVersion": __version__, "bundledCompiler": bool(validator), "requiresPython": "3.8+ with Tk"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="打包独立便携工具，不包含用户工程")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--validator", type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(package(args.output, args.validator), ensure_ascii=False))
