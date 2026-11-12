#!/usr/bin/env python3
"""e2e.py 가 찍은 장면으로 (1) 3분 안쪽 시연 영상 (2) 30초 세로 숏폼을 만든다.
쓰는 법: uv run --with pillow python e2e_short.py <결과 폴더> <시연 영상.mp4> <숏폼.mp4>
시연 영상: 대표 이야기 장면만 골라 조금 빠르게 잇는다. 자막은 긴 영상과 같다.
숏폼: 1080×1920. 앱 창만 잘라 가운데에 두고 위에 큰 글자를 얹는다. 소리는 없다."""
import json, os, subprocess, sys, textwrap
from PIL import Image, ImageDraw, ImageFont
OUT, DEMO, SHORT = (os.path.abspath(p) for p in sys.argv[1:4])
FF = "/opt/homebrew/bin/ffmpeg"; FP = "/opt/homebrew/bin/ffprobe"
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
caps = json.load(open(os.path.join(OUT, "captions.json"), encoding="utf-8"))
tmp = os.path.join(OUT, "_짧은판"); os.makedirs(tmp, exist_ok=True)
def run(args):
    r = subprocess.run([FF, "-y", "-loglevel", "error", *args], capture_output=True, text=True)
    if r.returncode != 0: print("ffmpeg 실패:", r.stderr[-600:]); sys.exit(1)
