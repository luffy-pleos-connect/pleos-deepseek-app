#!/usr/bin/env python3
"""MOCA 를 처음부터 끝까지 실제로 눌러 보고 화면을 녹화한다. 단계마다 본 것을 확인하고 기록한다.
쓰는 법: e2e.py <결과 폴더>   (에뮬레이터가 -gpu host 로 떠 있어야 한다. APK 는 MOCA_APK 또는 빌드 결과물)
녹화는 한 번에 180초까지라 장면마다 끊어 찍는다. 장면별 자막 시각은 <결과 폴더>/captions.json 에 남긴다."""
import json, os, re, subprocess, sys, time
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "ai.moca.app"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APK = os.environ.get("MOCA_APK") or os.path.join(ROOT, "app/app/build/outputs/apk/debug/app-debug.apk")
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
LOG = []; CAPS = {}; SEG = 0; REC = None; SEG_T0 = 0.0; SEG_TITLE = ""

def adb(*a, timeout=60):
    return subprocess.run([ADB, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout).stdout
def dump():
    for _ in range(6):
        adb("shell", "rm", "-f", "/sdcard/u.xml"); adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
        x = adb("shell", "cat", "/sdcard/u.xml")
        if "<node" in x: return x
        time.sleep(1)
    return ""
def nodes(x):
    out = []
    for m in re.finditer(r"<node ([^>]*?)/?>", x):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        b = list(map(int, re.findall(r"\d+", a.get("bounds", ""))))
        out.append((a.get("resource-id", "").split("/")[-1], a.get("text", "") or a.get("content-desc", ""), b, a))
    return out
def find(key, x=None):
    for n in nodes(x if x is not None else dump()):
        if key == n[0] or key == n[1] or (key.startswith("~") and key[1:] in n[1]): return n
    return None
def wait(key, sec=15):
    end = time.time() + sec
    while time.time() < end:
        n = find(key)
        if n: return n
        time.sleep(0.5)
    return None
def caption(text):
    CAPS[SEG].append((round(time.time() - SEG_T0, 1), text))
def note(step, ok, seen):
    LOG.append((SEG, SEG_TITLE, step, ok, seen)); print(("통과 " if ok else "실패 ") + step + " — " + seen, flush=True)
    caption(("✓ " if ok else "✗ ") + step)
def tap_xy(b):
    adb("shell", "input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); time.sleep(1.0)
def tap(key, say=None, sec=15):
    n = wait(key, sec)
    if not n:
        note(say or f"누르기 {key}", False, f"{key} 를 찾지 못함"); return False
    if say: caption("누름: " + say)
    tap_xy(n[2]); return True
def expect_all(step, pairs, sec=20):
    """한 번 모은 화면 글자에서 여러 곳을 같이 본다. pairs = [(볼 곳, 들어 있어야 할 글자)]"""
    end = time.time() + sec; got = {}
    while time.time() < end:
        x = dump(); got = {}
        for key, want in pairs:
            n = find(key, x); got[key] = n[1] if n else None
        if all(got[k] is not None and w in got[k] for k, w in pairs):
            note(step, True, " / ".join(f"{k} = 「{got[k]}」" for k, _ in pairs)); return True
        time.sleep(0.6)
    note(step, False, " / ".join(f"{k} = 「{got.get(k)}」(기대 「{w}」)" for k, w in pairs)); return False
def expect(step, key, want, sec=20):
    return expect_all(step, [(key, want)], sec)
def gone(step, key):
    n = find(key); note(step, n is None, f"{key} " + ("없음" if n is None else "있음")); return n is None
def shot(name):
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
def scene(title):
    """새 장면 녹화를 시작한다."""
    global REC, SEG, SEG_T0, SEG_TITLE
    scene_end()
    SEG += 1; SEG_TITLE = title; CAPS[SEG] = []
    print(f"\n── 장면 {SEG}. {title}", flush=True)
    REC = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", "178", "--bit-rate", "8000000", f"/sdcard/e2e{SEG}.mp4"])
    time.sleep(1.5); SEG_T0 = time.time() - 0.7
    caption(f"장면 {SEG}. {title}")
def scene_end():
    global REC
    if REC is None: return
    time.sleep(1.5)
    early = REC.poll() is not None
    took = time.time() - SEG_T0
    adb("shell", "pkill", "-INT", "screenrecord")
    try: REC.wait(timeout=20)
    except Exception: REC.kill()
    time.sleep(2); adb("pull", f"/sdcard/e2e{SEG}.mp4", os.path.join(OUT, f"seg{SEG:02d}.mp4"), timeout=180)
    if early: note("녹화가 장면보다 먼저 끝났다", False, f"장면 길이 {took:.0f}초")
    else: print(f"   장면 길이 {took:.0f}초", flush=True)
    REC = None
def front():
    adb("shell", "am", "start", "--user", "10", "-n", f"{PKG}/.MainActivity"); time.sleep(1.5)
def scenario(sid, label):
    tap("tabSettings", "설정"); tap("settingsGroupDemo", "데모")
    if not find("scenarioD0"):
        tap("switchDemo", "데모 모드 켜기"); time.sleep(2.5); tap("settingsGroupDemo", sec=6)
    tap(f"scenario{sid}", f"{sid} · {label}"); time.sleep(2.5)
    tap("tabHome", "홈")
def navi_nodes():
    return [n for n in nodes(dump()) if n[3].get("package", "").startswith("ai.umos.maps")]
def set_clock(ms):
    adb("shell", "settings", "put", "global", "auto_time", "0"); adb("shell", "cmd", "alarm", "set-time", str(int(ms)))
def restore_clock():
    adb("shell", "cmd", "alarm", "set-time", str(int(time.time() * 1000))); adb("shell", "settings", "put", "global", "auto_time", "1")

# ── 준비: 새로 설치. 누르는 자리가 영상에 보이게 한다.
adb("shell", "cmd", "uimode", "night", "no")
adb("uninstall", PKG); print(adb("install", "--user", "10", APK, timeout=180).strip().splitlines()[-1])
adb("shell", "settings", "put", "system", "show_touches", "1"); adb("logcat", "-c")
try:
    scene("새로 설치한 앱을 처음 연다")
    front()
    expect("첫 실행 화면이 뜬다", "buttonStart", "동의하고 시작"); shot("01-첫-실행")
    tap("consent2", "음성 질문 동의"); tap("consent3", "대전 닉네임 공개 동의")
    tap("nicknameInput", "닉네임 칸"); time.sleep(1); adb("shell", "input", "text", "MOCA1"); caption("닉네임 MOCA1 을 넣는다"); time.sleep(1.5)
    adb("shell", "input", "keyevent", "66"); time.sleep(1.5)
    tap("buttonStart", "동의하고 시작")
    expect_all("홈이 뜨고, 누르지 않아도 점검이 끝나 있다(데모 꺼짐, 에뮬레이터가 주는 실제 값)", [("lastInspection", "월"), ("catElectricStatus", "정상"), ("homeStreak", "1일째")])
    expect("이 에뮬레이터는 읽을 수 있는 항목이 적어 등급을 내지 않는다고 알린다", "topProblemText", "등급을 낼 수 없어요")
    shot("02-홈-실제값"); time.sleep(2)

    scene("데모 D3: 문제를 보고 정비소 길안내까지(세 번 누름)")
    scenario("D3", "ABS 빨강 + 타이어 저압")
    expect_all("홈: 등급 B · 종합 75, 가장 먼저 할 일은 ABS 경고등, 오늘 9위", [("gradeValue", "B"), ("totalValue", "75"), ("topProblemText", "ABS 경고등"), ("rankText", "9위"), ("topProblemAction", "가까운 정비소 안내")])
    shot("03-홈-D3"); time.sleep(1.5)
    tap("topProblemAction", "가까운 정비소 안내 (1번째)")
    time.sleep(4); nav = navi_nodes(); texts = [n[1] for n in nav if n[1]]
    first = None
    for i, n in enumerate(nav):
        if n[1] == "1" and i + 1 < len(nav): first = nav[i + 1]; break
    note("내비게이션이 「정비소」 검색 결과를 연다", "정비소" in texts and first is not None, "검색어 「정비소」, 첫 결과 「" + (first[1] if first else "없음") + "」")
    shot("04-정비소-검색결과")
    if first:
        caption(f"누름: {first[1]} (2번째)"); tap_xy(first[2]); time.sleep(2)
        tap("목적지로", "목적지로 (3번째)"); time.sleep(4)
        nav = navi_nodes(); texts = [n[1] for n in nav if n[1]]
        note("길안내가 시작된다", "안내 종료" in texts, "화면 글자: " + ", ".join(t for t in texts if t != "안내 종료")[:80])
        shot("05-길안내"); time.sleep(3)
        tap("안내 종료", "안내 종료"); time.sleep(1)

    scene("상세: 왜 깎였는지, 무엇을 하면 되는지")
    front(); tap("tabHome", "홈")
    tap("catBrake", "제동·조향 줄")
    expect_all("제동·조향 상세: 읽은 값, 감점, 조치 한 줄, 해결 단추", [("detailTitle", "제동·조향"), ("detailDeductions", "ABS 경고등 −50"), ("detailAdvice", "정비소"), ("detailAction", "가까운 정비소 안내")])
    expect("최근 점검의 종합 점수 추이(화면 낭독용 글자로 읽음)", "trendGraph", "75점")
    shot("06-상세-제동조향"); time.sleep(1.5)
    tap("statusTire", "타이어 줄")
    expect("타이어 상세: 저압 바퀴가 어느 쪽인지", "detailValues", "앞왼쪽"); shot("07-상세-타이어"); time.sleep(2)

    scene("차에게 묻기")
    tap("tabVoice", "차에게 묻기")
    tap("suggestion1", "내 차 등급 몇이야?")
    expect_all("답에 등급과 점수가 있고, 답 아래에 해결 단추가 붙는다", [("answerText", "B"), ("voiceFixAction", "정비소")], 30)
    shot("08-차에게-묻기-등급"); time.sleep(5)
    tap("suggestion2", "이 경고등 뭐야?")
    expect_all("빨간 경고등이면 먼저 정차를 안내하고, 어떤 경고등인지 이름과 색으로 말한다", [("answerText", "정차"), ("answerText", "ABS"), ("answerText", "타이어 공기압")], 30); shot("09-차에게-묻기-경고등"); time.sleep(6)
    tap("suggestion3", "엔진오일 괜찮아?")
    expect_all("물어본 부품의 상태를 말한다", [("answerText", "엔진오일"), ("answerText", "정상")], 30); shot("09b-차에게-묻기-엔진오일"); time.sleep(5)
    tap("questionInput", "글로 묻기 칸"); time.sleep(1); adb("shell", "input", "text", "what%sis%sthe%sweather%stoday"); caption("차와 무관한 질문을 글로 넣는다"); time.sleep(2)
    shot("10-글로-묻기-키보드")
    adb("shell", "input", "keyevent", "66"); caption("누름: 키보드의 보내기")
    expect("차와 무관한 질문은 답하지 않고 물을 수 있는 것을 알려 준다", "answerText", "이해하지 못했어요", 30)
    shown = "mInputShown=true" in adb("shell", "dumpsys", "input_method")
    note("보낸 뒤 키보드가 닫힌다", not shown, "키보드 " + ("남아 있음" if shown else "닫힘"))
    if shown: adb("shell", "input", "keyevent", "4"); time.sleep(1)
    shot("11-무관한-질문"); time.sleep(4)

    scene("오늘의 순위와 대전")
    tap("tabBattle", "순위·대전")
    expect_all("순위: 13명 중 9위, 연습 순위라고 밝힌다", [("rankSummary", "9위"), ("rankPracticeBadge", "연습 순위")])
    shot("12-순위"); time.sleep(1.5)
    tap("buttonStartBattle", "대전 시작")
    expect_all("문제가 있는 차는 진다: 라운드 2 : 2, 종합 75 대 88 로 패배", [("battleResult", "패배"), ("battleResult", "2 : 2"), ("battleRecord", "0승 0무 1패")], 40)
    shot("13-대전-결과"); time.sleep(3)

    scene("데모 D0: 고치고 나면 등급이 오른다")
    scenario("D0", "정상")
    expect_all("등급이 오른 순간을 알린다. 문제가 없으면 「오늘도 이상 없어요」", [("gradeUpBanner", "B → A"), ("gradeValue", "A"), ("topProblemText", "이상 없어요"), ("rankText", "5위")])
    gone("문제가 없으면 해결 단추가 없다", "topProblemAction")
    shot("14-홈-D0-등급이-오른-순간"); time.sleep(3)
    tap("tabBattle", "순위·대전"); tap("buttonStartBattle", "대전 시작")
    expect_all("고친 차는 이긴다: 1 : 0 승리", [("battleResult", "승리"), ("battleRecord", "1승 0무 1패")], 40)
    shot("14b-대전-D0-승리"); time.sleep(3)

    scene("데모 D4: 배터리가 모자라면 충전소로")
    scenario("D4", "배터리 9%")
    expect_all("가장 먼저 할 일이 잔량 부족이고, 단추가 충전소 안내로 바뀐다", [("topProblemText", "잔량"), ("topProblemAction", "가까운 충전소 안내")])
    shot("15-홈-D4"); time.sleep(1.5)
    tap("topProblemAction", "가까운 충전소 안내")
    time.sleep(4); texts = [n[1] for n in navi_nodes() if n[1]]
    note("내비게이션이 「전기차 충전소」 검색 결과를 연다", "전기차 충전소" in texts and len(texts) > 3, "검색어 「전기차 충전소」, 화면 글자 " + str(len(texts)) + "개")
    shot("16-충전소-검색결과"); time.sleep(3)

    scene("데모 D7: 부품은 휴대폰으로 넘긴다(QR)")
    front(); scenario("D7", "워셔액 부족")
    expect_all("가장 먼저 할 일이 워셔액 부족이고, 단추가 「휴대폰으로 부품 보기」다", [("topProblemText", "워셔액 부족"), ("topProblemAction", "휴대폰으로 부품 보기")])
    tap("topProblemAction", "휴대폰으로 부품 보기")
    expect_all("QR 창이 뜨고 어디로 가는 주소인지 보여 준다", [("qrTitle", "휴대폰으로 부품 보기"), ("qrDomain", "www.mobis-as.com")])
    shot("17-QR"); time.sleep(4)
    tap("qrClose", "닫기")

    scene("날이 바뀌어도 점검 기록이 남는다")
    scenario("D0", "정상")
    expect("오늘은 1일째", "homeStreak", "1일째"); shot("18-연속-1일째"); time.sleep(1.5)
    adb("shell", "am", "force-stop", "--user", "10", PKG)
    try:
        set_clock(time.time() * 1000 + 86_400_000); caption("앱을 끄고 에뮬레이터 날짜를 하루 뒤로 바꾼다(명령)"); time.sleep(3)
        front(); caption("앱을 다시 연다")
        expect("다음 날 앱을 열면 2일째. 어제 기록이 남아 있다", "homeStreak", "2일째"); shot("19-연속-2일째"); time.sleep(3)
    finally:
        adb("shell", "am", "force-stop", "--user", "10", PKG); restore_clock()
    caption("날짜를 오늘로 되돌린다(명령)"); time.sleep(2)

    scene("야간 화면")
    adb("shell", "cmd", "uimode", "night", "yes"); caption("에뮬레이터를 야간으로 바꾼다(명령)"); time.sleep(2)
    front(); scenario("D3", "ABS 빨강 + 타이어 저압")
    expect_all("야간에도 같은 값이 보인다", [("gradeValue", "B"), ("topProblemText", "ABS 경고등")]); shot("20-야간-홈"); time.sleep(2)
    tap("tabStatus", "상세"); time.sleep(2); shot("21-야간-상세"); time.sleep(2)
    adb("shell", "cmd", "uimode", "night", "no"); caption("주간으로 되돌린다(명령)"); time.sleep(3)

    scene("내 데이터 삭제")
    front(); tap("tabSettings", "설정"); tap("settingsGroupData", "데이터")
    tap("buttonDeleteData", "내 데이터 삭제")
    expect("바로 지우지 않고 먼저 묻는다", "~지울까요", "지울까요"); shot("22-삭제-확인"); time.sleep(1.5)
    tap("취소", "취소"); expect("취소하면 그대로다", "buttonDeleteData", "내 데이터 삭제")
    tap("buttonDeleteData", "내 데이터 삭제"); tap("지우기", "지우기")
    expect("지우면 첫 실행 화면으로 돌아간다", "buttonStart", "동의하고 시작"); shot("23-삭제-뒤-첫-실행"); time.sleep(3)
    scene_end()
finally:
    scene_end()
    adb("shell", "cmd", "uimode", "night", "no"); restore_clock()
    adb("shell", "settings", "put", "system", "show_touches", "0")

fatal = adb("logcat", "-d").count("FATAL EXCEPTION")
with open(os.path.join(OUT, "captions.json"), "w", encoding="utf-8") as f: json.dump(CAPS, f, ensure_ascii=False, indent=1)
with open(os.path.join(OUT, "단계-기록.md"), "w", encoding="utf-8") as f:
    f.write("# MOCA 처음부터 끝까지 눌러 본 기록\n\n")
    f.write(f"- 찍은 날: {time.strftime('%Y-%m-%d %H:%M')}\n- 에뮬레이터: Pleos Connect v2.1.0 (`-gpu host`)\n- 앱: 새로 설치한 debug APK\n- 실행 중 앱이 죽은 횟수(logcat 의 FATAL EXCEPTION): {fatal}\n\n")
    f.write("| 장면 | 확인한 것 | 판정 | 화면에서 읽은 값 |\n|---|---|---|---|\n")
    for seg, title, step, ok, seen in LOG:
        f.write(f"| {seg}. {title} | {step} | {'통과' if ok else '실패'} | {seen.replace('|', '/')} |\n")
bad = [s for _, _, s, ok, _ in LOG if not ok]
print(f"\n확인 {len(LOG)}개 중 실패 {len(bad)}개, FATAL EXCEPTION {fatal}줄"); [print("  실패:", s) for s in bad]
