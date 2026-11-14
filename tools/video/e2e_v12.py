#!/usr/bin/env python3
"""새 화면(기능 3개 구조)을 처음부터 끝까지 눌러 보며 녹화하고, 자막을 얹어 시연 영상 하나로 만든다.

쓰는 법: python3 e2e_v12.py <결과 폴더> [record|compose|short|all]
전제: 에뮬레이터(사용자 10)에 앱이 «막 설치된» 상태(동의 전), 데모 모드 D3.
확인: 장면마다 화면 글자를 읽어 기대값과 비교하고 result.json 에 남긴다. 하나라도 다르면 끝 코드 1.
"""
import json, os, re, subprocess, sys, textwrap, time

OUT = os.path.abspath(sys.argv[1]); MODE = sys.argv[2] if len(sys.argv) > 2 else "all"
os.makedirs(OUT, exist_ok=True)
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "ai.moca.app"
FF, FP = "/opt/homebrew/bin/ffmpeg", "/opt/homebrew/bin/ffprobe"
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
REC = None; SEG = 0; T0 = 0.0; CAPS = {}; NOTES = []


def adb(*a, timeout=60):
    return subprocess.run([ADB, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout


def dump():
    for _ in range(5):
        adb("shell", "uiautomator", "dump", "/sdcard/u.xml"); x = adb("shell", "cat", "/sdcard/u.xml")
        if "<node" in x: return x
        time.sleep(0.7)
    return ""


def find(key, x=None):
    for m in re.finditer(r"<node ([^>]*?)/?>", x or dump()):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        rid = a.get("resource-id", "").split("/")[-1]; text = a.get("text", "")
        if key == rid or key == text:
            return rid, text, list(map(int, re.findall(r"\d+", a.get("bounds", ""))))
    return None


def wait(key, sec=20):
    end = time.time() + sec
    while time.time() < end:
        n = find(key)
        if n: return n
        time.sleep(0.5)
    return None


def note(step, ok, detail=""):
    NOTES.append({"scene": SEG, "step": step, "ok": ok, "detail": detail})
    print(f"   [{'확인' if ok else '다름'}] {step} — {detail}", flush=True)


def tap(key, sec=20):
    n = wait(key, sec)
    if not n:
        note(f"누르기 {key}", False, "찾지 못함"); return False
    b = n[2]; adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); return True


def expect(step, key, want, sec=25):
    end = time.time() + sec; got = None
    while time.time() < end:
        n = find(key); got = n[1] if n else None
        if got is not None and want in got:
            note(step, True, f"{key} = 「{got}」"); return True
        time.sleep(0.6)
    note(step, False, f"{key} = 「{got}」 (기대 「{want}」)"); return False


def cap(text):
    CAPS[SEG].append([round(time.time() - T0, 1), text]); print("   자막:", text, flush=True)


def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)


def scene(title):
    global REC, SEG, T0
    scene_end(); SEG += 1; CAPS[SEG] = []
    print(f"\n── 장면 {SEG}. {title}", flush=True)
    REC = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", "178", "--bit-rate", "8000000", f"/sdcard/v12-{SEG}.mp4"])
    time.sleep(1.5); T0 = time.time() - 0.7
    cap(title)


def scene_end():
    global REC
    if REC is None: return
    time.sleep(1.5)
    adb("shell", "pkill", "-INT", "screenrecord")
    try: REC.wait(timeout=20)
    except Exception: REC.kill()
    time.sleep(2); adb("pull", f"/sdcard/v12-{SEG}.mp4", os.path.join(OUT, f"seg{SEG:02d}.mp4"), timeout=180)
    REC = None


