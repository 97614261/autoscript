"""Host-only checks of the shipped model and native algorithm; no device or APK UI test.

Optional dependencies are isolated under build/native-vision-qa-deps.
"""
import hashlib
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/native-vision-qa-deps"))
import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

model = ROOT / "android/runtime-service/src/main/assets/vision/en_PP-OCRv4_rec_mobile.onnx"
assert hashlib.sha256(model.read_bytes()).hexdigest() == "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"
options = ort.SessionOptions()
options.intra_op_num_threads = options.inter_op_num_threads = 1
options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
session = ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
dictionary = [""] + session.get_modelmeta().custom_metadata_map["character"].rstrip("\n").split("\n") + [" "]
assert len(dictionary) == session.get_outputs()[0].shape[2] == 97
assert set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789") <= set(dictionary)

font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 32)
for expected in ["ABCXYZ", "abcxyz", "0123456789", "Hello123", "A1B2c3"]:
    box = font.getbbox(expected)
    image = Image.new("RGB", (box[2] - box[0] + 20, 48), "white")
    ImageDraw.Draw(image).text((10, 4), expected, font=font, fill="black")
    bgr = cv2.cvtColor(np.asarray(image), cv2.COLOR_RGB2BGR)
    width = math.ceil(48 * bgr.shape[1] / bgr.shape[0])
    normalized = cv2.resize(bgr, (width, 48)).astype(np.float32).transpose(2, 0, 1) / 127.5 - 1
    tensor = np.zeros((1, 3, 48, max(320, width)), dtype=np.float32)
    tensor[0, :, :, :width] = normalized
    result = session.run(None, {session.get_inputs()[0].name: tensor})[0][0]
    output, scores, previous = [], [], -1
    for step in result:
        index = int(step.argmax())
        symbol = dictionary[index]
        if index and index != previous and step[index] >= .5 and len(symbol) == 1 and symbol.isascii() and symbol.isalnum():
            output.append(symbol)
            scores.append(float(step[index]))
        previous = index
    actual = "".join(output)
    assert actual == expected, (expected, actual)
    print("OCR sample:", expected, "scorePermille:", int(np.mean(scores) * 1000))

# Known template spanning a tile boundary with RGBA row padding and a nonzero ROI.
rng = np.random.default_rng(418)
source = rng.integers(30, 200, (110, 150, 4), dtype=np.uint8)
source[:, :, 3] = 255
template = source[61:74, 89:106].copy()
gray = cv2.cvtColor(source, cv2.COLOR_RGBA2GRAY)
tpl = cv2.cvtColor(template, cv2.COLOR_RGBA2GRAY)
roi = gray[7:103, 5:145]
best = (1.0, 0, 0)
cw, ch = roi.shape[1] - tpl.shape[1] + 1, roi.shape[0] - tpl.shape[0] + 1
for y in range(0, ch, 8):
    for x in range(0, cw, 11):
        w, h = min(11, cw-x), min(8, ch-y)
        tile = roi[y:y+h+tpl.shape[0]-1, x:x+w+tpl.shape[1]-1]
        values = cv2.matchTemplate(tile, tpl, cv2.TM_SQDIFF_NORMED)
        low, _, pos, _ = cv2.minMaxLoc(values)
        candidate = (low, y+pos[1]+7, x+pos[0]+5)
        if candidate < best:
            best = candidate
assert best[1:] == (61, 89), best
assert int((1-best[0])*1000) >= 999
print("Gray match: x=89 y=61; native dependencies", cv2.__version__, ort.__version__)
