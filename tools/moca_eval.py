#!/usr/bin/env python3
"""MOCA 기능별 평가기준표를 에뮬레이터에서 재고 기록지를 만든다.

판정은 화면 XML 만 받는 순수 함수다. adb 는 --app 으로 기록지를 쓸 때만 부른다.
"""

import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

CATEGORY_NAMES = ["동력", "제동·조향", "타이어", "전기", "안전"]
STATUS_WORDS = ("정상", "주의", "위험", "측정 불가")
ACTION_WORDS = ("정비", "점검", "정차", "보충", "교체")
TIRE_WORDS = ("공기압", "TPMS", "저압")
WEATHER_WORDS = ("맑", "흐림", "비가", "눈이", "기온", "℃", "도입니다")
CAR_WORDS = ("등급", "경고등", "부품", "점검", "대전")
DATE_RE = re.compile(r"[0-9]+월 [0-9]+일")
CARE = 53

SCENARIO_ORDER = ["D0", "D1", "D2", "D3", "D4", "D5", "D6"]
BATTLE_ORDER = ["D0", "D1", "D2", "D4", "D6", "D3", "D5"]

# 기준표 3-2. 타이어 「없음」은 None. 「다음」은 표의 칸을 그대로 둔다.
SCENARIOS = {
    "D0": {"차": "이상 없음", "동력": 100, "제동·조향": 100, "타이어": 100, "전기": 100, "안전": 100, "건강": 100, "종합": 85, "등급": "A", "다음": "S까지 5점"},
    "D1": {"차": "엔진오일 부족", "동력": 60, "제동·조향": 100, "타이어": 100, "전기": 100, "안전": 100, "건강": 92, "종합": 80, "등급": "A", "다음": "S까지 10점"},
    "D2": {"차": "타이어 공기압 노란 경고등, 저압 바퀴 2개", "동력": 100, "제동·조향": 100, "타이어": 60, "전기": 100, "안전": 100, "건강": 92, "종합": 80, "등급": "A", "다음": "S까지 10점"},
    "D3": {"차": "ABS 빨간 경고등, 타이어 공기압 노란 경고등, 저압 바퀴 1개", "동력": 100, "제동·조향": 50, "타이어": 75, "전기": 100, "안전": 100, "건강": 85, "종합": 75, "등급": "B", "다음": "A까지 5점"},
    "D4": {"차": "배터리 9%", "동력": 100, "제동·조향": 100, "타이어": 100, "전기": 90, "안전": 100, "건강": 98, "종합": 84, "등급": "A", "다음": "S까지 6점"},
    "D5": {"차": "엔진오일 부족, 타이어 공기압 노란 경고등, 저압 바퀴 1개", "동력": 60, "제동·조향": 100, "타이어": 75, "전기": 100, "안전": 100, "건강": 87, "종합": 76, "등급": "B", "다음": "A까지 4점"},
    "D6": {"차": "타이어 값을 지원하지 않음", "동력": 100, "제동·조향": 100, "타이어": None, "전기": 100, "안전": 100, "건강": 100, "종합": 85, "등급": "A", "다음": "S까지 5점"},
}

# 기준표 3-3. 「결과」는 화면에서 찾을 낱말, 「결과칸」은 표의 칸 전체.
BATTLES = {
    "D0": {"상대": "달리는곰", "등급": "A", "상대점수": 84, "라운드": ["무", "무", "승", "무", "무"], "점수": (1, 0), "결과": "승리", "결과칸": "승리", "데모": False},
    "D1": {"상대": "세차는사랑", "등급": "A", "상대점수": 83, "라운드": ["패", "무", "무", "승", "무"], "점수": (1, 1), "결과": "패배", "결과칸": "패배 (종합 80 대 83)", "데모": False},
    "D2": {"상대": "출근길요정", "등급": "S", "상대점수": 91, "라운드": ["무", "무", "패", "무", "무"], "점수": (0, 1), "결과": "패배", "결과칸": "패배", "데모": False},
    "D4": {"상대": "주말캠퍼", "등급": "S", "상대점수": 92, "라운드": ["무", "무", "무", "패", "무"], "점수": (0, 1), "결과": "패배", "결과칸": "패배", "데모": False},
    "D6": {"상대": "퇴근길질주", "등급": "B", "상대점수": 76, "라운드": ["승", "무", "무", "무", "무"], "점수": (1, 0), "결과": "승리", "결과칸": "승리", "데모": False},
    "D3": {"상대": "라이벌드라이버", "등급": "A", "상대점수": 88, "라운드": ["무", "무", "패", "승", "승"], "점수": (2, 1), "결과": "승리", "결과칸": "승리", "데모": True},
    "D5": {"상대": "경고등수집가", "등급": "C", "상대점수": 64, "라운드": ["패", "승", "무", "무", "승"], "점수": (2, 1), "결과": "승리", "결과칸": "승리", "데모": True},
}

