#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MGMemo 图标生成器：纯 Python 绘制 + PNG 编码（无需 PIL）。
输出各密度 mipmap PNG（API < 26 的旧设备使用）。
设计空间 108x108，与 drawable/ic_launcher_foreground.xml 保持一致。
"""
import os, zlib, struct, math

RES = "/data/user/0/com.ai.assistance.operit/files/workspace/MGmemo/app/src/main/res"
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# ---------- 几何工具（坐标均为 108 设计空间） ----------

def seg_dist(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def rrect_sdf(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    if qx <= 0 and qy <= 0:
        return max(qx, qy) - r
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) - r


def tri_inside(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    has_neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    has_pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (has_neg and has_pos)


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def blend(dst, src, alpha):
    if alpha <= 0:
        return dst
    if alpha >= 1:
        return src
    return tuple(int(dst[i] * (1 - alpha) + src[i] * alpha + 0.5) for i in range(4))


# ---------- 绘制 ----------

PAGE_WHITE = (255, 255, 255, 255)
FOLD_BLUE = (199, 230, 201, 255)
MARK_BLUE = (46, 107, 58, 255)
LINE_LIGHT = (199, 230, 201, 255)
LINE_LIGHTER = (220, 237, 221, 255)
GRAD_A = (90, 168, 94)
GRAD_B = (36, 82, 31)

M_STROKES = [((40, 62), (40, 46)), ((40, 46), (47, 54)),
             ((47, 54), (54, 46)), ((54, 46), (54, 62))]
ARROW_STROKES = [((63, 46), (63, 59)), ((58.5, 54.5), (63, 59)), ((63, 59), (67.5, 54.5))]
STROKE_W = 4.2
RECTS = [(40, 69, 66, 71.5, LINE_LIGHT), (40, 75, 57, 77.5, LINE_LIGHTER)]


def render(size, round_mask):
    buf = [[(0, 0, 0, 0)] * size for _ in range(size)]
    scale = size / 108.0
    aa = 1.2 / scale  # 抗锯齿带宽（设计空间）

    for j in range(size):
        for i in range(size):
            px = (i + 0.5) / scale
            py = (j + 0.5) / scale

            # 圆角方形遮罩（round 版本用圆形）
            if round_mask:
                d = math.hypot(px - 54, py - 54) - 52
                cov_bg = 1.0 - smoothstep(0.0, aa, d)
            else:
                d = rrect_sdf(px, py, 54, 54, 54, 54, 20)
                cov_bg = 1.0 - smoothstep(0.0, aa, d)
            if cov_bg <= 0:
                continue

            # 渐变底色（对角）
            t = max(0.0, min(1.0, (px + py) / 216.0))
            bg = tuple(int(GRAD_A[k] * (1 - t) + GRAD_B[k] * t) for k in range(3)) + (255,)
            col = blend((0, 0, 0, 0), bg, cov_bg)

            # 纸张
            d = rrect_sdf(px, py, 54, 54.5, 21.5, 28.5, 5)
            cov = 1.0 - smoothstep(0.0, aa, d)
            col = blend(col, PAGE_WHITE, cov)

            # 折角三角
            if tri_inside(px, py, (63, 26), (75, 38), (63, 38)):
                col = blend(col, FOLD_BLUE, 1.0)

            # M 标记
            for (x1, y1), (x2, y2) in M_STROKES:
                dd = seg_dist(px, py, x1, y1, x2, y2) - STROKE_W / 2
                cov = 1.0 - smoothstep(0.0, aa, dd)
                col = blend(col, MARK_BLUE, cov)

            # 向下箭头
            for (x1, y1), (x2, y2) in ARROW_STROKES:
                dd = seg_dist(px, py, x1, y1, x2, y2) - STROKE_W / 2
                cov = 1.0 - smoothstep(0.0, aa, dd)
                col = blend(col, MARK_BLUE, cov)

            # 底部占位线
            for (x1, y1, x2, y2, c) in RECTS:
                dx = max(x1 - px, px - x2, 0.0)
                dy = max(y1 - py, py - y2, 0.0)
                dd = math.hypot(dx, dy)
                cov = 1.0 - smoothstep(0.0, aa, dd)
                col = blend(col, c, cov)

            buf[j][i] = col
    return buf


def write_png(path, buf, size):
    raw = bytearray()
    for j in range(size):
        raw.append(0)
        for i in range(size):
            raw.extend(buf[j][i])
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    for folder, size in DENSITIES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), render(size, False), size)
        write_png(os.path.join(d, "ic_launcher_round.png"), render(size, True), size)
        print(f"  {folder}: {size}x{size} x2")
    print("图标生成完成")


if __name__ == "__main__":
    main()