def front():
    adb("shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(1.5)


def record():
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", "--user", "10", PKG)

    scene("처음 켜면 필수 동의 세 가지를 받아요")
    front(); wait("buttonStart")
    time.sleep(2.5)
    for c in ("consent1", "consent2", "consent3"):
        tap(c); time.sleep(0.8)
    cap("세 가지에 모두 동의해야 시작할 수 있어요"); time.sleep(2); shot("01-동의")
    tap("buttonStart")

    scene("앱을 켜면 차가 스스로 점검해요")
    expect("실제 모드 앱 시작 화면", "startGrade", "등급을 낼 수 없어요")
    cap("에뮬레이터는 읽을 수 있는 항목이 적어요. 그래서 등급을 지어내지 않고 「낼 수 없다」고 말해요"); time.sleep(4); shot("02-앱시작-실제")
    tap("startGoMain"); wait("tabSettings")
    tap("tabSettings"); tap("settingsGroupDemo"); cap("화면을 다 보려면 설정에서 데모 모드를 켜요. 데모 값에는 항상 「데모 데이터」 배지가 붙어요")
    tap("switchDemo"); time.sleep(1); tap("scenarioD3"); time.sleep(2); shot("02-설정-데모")
    adb("shell", "am", "force-stop", "--user", "10", PKG); front()
    expect("앱 시작 화면 등급", "startGrade", "B · 75")
    x = dump()
    for key, want in (("startAction1", "ABS 경고등 −50"), ("startAction2", "타이어 공기압 경고등 −25")):
        n = find(key, x); note(f"조치 항목 {key}", bool(n and want in n[1]), n[1] if n else "없음")
    cap("데모 D3: 종합 B · 75. ABS 빨간 경고등과 타이어 공기압 경고등이 켜져 있어요"); time.sleep(4); shot("02-앱시작")
    cap("빨간 경고등이 있으면 자동으로 넘어가지 않고 정차 안내를 보여 줘요"); time.sleep(3)
    tap("startGoMain")
    expect("메인 등급", "mainGrade", "B · 75")
    cap("메인: 등급, 주의 배지, 기능 세 개 — 상태관리 · 보이스챗 · 대전하기"); time.sleep(4); shot("03-메인")

    scene("상태관리: 어디가 왜 감점인지 한 화면에")
    tap("tabStatus"); expect("상태관리 등급", "statusGrade", "B · 75")
    cap("차 그림의 색이 구역 상태예요. 빨강은 제동·조향, 노랑은 타이어"); time.sleep(6); shot("04-상태관리")
    cap("항목마다 감점, 해결 방법, 필요한 용품 QR 을 보여 줘요"); time.sleep(5)
    cap("[AI에게 물어보기] 를 누르면 그 항목을 들고 보이스챗으로 가요"); time.sleep(2.5)
    tap("solutionAsk")

    scene("보이스챗: 묻고, 길 안내까지")
    expect("넘어온 항목", "voiceContextText", "ABS 경고등")
    time.sleep(3); shot("05-보이스챗-항목")
    tap("suggestion2"); cap("「어떻게 해결해?」 — 차에서 읽은 값만 근거로 답해요")
    time.sleep(3)
    n = None; end = time.time() + 30
    while time.time() < end:                       # 답이 올 때까지
        n = find("answerText")
        if n and n[1] and n[1] != "무엇이든 물어보세요": break
        time.sleep(1)
    note("AI 답", bool(n and n[1] and n[1] != "무엇이든 물어보세요"), (n[1] if n else "")[:80])
    time.sleep(7); shot("06-보이스챗-답")
    tap("suggestion3"); cap("「가까운 정비소 안내해줘」 — 바로 출발하지 않고 먼저 물어봐요")
    expect("확인 카드", "navConfirmText", "안내할까요"); time.sleep(4); shot("07-확인카드")
    tap("navConfirmYes"); cap("[안내 시작] 을 눌러야 내비게이션에 경로를 요청해요")
    time.sleep(11); shot("08-내비")
    log = adb("logcat", "-d", "-s", "MOCA_NAVI:V")
    note("내비가 경로 시작을 알림", "onRouteStarted" in log, (re.findall(r"onRouteStarted.*", log) or ["없음"])[-1][:60])
    cap("내비게이션이 경로를 받았어요 (데모 모드는 앱에 넣어 둔 데모 장소)"); time.sleep(5)

    scene("대전하기: 연습 상대와 구역별로 겨뤄요")
    front(); tap("tabBattle")
    expect("상대", "battleTheirName", "라이벌드라이버"); time.sleep(3); shot("09-대전-로비")
    tap("battleStart"); cap("동력 · 제동·조향 · 타이어 · 전기 · 안전, 다섯 라운드")
    expect("결과", "battleResult", "2 : 2 · 종합 75 대 88", sec=40); shot("10-대전-결과")
    cap("2 : 2 동점이면 종합 점수로 가려요. 75 대 88 로 졌어요"); time.sleep(5)
    cap("「이 차만큼 관리하려면」 — 무엇을 고치면 따라잡는지 알려 줘요"); time.sleep(5)

    scene("월간랭킹: 이번 달 내 관리")
    tap("battleRanking")
    expect("내 순위", "rankingMine", "13명 중 9위"); expect("1위와 차이", "rankingMine", "22점 차이")
    cap("13명 중 9위. 1위 새벽드라이버 97점과 22점 차이 (데모 데이터)"); time.sleep(6); shot("11-월간랭킹")
    tap("rankingAsk"); expect("관리 요약 카드", "voiceContextText", "이번 달 관리 요약")
    cap("[질문하기] 로 이번 달 관리 요약을 들고 보이스챗에 물어볼 수 있어요"); time.sleep(3)
    tap("suggestion3"); time.sleep(11); shot("12-보이스챗-관리")

    scene("해결하면 등급이 올라요")
    tap("tabStatus"); expect("상태관리", "statusGrade", "B · 75"); time.sleep(2)
    tap("buttonResolveAbs"); cap("데모: ABS 경고등을 해결하면")
    expect("해결 뒤 등급", "statusGrade", "A · 83"); cap("종합 A · 83 으로 올라요. 관리 점수도 같이 올라요"); time.sleep(5); shot("13-해결뒤")
    tap("tabSettings"); cap("설정: 닉네임, 데모 시나리오, 내 데이터 삭제"); time.sleep(3)
    tap("settingsGroupDemo"); time.sleep(3); shot("14-설정-데모")
    tap("settingsGroupData"); time.sleep(3.5); shot("15-설정-데이터")
    scene_end()

    json.dump(CAPS, open(os.path.join(OUT, "captions.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(NOTES, open(os.path.join(OUT, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    bad = [n for n in NOTES if not n["ok"]]
    print(f"\n확인 {len(NOTES) - len(bad)} / {len(NOTES)}", "· 다름:" if bad else "", *[b["step"] for b in bad])
    return not bad


def run_ff(args):
    r = subprocess.run([FF, "-y", "-loglevel", "error", *args], capture_output=True, text=True)
    if r.returncode != 0: sys.exit("ffmpeg 실패: " + r.stderr[-600:])


def dur(path):
    return float(subprocess.run([FP, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip())


def compose():
    from PIL import Image, ImageDraw, ImageFont
    caps = json.load(open(os.path.join(OUT, "captions.json"), encoding="utf-8"))
    tmp = os.path.join(OUT, "_합성"); os.makedirs(tmp, exist_ok=True)
    W, X, Y = 600, 20, 690                       # 1920×1080 기준. 왼쪽(차량 패널) 아래에 자막 카드를 둔다
    total_raw = sum(dur(os.path.join(OUT, f"seg{int(n):02d}.mp4")) for n in caps)
    speed = float(os.environ.get("DEMO_SPEED", "0")) or max(1.1, total_raw / 175)   # 3분 안에 들어오게
    print(f"원본 {total_raw:.0f}초 → 속도 {speed:.2f}배")

    def card(title, text, path):
        big = ImageFont.truetype(FONT, 34, index=6); small = ImageFont.truetype(FONT, 22, index=4)
        lines = textwrap.wrap(text, 17) or [""]
        h = 30 + 30 + 14 + len(lines) * 46 + 24
        img = Image.new("RGBA", (W, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
        d.rounded_rectangle([0, 0, W - 1, h - 1], 24, fill=(20, 22, 28, 238))
        d.text((26, 24), title, font=small, fill=(150, 190, 255, 255))
        for i, line in enumerate(lines): d.text((26, 30 + 30 + 10 + i * 46), line, font=big, fill=(255, 255, 255, 255))
        img.save(path)

    parts = []
    for n in sorted(caps, key=int):
        src = os.path.join(OUT, f"seg{int(n):02d}.mp4"); total = dur(src); items = caps[n]
        title = f"MOCA 시연 {n}/{len(caps)}"
        inputs = ["-i", src]; chain = []; last = "0:v"
        for i, (t, text) in enumerate(items):
            end = items[i + 1][0] if i + 1 < len(items) else total
            png = os.path.join(tmp, f"c{n}-{i:02d}.png"); card(title, text, png)
            inputs += ["-i", png]
            chain.append(f"[{last}][{i + 1}:v]overlay={X}:{Y}:enable='between(t,{t:.1f},{max(end, t + 0.5):.1f})'[v{i}]"); last = f"v{i}"
        chain.insert(0, "[0:v]scale=1920:1080[base]"); chain[1] = chain[1].replace("[0:v]", "[base]", 1)
        chain.append(f"[{last}]setpts=PTS/{speed}[out]")
        dst = os.path.join(tmp, f"part{int(n):02d}.mp4")
        run_ff([*inputs, "-filter_complex", ";".join(chain), "-map", "[out]", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p", "-r", "30", dst])
        parts.append(dst)
    lst = os.path.join(tmp, "list.txt"); open(lst, "w").writelines(f"file '{p}'\n" for p in parts)
    final = os.path.join(OUT, "MOCA_시연영상.mp4")
    run_ff(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", final])
    print(final, f"{dur(final):.0f}초, {os.path.getsize(final) / 1e6:.1f}MB")


def short():
    """30초 세로 숏폼(1080×1920, 소리 없음). 구성은 docs/submission/숏폼_구성안.md."""
    from PIL import Image, ImageDraw, ImageFont
    tmp = os.path.join(OUT, "_숏폼"); os.makedirs(tmp, exist_ok=True)
    CW, CH, TOP = 1080, 1920, 640                 # 위 15%·아래 20% 는 비운다. 앱 창은 TOP 부터
    CROP = "crop=1682:1210:853:90,scale=1080:-2"  # 2560×1440 녹화에서 앱 창만

    def canvas(big, small, path, center=False):
        img = Image.new("RGB", (CW, CH), (19, 20, 23)); d = ImageDraw.Draw(img)
        fb = ImageFont.truetype(FONT, 76, index=6); fs = ImageFont.truetype(FONT, 40, index=4)
        y = 760 if center else 320
        for line in big.split("\n"):
            d.text(((CW - d.textlength(line, font=fb)) / 2, y), line, font=fb, fill=(255, 255, 255)); y += 100
        if small:
            sy = y + 40 if center else 1440
            for line in small.split("\n"):
                d.text(((CW - d.textlength(line, font=fs)) / 2, sy), line, font=fs, fill=(150, 190, 255)); sy += 56
        img.save(path)

    # (장면, 시작, 원본 길이, 나갈 길이, 큰 글자, 작은 글자)
    cuts = [
        (2, 31.0, 3.5, 3.0, "경고등이 켜졌다.\n얼마나 급한 걸까?", ""),
        (3, 6.0, 12.0, 6.0, "점수 낮은 구역부터,\n고칠 방법까지", "앱을 켜면 점검은 끝나 있다"),
        (4, 31.0, 25.0, 7.0, "말로 묻고, 확인하면\n내비가 안내한다", "데모 모드: 장소 목록의 좌표로 안내"),
        (5, 12.0, 18.0, 6.0, "진 구역은 무엇을\n고칠지 알려 준다", "연습 상대와의 대전"),
        (7, 9.5, 8.0, 4.0, "고치면 등급이 오른다\nB → A", "데모: ABS 경고등 해결"),
    ]
    parts = []
    for i, (n, start, raw, play, big, small) in enumerate(cuts):
        png = os.path.join(tmp, f"bg{i}.png"); canvas(big, small, png)
        dst = os.path.join(tmp, f"cut{i}.mp4")
        run_ff(["-ss", f"{start}", "-t", f"{raw}", "-i", os.path.join(OUT, f"seg{n:02d}.mp4"), "-loop", "1", "-i", png,
                "-filter_complex", f"[0:v]{CROP},setpts=PTS*{play / raw:.4f}[v];[1:v][v]overlay=0:{TOP}:shortest=1[out]",
                "-map", "[out]", "-t", f"{play}", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-r", "30", dst])
        parts.append(dst)
    png = os.path.join(tmp, "end.png")
    canvas("MOCA", "앱을 켜면 스스로 점검하고,\n고칠 곳까지 이어 주는 내 차 관리 파트너\n\n화면은 Pleos Connect 에뮬레이터의\n데모 데이터입니다", png, center=True)
    dst = os.path.join(tmp, "end.mp4")
    run_ff(["-loop", "1", "-i", png, "-t", "4", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-r", "30", dst])
    parts.append(dst)
    lst = os.path.join(tmp, "list.txt"); open(lst, "w").writelines(f"file '{p}'\n" for p in parts)
    final = os.path.join(OUT, "MOCA_숏폼_30s.mp4")
    run_ff(["-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", final])
    print(final, f"{dur(final):.0f}초, {os.path.getsize(final) / 1e6:.1f}MB")


if __name__ == "__main__":
    ok = True
    if MODE in ("record", "all"): ok = record()
    if MODE in ("compose", "all"): compose()
    if MODE in ("short", "all"): short()
    sys.exit(0 if ok else 1)