ITEMS = [
    ("홈-1", "자동"), ("홈-2", "자동"), ("홈-3", "자동"),
    ("점검-1", "수동"), ("점검-2", "자동"), ("점검-3", "자동"), ("점검-4", "수동"), ("점검-5", "자동"),
    ("상태-1", "자동"), ("상태-2", "자동"), ("상태-3", "자동"), ("상태-4", "자동"), ("상태-5", "자동"),
    ("보이스-1", "수동"), ("보이스-2", "수동"), ("보이스-3", "자동"), ("보이스-4", "자동"),
    ("보이스-5", "자동"), ("보이스-6", "수동"),
    ("대전-1", "자동"), ("대전-2", "자동"), ("대전-3", "자동"), ("대전-4", "자동"),
    ("공통-1", "자동"), ("공통-2", "수동"), ("공통-3", "자동"),
]

MANUAL = {
    "점검-1": "수동. 까닭: 데모를 끄고 새로 설치한 뒤 카드 상세 다섯의 원본 값을 확인하는 항목이다.",
    "점검-4": "수동. 까닭: 화면 녹화(기준표 명령 4)로 3초를 재야 한다.",
    "보이스-1": "수동. 까닭: 마이크가 있는 기기에서 소리 내어 말해야 한다.",
    "보이스-2": "수동. 까닭: 서버를 연결한 상태에서 같은 질문을 세 번 보내야 한다.",
    "보이스-6": "수동. 까닭: 화면 녹화(기준표 명령 4)로 6초를 재야 한다.",
    "공통-2": "수동. 까닭: 주행 제한 코드 검색과 주행 상태는 에뮬레이터에서 자동으로 재지 않는다.",
}

PARTIAL = "서버 연결 쪽은 수동. 까닭: 자동으로 재는 것은 서버 끊김 쪽뿐이다."
COMMON_SCREENS = ["홈", "점검하기", "상태관리", "AI 보이스", "대전하기", "설정"]
HOME_BUTTONS = ["점검하기", "상태관리", "AI 보이스", "대전하기"]


class Verdict:
    def __init__(self, ok, measured=""):
        self.ok = bool(ok)
        self.measured = measured


class DeviceError(Exception):
    pass


