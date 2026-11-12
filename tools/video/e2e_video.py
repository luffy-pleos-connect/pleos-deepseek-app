#!/usr/bin/env python3
"""e2e.py 가 찍은 장면들에 단계 자막을 얹고 하나로 잇는다.
쓰는 법: uv run --with pillow python e2e_video.py <결과 폴더> <만들 동영상.mp4>
자막은 화면 왼쪽(차량 위젯 자리)에 얹는다. 앱 창은 가리지 않는다."""
import json, os, subprocess, sys, textwrap
from PIL import Image, ImageDraw, ImageFont
OUT, FINAL = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
FF = "/opt/homebrew/bin/ffmpeg"; FP = "/opt/homebrew/bin/ffprobe"
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, X, Y = 800, 26, 700  # 2560×1440 기준. 왼쪽 패널 너비는 853
caps = json.load(open(os.path.join(OUT, "captions.json"), encoding="utf-8"))
tmp = os.path.join(OUT, "_자막"); os.makedirs(tmp, exist_ok=True)

def card(title, text, path):
    big = ImageFont.truetype(FONT, 40, index=6); small = ImageFont.truetype(FONT, 28, index=4)
    lines = textwrap.wrap(text, 19) or [""]
    h = 36 + 44 + 16 + len(lines) * 56 + 28
    img = Image.new("RGBA", (W, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, h - 1], 28, fill=(20, 22, 28, 235))
    d.text((32, 30), title[:40], font=small, fill=(150, 190, 255, 255))
    color = (255, 120, 110, 255) if text.startswith("✗") else (255, 255, 255, 255)
    for i, line in enumerate(lines): d.text((32, 36 + 44 + 12 + i * 56), line, font=big, fill=color)
    img.save(path)

parts = []
for seg in sorted(caps, key=int):
    src = os.path.join(OUT, f"seg{int(seg):02d}.mp4")
    if not os.path.exists(src): continue
    dur = float(subprocess.run([FP, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src], capture_output=True, text=True).stdout.strip())
    items = caps[seg]; title = items[0][1] if items else ""
    inputs = ["-i", src]; chain = []; last = "0:v"
    for i, (t, text) in enumerate(items):
        end = items[i + 1][0] if i + 1 < len(items) else dur
        if end <= t: end = t + 0.5
        png = os.path.join(tmp, f"{int(seg):02d}-{i:02d}.png")
        if i == 0: card("MOCA · 처음부터 끝까지", title, png)
        else: card(title, text, png)
        inputs += ["-i", png]
        chain.append(f"[{last}][{i + 1}:v]overlay={X}:{Y}:enable='between(t,{max(t, 0):.1f},{end:.1f})'[v{i}]"); last = f"v{i}"
    chain.append(f"[{last}]scale=1920:1080[out]")
    dst = os.path.join(tmp, f"part{int(seg):02d}.mp4")
    r = subprocess.run([FF, "-y", "-loglevel", "error", *inputs, "-filter_complex", ";".join(chain), "-map", "[out]", "-an",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p", "-r", "30", dst], capture_output=True, text=True)
    if r.returncode != 0: print("ffmpeg 실패", seg, r.stderr[-400:]); sys.exit(1)
    parts.append(dst); print(f"장면 {seg}: {dur:.0f}초, 자막 {len(items)}개", flush=True)
lst = os.path.join(tmp, "list.txt")
with open(lst, "w") as f: f.writelines(f"file '{p}'\n" for p in parts)
subprocess.run([FF, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", FINAL], check=True)
print(FINAL, f"{os.path.getsize(FINAL) / 1e6:.0f}MB")
