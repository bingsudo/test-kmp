"""Writes a small synthetic 'laggy' clip: 120 frames of a moving box where the picture only changes every 3rd frame."""
import sys

import cv2
import numpy as np

path = sys.argv[1]
w, h = 640, 360
writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), 30, (w, h))
rng = np.random.default_rng(1)
frame = None
for i in range(120):
    if i % 3 == 0:  # a new picture only every third frame = two duplicates in between
        frame = np.full((h, w, 3), 40, np.uint8)
        frame += rng.integers(0, 10, frame.shape, dtype=np.uint8)
        x = 20 + (i // 3) * 14
        cv2.rectangle(frame, (x, 120), (x + 90, 240), (30, 200, 255), -1)
    writer.write(frame)
writer.release()
print("wrote", path)