def pure(fn):
    def wrap(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except Exception as exc:
            return Verdict(False, f"{type(exc).__name__}: {exc}")

    return wrap


def list_text():
    return "".join(f"{name} {mode}\n" for name, mode in ITEMS)


def _tire_cell(value):
    return "없음" if value is None else str(value)


def answer_text():
    lines = [
        "3-2 데모 시나리오 정답",
        "관리 점수는 모든 시나리오에서 53 이다.",
        "시나리오\t차 상태\t동력\t제동·조향\t타이어\t전기\t안전\t건강\t종합\t등급\t다음 등급까지",
    ]
    for sid in SCENARIO_ORDER:
        row = SCENARIOS[sid]
        lines.append("\t".join([
            sid, row["차"], str(row["동력"]), str(row["제동·조향"]), _tire_cell(row["타이어"]),
            str(row["전기"]), str(row["안전"]), str(row["건강"]), str(row["종합"]),
            row["등급"], row["다음"],
        ]))
    lines.append("켜진 경고등 수: D2 1개, D3 2개, D5 1개, 나머지 0개. 빨간 경고등은 D3 에만 있다.")
    lines.append("")
    lines.append("3-3 대전 정답")
    lines.append("시나리오\t상대\t상대 등급\t상대 점수\t라운드 1~5 (나 기준)\t라운드 점수\t결과")
    for sid in BATTLE_ORDER:
        row = BATTLES[sid]
        lines.append("\t".join([
            sid, row["상대"], row["등급"], str(row["상대점수"]),
            " ".join(row["라운드"]),
            f"{row['점수'][0]} : {row['점수'][1]}",
            row["결과칸"],
        ]))
    lines.append("D3 과 D5 는 계획이 진행을 정해 둔 시연용 시나리오다. 위 규칙으로 계산한 결과와 다르므로, 화면에 「데모 시나리오」라는 글자가 함께 나와야 한다.")
    lines.append("상대의 카테고리 점수(동력, 제동·조향, 타이어, 전기, 안전): 달리는곰 100·100·75·100·100, 세차는사랑 100·100·100·90·100, 출근길요정 100·100·80·100·100, 주말캠퍼 100·100·100·100·100, 퇴근길질주 60·100·75·100·100.")
    lines.append("")
    return "\n".join(lines)


def contains_number(text, number):
    """바로 앞뒤가 숫자가 아닐 때만 그 숫자로 본다. 175·750 은 75 가 아니다."""
    token = str(number)
    start = 0
    while True:
        index = text.find(token, start)
        if index < 0:
            return False
        before = text[index - 1] if index > 0 else ""
        after_at = index + len(token)
        after = text[after_at] if after_at < len(text) else ""
        if not before.isdigit() and not after.isdigit():
            return True
        start = index + 1


def _find_standalone(text, token, start):
    index = start
    while True:
        found = text.find(token, index)
        if found < 0:
            return -1
        before = text[found - 1] if found > 0 else ""
        after_at = found + len(token)
        after = text[after_at] if after_at < len(text) else ""
        if not before.isdigit() and not after.isdigit():
            return after_at
        index = found + 1


def has_numbers_in_order(text, first, second):
    end = _find_standalone(text, str(first), 0)
    if end < 0:
        return False
    return _find_standalone(text, str(second), end) >= 0


def grade_of(text):
    """영문 대문자와 + 만 남긴다. B · 75 → B, S+ → S+."""
    return "".join(ch for ch in text if ch == "+" or ("A" <= ch <= "Z"))


def first_number(text):
    index = 0
    while index < len(text):
        if text[index].isdigit():
            end = index + 1
            while end < len(text) and text[end].isdigit():
                end += 1
            return int(text[index:end])
        index += 1
    return None


def next_parts(phrase):
    return grade_of(phrase), first_number(phrase)


def first_sentence(text):
    for index, ch in enumerate(text):
        if ch in ".?!":
            return text[: index + 1]
    return text


def category_slot(text, name):
    start = 0
    while True:
        index = text.find(name, start)
        if index < 0:
            return None
        rest = text[index + len(name):]
        cut = len(rest)
        for other in CATEGORY_NAMES:
            if other == name:
                continue
            found = rest.find(other)
            if found >= 0:
                cut = min(cut, found)
        return rest[:cut]


def only_status(text, word):
    if word not in text:
        return False
    return all(other not in text for other in STATUS_WORDS if other != word)


def parse_root(xml):
    if not xml or not str(xml).strip():
        raise ValueError("화면 글자가 비어 있다")
    start = xml.find("<")
    if start < 0:
        raise ValueError("xml 없음")
    return ET.fromstring(xml[start:])


def elements(xml, package):
    for node in parse_root(xml).iter("node"):
        if node.attrib.get("package") == package:
            yield node


def find_nodes(xml, resource_id, package):
    found = []
    for node in elements(xml, package):
        if node.attrib.get("resource-id") == resource_id:
            found.append({
                "text": node.attrib.get("text", ""),
                "bounds": node.attrib.get("bounds", ""),
                "enabled": node.attrib.get("enabled", ""),
                "checked": node.attrib.get("checked", ""),
                "resource_id": resource_id,
            })
    return found


def bounds_center(bounds):
    matched = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds.strip())
    if not matched:
        raise ValueError(f"bounds 형식이 아니다: {bounds}")
    x1, y1, x2, y2 = (int(group) for group in matched.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def first_element(xml, resource_id, package):
    for node in elements(xml, package):
        if node.attrib.get("resource-id") == resource_id:
            return node
    return None


def all_text(xml, package):
    parts = []
    for node in elements(xml, package):
        text = node.attrib.get("text", "")
        if text:
            parts.append(text)
    return "\n".join(parts)


def container_text(xml, resource_id, package):
    container = first_element(xml, resource_id, package)
    if container is None:
        return ""
    parts = []
    for node in container.iter("node"):
        if node.attrib.get("package") != package:
            continue
        text = node.attrib.get("text", "")
        if text:
            parts.append(text)
    return "\n".join(parts)


def place_text(xml, app, name):
    spec = app["places"][name]
    package = app["package"]
    if "id" in spec:
        return " ".join(node["text"] for node in find_nodes(xml, spec["id"], package))
    if "ids" in spec:
        parts = []
        for resource_id in spec["ids"]:
            parts.extend(node["text"] for node in find_nodes(xml, resource_id, package))
        return " ".join(parts)
    if "container" in spec:
        return container_text(xml, spec["container"], package)
    return ""


def _packaged_children(parent, package):
    return [
        child for child in list(parent)
        if child.tag == "node" and child.attrib.get("package") == package
    ]


def category_states(xml, app):
    container = first_element(xml, app["places"]["상태.카드"]["container"], app["package"])
    if container is None:
        return {}
    found = {}
    package = app["package"]
    for parent in container.iter("node"):
        kids = _packaged_children(parent, package)
        for index, kid in enumerate(kids):
            name = kid.attrib.get("text", "")
            if name in CATEGORY_NAMES and name not in found and index + 1 < len(kids):
                found[name] = kids[index + 1].attrib.get("text", "")
    return found


def category_detail(xml, app, name):
    container = first_element(xml, app["places"]["상태.상세"]["container"], app["package"])
    if container is None:
        return ""
    package = app["package"]
    for parent in container.iter("node"):
        kids = _packaged_children(parent, package)
        for index, kid in enumerate(kids):
            if kid.attrib.get("text", "") == name and index + 2 < len(kids):
                return kids[index + 2].attrib.get("text", "")
    return ""


def round_verdicts(xml, app):
    container = first_element(xml, app["places"]["대전.라운드"]["container"], app["package"])
    if container is None:
        return []
    package = app["package"]
    verdicts = []
    for kid in list(container):
        if kid.tag != "node":
            continue
        words = []
        for node in kid.iter("node"):
            if node.attrib.get("package") != package:
                continue
            text = node.attrib.get("text", "")
            if text in ("승", "패", "무") and text not in words:
                words.append(text)
        verdicts.append(words[0] if len(words) == 1 else None)
    return verdicts


def count_exact(xml, package, text):
    return sum(1 for node in elements(xml, package) if node.attrib.get("text") == text)


def _done(ok, measured, note=""):
    return ("통과" if ok else "실패", measured, note)


@pure
def judge_home_1(xml_d3, xml_d0, app):
    grade_d3 = grade_of(place_text(xml_d3, app, "홈.등급"))
    grade_d0 = grade_of(place_text(xml_d0, app, "홈.등급"))
    ok = grade_d3 == SCENARIOS["D3"]["등급"] and grade_d0 == SCENARIOS["D0"]["등급"]
    return Verdict(ok, f"D3 {grade_d3} / D0 {grade_d0}")


@pure
def judge_home_2(screens, app):
    ok = True
    bits = []
    for sid in ("D3", "D1"):
        xml = screens.get(sid, "")
        total = place_text(xml, app, "홈.종합")
        health = place_text(xml, app, "홈.건강")
        care = place_text(xml, app, "홈.관리")
        hint = place_text(xml, app, "홈.남은점수")
        expected = SCENARIOS[sid]
        next_grade, next_points = next_parts(expected["다음"])
        row_ok = (
            contains_number(total, expected["종합"])
            and contains_number(health, expected["건강"])
            and contains_number(care, CARE)
            and grade_of(hint) == next_grade
            and next_points is not None
            and contains_number(hint, next_points)
        )
        ok = ok and row_ok
        bits.append(f"{sid} 종합 {total} 건강 {health} 관리 {care} 남은 {hint}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_home_3(steps, app):
    package = app["package"]
    checks = [
        ("점검하기", lambda xml: bool(
            find_nodes(xml, app["places"]["점검.결과"]["id"], package)
            or find_nodes(xml, app["places"]["점검.시작"]["id"], package)
        )),
        ("상태관리", lambda xml: all(count_exact(xml, package, name) >= 1 for name in CATEGORY_NAMES)),
        ("AI 보이스", lambda xml: bool(find_nodes(xml, app["places"]["보이스.상태"]["id"], package))),
        ("대전하기", lambda xml: bool(find_nodes(xml, app["places"]["대전.시작"]["id"], package))),
    ]
    if len(steps) != 4:
        return Verdict(False, "화면 수가 4가 아니다")
    ok = True
    bits = []
    for (name, pred), step in zip(checks, steps):
        good = pred(step["xml"])
        back = bool(find_nodes(step["home"], app["places"]["홈.등급"]["id"], package))
        ok = ok and good and back
        bits.append(f"{name} {'있음' if good else '없음'} 복귀 {'있음' if back else '없음'}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_inspect_2(inspect_xml, status_xml, app):
    slot = category_slot(place_text(inspect_xml, app, "점검.카테고리"), "타이어")
    no_number = slot is not None and first_number(slot) is None
    tire = category_states(status_xml, app).get("타이어", "")
    state_ok = "측정 불가" in tire and "정상" not in tire
    result = place_text(inspect_xml, app, "점검.결과")
    expected = SCENARIOS["D6"]
    result_ok = grade_of(result) == expected["등급"] and contains_number(result, expected["종합"])
    return Verdict(
        no_number and state_ok and result_ok,
        f"타이어칸 {slot} / 카드 {tire} / 결과 {result}",
    )


@pure
def judge_inspect_3(screens, app):
    ok = True
    bits = []
    for sid in SCENARIO_ORDER:
        text = place_text(screens.get(sid, ""), app, "점검.결과")
        expected = SCENARIOS[sid]
        row_ok = grade_of(text) == expected["등급"] and contains_number(text, expected["종합"])
        ok = ok and row_ok
        bits.append(f"{sid} {text}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_inspect_5(before_xml, after_xml, app):
    resource_id = app["places"]["홈.마지막점검"]["id"]
    package = app["package"]
    if not find_nodes(before_xml, resource_id, package) or not find_nodes(after_xml, resource_id, package):
        return Verdict(False, "마지막 점검 노드가 없다")
    before = place_text(before_xml, app, "홈.마지막점검")
    after = place_text(after_xml, app, "홈.마지막점검")
    return Verdict(before != after, f"전 {before} / 후 {after}")


@pure
def judge_status_1(xml, app):
    package = app["package"]
    counts_ok = all(count_exact(xml, package, name) == 1 for name in CATEGORY_NAMES)
    states = category_states(xml, app)
    numbers_ok = all(first_number(states.get(name, "")) is not None for name in CATEGORY_NAMES)
    return Verdict(counts_ok and numbers_ok, str(states))


@pure
def judge_status_2(screens, app):
    ok = True
    bits = []
    for sid in SCENARIO_ORDER:
        states = category_states(screens.get(sid, ""), app)
        expected = SCENARIOS[sid]
        for name in CATEGORY_NAMES:
            got = first_number(states.get(name, ""))
            if got != expected[name]:
                ok = False
            bits.append(f"{sid} {name} {got}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_status_3(screens, app):
    expect = {
        "D3": {"동력": "정상", "제동·조향": "위험", "타이어": "주의"},
        "D1": {"동력": "위험"},
        "D4": {"전기": "주의"},
        "D6": {"타이어": "측정 불가"},
    }
    ok = True
    bits = []
    for sid, words in expect.items():
        states = category_states(screens.get(sid, ""), app)
        for name, word in words.items():
            text = states.get(name, "")
            if not only_status(text, word):
                ok = False
            bits.append(f"{sid} {name} {text}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_status_4(brake_xml, tire_xml, app):
    brake = category_detail(brake_xml, app, "제동·조향")
    tire = category_detail(tire_xml, app, "타이어")
    brake_ok = "ABS" in brake and contains_number(brake, 50) and any(word in brake for word in ACTION_WORDS)
    tire_ok = (
        any(word in tire for word in TIRE_WORDS)
        and contains_number(tire, 25)
        and any(word in tire for word in ACTION_WORDS)
    )
    return Verdict(brake_ok and tire_ok, f"제동 {brake} / 타이어 {tire}")


@pure
def judge_status_5(xml, app):
    text = place_text(xml, app, "상태.추이")
    dates = DATE_RE.findall(text)
    ok = len(dates) >= 2 and contains_number(text, SCENARIOS["D3"]["종합"])
    return Verdict(ok, text)


@pure
def judge_voice_3(xml, app):
    sentence = first_sentence(place_text(xml, app, "보이스.답"))
    return Verdict("정차" in sentence, sentence)


@pure
def judge_voice_4(xml, app):
    text = place_text(xml, app, "보이스.답")
    expected = SCENARIOS["D3"]
    return Verdict(expected["등급"] in text and contains_number(text, expected["종합"]), text)


@pure
def judge_voice_5(xml, app):
    text = place_text(xml, app, "보이스.답")
    ok = bool(text.strip()) and all(word not in text for word in WEATHER_WORDS) and any(word in text for word in CAR_WORDS)
    return Verdict(ok, text)


@pure
def judge_battle_1(screens, app):
    ok = True
    bits = []
    for sid in ("D3", "D0"):
        text = place_text(screens.get(sid, ""), app, "대전.상대")
        row = BATTLES[sid]
        row_ok = row["상대"] in text and grade_of(text) == row["등급"] and contains_number(text, row["상대점수"])
        ok = ok and row_ok
        bits.append(f"{sid} {text}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_battle_2(screens, app):
    ok = True
    bits = []
    for sid in BATTLE_ORDER:
        xml = screens.get(sid, "")
        got = round_verdicts(xml, app)
        expected = BATTLES[sid]
        result = place_text(xml, app, "대전.결과")
        row_ok = (
            got == expected["라운드"]
            and expected["결과"] in result
            and has_numbers_in_order(result, expected["점수"][0], expected["점수"][1])
        )
        if expected["데모"] and "데모 시나리오" not in all_text(xml, app["package"]):
            row_ok = False
        ok = ok and row_ok
        bits.append(f"{sid} {' '.join(v or '?' for v in got)} {result}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_battle_3(first_xml, second_xml, app):
    first = place_text(first_xml, app, "대전.전적")
    second = place_text(second_xml, app, "대전.전적")
    ok = "1승" in first and "0무" in first and "0패" in first and "2승" in second and "0무" in second and "0패" in second
    return Verdict(ok, f"{first} / {second}")


@pure
def judge_battle_4(xml, app):
    text = all_text(xml, app["package"])
    nodes = find_nodes(xml, app["places"]["대전.시작"]["id"], app["package"])
    enabled = nodes[0]["enabled"] if nodes else ""
    return Verdict("먼저 점검해 주세요" in text and enabled == "false", f"enabled={enabled} {text}")


@pure
def judge_common_1(on_xmls, off_xmls, app):
    package = app["package"]
    on_ok = len(on_xmls) == 6 and all("데모 데이터" in all_text(xml, package) for xml in on_xmls)
    off_ok = len(off_xmls) == 6 and all("데모 데이터" not in all_text(xml, package) for xml in off_xmls)
    return Verdict(on_ok and off_ok, f"켜짐 {len(on_xmls)} 꺼짐 {len(off_xmls)}")


@pure
def judge_common_3(bundle, app):
    ok = True
    bits = []
    for sid in ("D3", "D5"):
        expected = SCENARIOS[sid]
        screens = bundle.get(sid, {})
        home = place_text(screens.get("홈", ""), app, "홈.종합")
        grade = place_text(screens.get("홈", ""), app, "홈.등급")
        result = place_text(screens.get("점검", ""), app, "점검.결과")
        delta = place_text(screens.get("점검", ""), app, "점검.카테고리")
        answer = place_text(screens.get("보이스", ""), app, "보이스.답")
        states = category_states(screens.get("상태", ""), app)
        from_delta = [first_number(category_slot(delta, name) or "") for name in CATEGORY_NAMES]
        from_cards = [first_number(states.get(name, "")) for name in CATEGORY_NAMES]
        want = [expected[name] for name in CATEGORY_NAMES]
        row_ok = (
            contains_number(home, expected["종합"])
            and contains_number(result, expected["종합"])
            and contains_number(answer, expected["종합"])
            and grade_of(grade) == expected["등급"]
            and grade_of(result) == expected["등급"]
            and from_delta == want
            and from_cards == want
        )
        ok = ok and row_ok
        bits.append(f"{sid} {home} {result} {answer} {from_delta}")
    return Verdict(ok, " / ".join(bits))


class Adb:
    """기준표 6장의 명령을 그대로 부른다. 시험은 run 을 가짜로 넣는다."""

    def __init__(self, run=None, sleep=None):
        self.run = run or subprocess.run
        self.sleep = sleep or __import__("time").sleep

    def _call(self, args):
        return self.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")

    def dump(self):
        for _ in range(5):
            self._call(["adb", "shell", "rm", "-f", "/sdcard/u.xml"])
            dumped = self._call(["adb", "shell", "uiautomator", "dump", "/sdcard/u.xml"])
            output = (dumped.stdout or "") + "\n" + (dumped.stderr or "")
            if "dumped to" not in output:
                self.sleep(2)
                continue
            cat = self._call(["adb", "shell", "cat", "/sdcard/u.xml"])
            return cat.stdout or ""
        return ""

    def tap(self, x, y):
        self._call(["adb", "shell", "input", "tap", str(x), str(y)])

    def text(self, value):
        self._call(["adb", "shell", "input", "text", value])

    def airplane(self, on):
        self._call(["adb", "shell", "cmd", "connectivity", "airplane-mode", "enable" if on else "disable"])

    def clear(self, package):
        self._call(["adb", "shell", "pm", "clear", package])

    def force_stop(self, package):
        self._call(["adb", "shell", "am", "force-stop", package])

    def start(self, package):
        resolved = self._call(["adb", "shell", "cmd", "package", "resolve-activity", "--brief", package])
        lines = [line.strip() for line in (resolved.stdout or "").splitlines() if line.strip()]
        if not lines:
            raise DeviceError("시작할 액티비티가 없다")
        self._call(["adb", "shell", "am", "start", "-W", "-n", lines[-1]])

    def prop(self, name):
        result = self._call(["adb", "shell", "getprop", name])
        return (result.stdout or "").strip()


class Runner:
    def __init__(self, app, adb):
        self.app = app
        self.adb = adb
        self.pkg = app["package"]
        self.ops = app["ops"]
        self.airplane_on = False

    def dump(self):
        xml = self.adb.dump()
        if not xml or "<" not in xml:
            raise DeviceError("화면 글자를 모으지 못했다")
        return xml

    def tap_id(self, resource_id):
        xml = self.dump()
        nodes = find_nodes(xml, resource_id, self.pkg)
        if not nodes or not nodes[0]["bounds"]:
            raise DeviceError(f"노드 없음: {resource_id}")
        x, y = bounds_center(nodes[0]["bounds"])
        self.adb.tap(x, y)

    def tap_seq(self, ids):
        for resource_id in ids:
            self.tap_id(resource_id)

    def tap_text(self, text):
        xml = self.dump()
        for node in elements(xml, self.pkg):
            if node.attrib.get("text") == text and node.attrib.get("bounds"):
                x, y = bounds_center(node.attrib["bounds"])
                self.adb.tap(x, y)
                return
        raise DeviceError(f"글자 노드 없음: {text}")

    def go(self, name):
        self.tap_seq(self.ops["조작-3"][name])

    def wait_until(self, pred, limit=6):
        last = ""
        for _ in range(limit):
            last = self.dump()
            try:
                if pred(last):
                    return last
            except Exception:
                continue
        return last

    def ensure_demo(self, on):
        nav = self.ops["조작-1"]["켜기"][:-1]
        switch = self.ops["조작-1"]["켜기"][-1]
        self.tap_seq(nav)
        xml = self.dump()
        nodes = find_nodes(xml, switch, self.pkg)
        if not nodes:
            raise DeviceError("데모 스위치가 없다")
        if (nodes[0]["checked"] == "true") == on:
            return
        self.tap_id(switch)
        self.tap_seq(nav)
        xml = self.dump()
        nodes = find_nodes(xml, switch, self.pkg)
        if not nodes or (nodes[0]["checked"] == "true") != on:
            raise DeviceError("데모 스위치가 바뀌지 않았다")

    def select_scenario(self, sid):
        self.ensure_demo(True)
        self.tap_seq(self.ops["조작-2"][sid])

    def run_inspect(self):
        self.tap_seq(self.ops["조작-3"]["홈"])
        self.tap_seq(self.ops["조작-4"]["시작"])

        def ready(xml):
            text = place_text(xml, self.app, "점검.결과")
            return bool(text) and "점검 중" not in text

        return self.wait_until(ready)

    def fresh(self):
        self.adb.clear(self.pkg)
        self.adb.start(self.pkg)
        skip = self.ops["첫실행"][0]
        home = self.app["places"]["홈.등급"]["id"]
        xml = self.wait_until(lambda dumped: find_nodes(dumped, skip, self.pkg) or find_nodes(dumped, home, self.pkg))
        if find_nodes(xml, skip, self.pkg) and not find_nodes(xml, home, self.pkg):
            self.tap_id(skip)
            self.wait_until(lambda dumped: find_nodes(dumped, home, self.pkg))

    def home_after(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("홈")
        return self.wait_until(lambda xml: place_text(xml, self.app, "홈.등급") != "")

    def ask(self, key):
        self.go("AI 보이스")
        before_xml = self.dump()
        before = place_text(before_xml, self.app, "보이스.답")
        seq = self.ops["조작-6"][key]
        if key == "질문-무관":
            self.tap_id(seq[0])
            self.adb.text(self.app["questions"][key].replace(" ", "%s"))
            self.tap_id(seq[1])
        else:
            self.tap_seq(seq)
        return self.wait_until(lambda xml: place_text(xml, self.app, "보이스.답") != before)

    def finish_battle(self):
        self.go("대전하기")
        self.tap_seq(self.ops["조작-7"])
        skip_ids = self.ops.get("대전건너뛰기") or []
        if skip_ids:
            xml = self.dump()
            if find_nodes(xml, skip_ids[0], self.pkg):
                self.tap_id(skip_ids[0])

        def ready(xml):
            text = place_text(xml, self.app, "대전.결과")
            return "승리" in text or "패배" in text or "무승부" in text

        return self.wait_until(ready)

    def set_airplane(self, on):
        self.adb.airplane(on)
        self.airplane_on = on

    def run_item(self, item):
        return {
            "홈-1": self.item_home_1,
            "홈-2": self.item_home_2,
            "홈-3": self.item_home_3,
            "점검-2": self.item_inspect_2,
            "점검-3": self.item_inspect_3,
            "점검-5": self.item_inspect_5,
            "상태-1": self.item_status_1,
            "상태-2": self.item_status_2,
            "상태-3": self.item_status_3,
            "상태-4": self.item_status_4,
            "상태-5": self.item_status_5,
            "보이스-3": self.item_voice_3,
            "보이스-4": self.item_voice_4,
            "보이스-5": self.item_voice_5,
            "대전-1": self.item_battle_1,
            "대전-2": self.item_battle_2,
            "대전-3": self.item_battle_3,
            "대전-4": self.item_battle_4,
            "공통-1": self.item_common_1,
            "공통-3": self.item_common_3,
        }[item]()

    def item_home_1(self):
        verdict = judge_home_1(self.home_after("D3"), self.home_after("D0"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_home_2(self):
        screens = {"D3": self.home_after("D3"), "D1": self.home_after("D1")}
        verdict = judge_home_2(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_home_3(self):
        self.select_scenario("D3")
        self.run_inspect()
        steps = []
        for name in HOME_BUTTONS:
            self.go(name)
            screen = self.dump()
            self.go("홈")
            steps.append({"xml": screen, "home": self.dump()})
        verdict = judge_home_3(steps, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_inspect_2(self):
        self.select_scenario("D6")
        inspect = self.run_inspect()
        self.go("상태관리")
        verdict = judge_inspect_2(inspect, self.dump(), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_inspect_3(self):
        screens = {}
        for sid in SCENARIO_ORDER:
            self.select_scenario(sid)
            screens[sid] = self.run_inspect()
        verdict = judge_inspect_3(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_inspect_5(self):
        self.fresh()
        self.go("홈")
        before = self.dump()
        self.run_inspect()
        self.adb.force_stop(self.pkg)
        self.adb.start(self.pkg)
        home_id = self.app["places"]["홈.마지막점검"]["id"]
        after = self.wait_until(lambda xml: find_nodes(xml, home_id, self.pkg))
        verdict = judge_inspect_5(before, after, self.app)
        return _done(verdict.ok, verdict.measured)

    def _status_after(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("상태관리")
        return self.dump()

    def item_status_1(self):
        verdict = judge_status_1(self._status_after("D3"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_status_2(self):
        screens = {sid: self._status_after(sid) for sid in SCENARIO_ORDER}
        verdict = judge_status_2(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_status_3(self):
        screens = {sid: self._status_after(sid) for sid in ("D3", "D1", "D4", "D6")}
        verdict = judge_status_3(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_status_4(self):
        self.select_scenario("D3")
        self.run_inspect()
        self.go("상태관리")
        self.tap_text("제동·조향")
        brake = self.dump()
        self.tap_text("타이어")
        verdict = judge_status_4(brake, self.dump(), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_status_5(self):
        verdict = judge_status_5(self._status_after("D3"), self.app)
        return _done(verdict.ok, verdict.measured)

    def _voice(self, question):
        self.select_scenario("D3")
        self.run_inspect()
        self.set_airplane(True)
        try:
            return self.ask(question)
        finally:
            self.set_airplane(False)

    def item_voice_3(self):
        verdict = judge_voice_3(self._voice("질문-경고"), self.app)
        if verdict.ok:
            return ("", verdict.measured, f"서버 끊김 쪽은 정차가 있다. {PARTIAL}")
        return ("실패", verdict.measured, PARTIAL)

    def item_voice_4(self):
        verdict = judge_voice_4(self._voice("질문-등급"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_voice_5(self):
        verdict = judge_voice_5(self._voice("질문-무관"), self.app)
        if verdict.ok:
            return ("", verdict.measured, f"서버 끊김 쪽은 거절 조건에 맞다. {PARTIAL}")
        return ("실패", verdict.measured, PARTIAL)

    def _battle_lobby(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("대전하기")
        return self.wait_until(lambda xml: place_text(xml, self.app, "대전.상대") != "")

    def item_battle_1(self):
        screens = {sid: self._battle_lobby(sid) for sid in ("D3", "D0")}
        verdict = judge_battle_1(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_battle_2(self):
        screens = {}
        for sid in BATTLE_ORDER:
            self.select_scenario(sid)
            self.run_inspect()
            screens[sid] = self.finish_battle()
        verdict = judge_battle_2(screens, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_battle_3(self):
        self.fresh()
        self.select_scenario("D0")
        self.run_inspect()
        self.finish_battle()
        first = self.wait_until(lambda xml: "1승" in place_text(xml, self.app, "대전.전적"))
        self.finish_battle()
        second = self.wait_until(lambda xml: "2승" in place_text(xml, self.app, "대전.전적"))
        verdict = judge_battle_3(first, second, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_battle_4(self):
        self.fresh()
        self.go("대전하기")
        verdict = judge_battle_4(self.dump(), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_common_1(self):
        self.ensure_demo(True)
        on_xmls = []
        for name in COMMON_SCREENS:
            self.go(name)
            on_xmls.append(self.dump())
        self.ensure_demo(False)
        off_xmls = []
        for name in COMMON_SCREENS:
            self.go(name)
            off_xmls.append(self.dump())
        verdict = judge_common_1(on_xmls, off_xmls, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_common_3(self):
        bundle = {}
        for sid in ("D3", "D5"):
            self.select_scenario(sid)
            inspect = self.run_inspect()
            self.go("홈")
            home = self.dump()
            self.go("상태관리")
            status = self.dump()
            self.set_airplane(True)
            try:
                voice = self.ask("질문-등급")
            finally:
                self.set_airplane(False)
            bundle[sid] = {"홈": home, "점검": inspect, "상태": status, "보이스": voice}
        verdict = judge_common_3(bundle, self.app)
        return _done(verdict.ok, verdict.measured)


def _cell(text):
    cleaned = (text or "").replace("|", "/").replace("\n", " ").strip()
    if len(cleaned) > 400:
        return cleaned[:400] + "…"
    return cleaned


def render_report(app, rows, day, board):
    repo = app.get("repo") or app.get("package") or ""
    by_id = {row[0]: row for row in rows}
    passed = sum(1 for row in rows if row[1] == "통과")
    lines = [
        f"앱(저장소): {repo}    만든 쪽:    평가한 날: {day}",
        f"에뮬레이터 판: {board}    평가자: moca_eval.py",
        "",
        "| 번호 | 결과 | 잰 값 (화면 글자, 시간) | 덧붙임 |",
        "|---|---|---|---|",
    ]
    for item, _mode in ITEMS:
        result, measured, note = by_id[item][1], by_id[item][2], by_id[item][3]
        lines.append(f"| {item} | {_cell(result)} | {_cell(measured)} | {_cell(note)} |")
    gate = [item for item in ("점검-2", "보이스-3", "공통-1") if by_id[item][1] == "실패"]
    lines.append("")
    lines.append(f"통과 수: {passed} / 26")
    lines.append("후보에서 빼는 항목(점검-2, 보이스-3, 공통-1) 가운데 실패: " + (", ".join(gate) if gate else "없음"))
    lines.append("점검-4 평균 시간:        초      보이스-6 평균 시간:        초")
    lines.append("")
    return "\n".join(lines)


def evaluate(app, adb=None, today=None):
    device = adb or Adb()
    runner = Runner(app, device)
    rows = []
    try:
        for item, mode in ITEMS:
            if mode == "수동":
                rows.append((item, "", "", MANUAL[item]))
                continue
            try:
                result, measured, note = runner.run_item(item)
                rows.append((item, result, measured, note))
            except Exception as exc:
                rows.append((item, "실패", "", f"{type(exc).__name__}: {exc}"))
    finally:
        if runner.airplane_on:
            try:
                runner.set_airplane(False)
            except Exception:
                pass
    board = ""
    try:
        board = device.prop("ro.build.display.id")
    except Exception:
        board = ""
    return render_report(app, rows, today or date.today().isoformat(), board)


def load_app(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if "--list" in args:
        sys.stdout.write(list_text())
        return 0
    if "--answers" in args:
        sys.stdout.write(answer_text())
        return 0
    if "--app" in args:
        try:
            app_path = args[args.index("--app") + 1]
            out_path = args[args.index("--out") + 1]
        except IndexError:
            sys.stderr.write("사용법: python3 tools/moca_eval.py --app APP.json --out 기록지.md\n")
            return 2
        Path(out_path).write_text(evaluate(load_app(app_path)), encoding="utf-8")
        return 0
    sys.stderr.write("사용법: python3 tools/moca_eval.py --list | --answers | --app APP.json --out 기록지.md\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
