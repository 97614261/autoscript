"""Generate only the public fixture project/ZIP in the repository's ignored build directory."""
import json
import base64
import pathlib
import shutil
import struct
import zlib
import zipfile


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def build():
    root = pathlib.Path(__file__).resolve().parents[1]
    source = root / "examples/native-workflow-project"
    output = root / "build/native-example/project"
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((source / "project.json").read_text(encoding="utf-8"))
    paths = ["project.json"] + [f["path"] for f in manifest["flows"]]
    for relative in paths:
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / relative, target)
    # Synthetic 1x1 RGBA template, not an attached/user screenshot or pretrained model.
    pixels = bytes([0, 10, 20, 30, 255])
    image = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
             + png_chunk(b"IDAT", zlib.compress(pixels)) + png_chunk(b"IEND", b""))
    fixture = base64.b64decode((source / "assets/images/tile.png.base64").read_text(encoding="ascii").strip(), validate=True)
    assert image == fixture, "Public fixture bytes changed"
    path = "assets/images/tile.png"
    (output / path).parent.mkdir(parents=True, exist_ok=True)
    (output / path).write_bytes(image)
    archive = root / "build/native-example/native-workflow.zip"
    with zipfile.ZipFile(str(archive), "w", zipfile.ZIP_DEFLATED) as bundle:
        for relative in paths + [path]:
            bundle.write(str(output / relative), relative)
    print("Public example generated: " + str(archive))


if __name__ == "__main__":
    build()