def dur(path):
    return float(subprocess.run([FP, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip())
def seg(n): return os.path.join(OUT, f"seg{n:02d}.mp4")
def t_of(n, needle, last=False):
    hits = [t for t, text in caps[str(n)] if needle in text]
    if not hits: raise SystemExit(f"장면 {n} 에 「{needle}」 자막이 없다")
    return hits[-1] if last else hits[0]

# ── 1. 시연 영상 (3분 안쪽)
W, X, Y = 800, 26, 700
def card(title, text, path):
    big = ImageFont.truetype(FONT, 40, index=6); small = ImageFont.truetype(FONT, 26, index=4)
    lines = textwrap.wrap(text, 19) or [""]
    h = 36 + 44 + 16 + len(lines) * 56 + 28
    img = Image.new("RGBA", (W, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, h - 1], 28, fill=(20, 22, 28, 235))
    d.text((32, 30), title[:40], font=small, fill=(150, 190, 255, 255))
    for i, line in enumerate(lines): d.text((32, 36 + 44 + 12 + i * 56), line, font=big, fill=(255, 255, 255, 255))
    img.save(path)
SPEED = float(os.environ.get("DEMO_SPEED", "1.3"))
plan = [(1, None), (2, None), (4, t_of(4, "어떤 경고등인지") + 4.0), (3, None), (6, None), (8, None)]
parts = []
for order, (n, cut) in enumerate(plan):
    src = seg(n); end_all = cut or dur(src); items = [c for c in caps[str(n)] if c[0] < end_all]
    title = items[0][1]; inputs = ["-t", f"{end_all:.2f}", "-i", src]; chain = []; last = "0:v"
    for i, (t, text) in enumerate(items):
        end = items[i + 1][0] if i + 1 < len(items) else end_all
        png = os.path.join(tmp, f"d{order}-{i:02d}.png")
        card("MOCA 시연" if i == 0 else title, title if i == 0 else text, png)
        inputs += ["-i", png]
        chain.append(f"[{last}][{i + 1}:v]overlay={X}:{Y}:enable='between(t,{t:.1f},{max(end, t + 0.5):.1f})'[v{i}]"); last = f"v{i}"
    chain.append(f"[{last}]setpts=PTS/{SPEED},scale=1920:1080[out]")
    dst = os.path.join(tmp, f"demo{order}.mp4")
    run([*inputs, "-filter_complex", ";".join(chain), "-map", "[out]", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p", "-r", "30", dst])
    parts.append(dst)
lst = os.path.join(tmp, "demo.txt"); open(lst, "w").writelines(f"file '{p}'\n" for p in parts)
run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", DEMO])
print(DEMO, f"{dur(DEMO):.0f}초, {os.path.getsize(DEMO) / 1e6:.0f}MB")

# ── 2. 숏폼 (1080×1920, 30초)
CW, CH = 1080, 1920; TOP, MAXH = 680, 850          # 화면 조각을 놓는 자리(아래 384px 는 비운다)
def canvas(lines, sub, path, big_size=84):
    img = Image.new("RGB", (CW, CH), (19, 20, 23)); d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT, big_size, index=6); small = ImageFont.truetype(FONT, 42, index=4)
    y = 300
    for line in lines:
        w = d.textlength(line, font=big); d.text(((CW - w) / 2, y), line, font=big, fill=(255, 255, 255)); y += big_size + 20
    if sub:
        w = d.textlength(sub, font=small); d.text(((CW - w) / 2, y + 14), sub, font=small, fill=(170, 176, 188))
    img.save(path)
def fit(crop):
    """crop = (너비, 높이, x, y) — 2560×1440 캡처 좌표. 가로 1080, 세로 MAXH 안에 맞춘다."""
    w, h, x, y = crop; k = min(CW / w, MAXH / h)
    ow, oh = int(w * k) // 2 * 2, int(h * k) // 2 * 2
    return f"crop={w}:{h}:{x}:{y},scale={ow}:{oh}", (CW - ow) // 2, TOP + (MAXH - oh) // 2
ENC = ["-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30"]
def still(shot, crop, lines, sub, secs, name):
    bg = os.path.join(tmp, name + ".png"); canvas(lines, sub, bg)
    dst = os.path.join(tmp, name + ".mp4"); vf, ox, oy = fit(crop)
    run(["-loop", "1", "-t", str(secs), "-i", bg, "-loop", "1", "-t", str(secs), "-i", os.path.join(OUT, shot + ".png"),
         "-filter_complex", f"[1:v]{vf}[a];[0:v][a]overlay={ox}:{oy},fps=30[out]", "-map", "[out]", *ENC, "-t", str(secs), dst])
    return dst
def clip(n, start, end, secs, crop, lines, sub, name):
    bg = os.path.join(tmp, name + ".png"); canvas(lines, sub, bg)
    dst = os.path.join(tmp, name + ".mp4"); speed = (end - start) / secs; vf, ox, oy = fit(crop)
    run(["-loop", "1", "-t", str(secs), "-i", bg, "-ss", f"{start:.2f}", "-t", f"{end - start:.2f}", "-i", seg(n),
         "-filter_complex", f"[1:v]{vf},setpts=PTS/{speed:.3f}[a];[0:v][a]overlay={ox}:{oy}:shortest=1,fps=30[out]", "-map", "[out]", *ENC, "-t", str(secs), dst])
    return dst
LEFT = (782, 906, 901, 186); RIGHT = (782, 906, 1706, 186)   # 홈의 왼쪽 카드, 오른쪽 칸
NAVI = (860, 1210, 853, 90); ANSWER = (1420, 560, 901, 230)
t1 = t_of(2, "가까운 정비소 안내 (1번째)") - 1.0; t2 = t_of(2, "길안내가 시작된다") + 2.5
cuts = [
    still("03-홈-D3", LEFT, ["경고등이 켜졌다.", "얼마나 급한 걸까?"], None, 3, "s1"),
    still("03-홈-D3", RIGHT, ["가장 먼저 할 일", "하나만 고른다"], "누르지 않아도 점검은 끝나 있다", 5, "s2"),
    clip(2, t1, t2, 8, NAVI, ["단추 하나로", "가까운 정비소까지"], "차의 내비게이션이 찾고 길을 안내한다", "s3"),
    still("09-차에게-묻기-경고등", ANSWER, ["말로 물으면", "차의 값을 읽어 답한다"], "「이 경고등 뭐야?」", 5, "s4"),
    still("14-홈-D0-등급이-오른-순간", LEFT, ["고치면 등급이 오른다", "B → A"], "오늘의 순위 9위 → 5위", 5, "s5"),
]
end_png = os.path.join(tmp, "s6.png")
img = Image.new("RGB", (CW, CH), (19, 20, 23)); d = ImageDraw.Draw(img)
f1 = ImageFont.truetype(FONT, 150, index=6); f2 = ImageFont.truetype(FONT, 56, index=5); f3 = ImageFont.truetype(FONT, 38, index=3)
w = d.textlength("MOCA", font=f1); d.text(((CW - w) / 2, 640), "MOCA", font=f1, fill=(255, 255, 255))
for i, line in enumerate(["시동 걸면 스스로 점검하고,", "고칠 곳까지 이어 주는", "내 차 관리 파트너"]):
    w = d.textlength(line, font=f2); d.text(((CW - w) / 2, 860 + i * 84), line, font=f2, fill=(220, 224, 232))
note = "화면은 Pleos Connect 에뮬레이터의 데모 데이터입니다"
w = d.textlength(note, font=f3); d.text(((CW - w) / 2, 1440), note, font=f3, fill=(130, 136, 148)); img.save(end_png)
dst = os.path.join(tmp, "s6.mp4")
run(["-loop", "1", "-t", "4", "-i", end_png, "-vf", "fps=30", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p", "-r", "30", "-t", "4", dst]); cuts.append(dst)
lst = os.path.join(tmp, "short.txt"); open(lst, "w").writelines(f"file '{p}'\n" for p in cuts)
run(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", SHORT])
print(SHORT, f"{dur(SHORT):.0f}초, {os.path.getsize(SHORT) / 1e6:.1f}MB")
