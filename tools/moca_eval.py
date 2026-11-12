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

SCENARIO_ORDER = ["D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7"]
BATTLE_ORDER = ["D0", "D1", "D2", "D4", "D6", "D3", "D5", "D7"]

# 기준표 3-2. 타이어 「없음」은 None. 「다음」은 표의 칸을 그대로 둔다.
SCENARIOS = {
    "D0": {"차": "이상 없음", "동력": 100, "제동·조향": 100, "타이어": 100, "전기": 100, "안전": 100, "건강": 100, "종합": 85, "등급": "A", "다음": "S까지 5점"},
    "D1": {"차": "엔진오일 부족", "동력": 60, "제동·조향": 100, "타이어": 100, "전기": 100, "안전": 100, "건강": 92, "종합": 80, "등급": "A", "다음": "S까지 10점"},
    "D2": {"차": "타이어 공기압 노란 경고등, 저압 바퀴 2개", "동력": 100, "제동·조향": 100, "타이어": 60, "전기": 100, "안전": 100, "건강": 92, "종합": 80, "등급": "A", "다음": "S까지 10점"},
    "D3": {"차": "ABS 빨간 경고등, 타이어 공기압 노란 경고등, 저압 바퀴 1개", "동력": 100, "제동·조향": 50, "타이어": 75, "전기": 100, "안전": 100, "건강": 85, "종합": 75, "등급": "B", "다음": "A까지 5점"},
    "D4": {"차": "배터리 9%", "동력": 100, "제동·조향": 100, "타이어": 100, "전기": 90, "안전": 100, "건강": 98, "종합": 84, "등급": "A", "다음": "S까지 6점"},
    "D5": {"차": "엔진오일 부족, 타이어 공기압 노란 경고등, 저압 바퀴 1개", "동력": 60, "제동·조향": 100, "타이어": 75, "전기": 100, "안전": 100, "건강": 87, "종합": 76, "등급": "B", "다음": "A까지 4점"},
    "D6": {"차": "타이어 값을 지원하지 않음", "동력": 100, "제동·조향": 100, "타이어": None, "전기": 100, "안전": 100, "건강": 100, "종합": 85, "등급": "A", "다음": "S까지 5점"},
    "D7": {"차": "워셔액 부족", "동력": 100, "제동·조향": 100, "타이어": 100, "전기": 100, "안전": 90, "건강": 98, "종합": 84, "등급": "A", "다음": "S까지 6점"},
}

# 기준표 3-3. 「결과」는 화면에서 찾을 낱말, 「결과칸」은 표의 칸 전체.
BATTLES = {
    "D0": {"상대": "달리는곰", "등급": "A", "상대점수": 84, "라운드": ["무", "무", "승", "무", "무"], "점수": (1, 0), "결과": "승리", "결과칸": "승리"},
    "D1": {"상대": "세차는사랑", "등급": "A", "상대점수": 83, "라운드": ["패", "무", "무", "승", "무"], "점수": (1, 1), "결과": "패배", "결과칸": "패배 (종합 80 대 83)"},
    "D2": {"상대": "출근길요정", "등급": "S", "상대점수": 91, "라운드": ["무", "무", "패", "무", "무"], "점수": (0, 1), "결과": "패배", "결과칸": "패배"},
    "D4": {"상대": "주말캠퍼", "등급": "S", "상대점수": 92, "라운드": ["무", "무", "무", "패", "무"], "점수": (0, 1), "결과": "패배", "결과칸": "패배"},
    "D6": {"상대": "퇴근길질주", "등급": "B", "상대점수": 76, "라운드": ["승", "무", "무", "무", "무"], "점수": (1, 0), "결과": "승리", "결과칸": "승리"},
    "D3": {"상대": "라이벌드라이버", "등급": "A", "상대점수": 88, "라운드": ["무", "패", "패", "승", "승"], "점수": (2, 2), "결과": "패배", "결과칸": "패배 (종합 75 대 88)"},
    "D5": {"상대": "경고등수집가", "등급": "C", "상대점수": 64, "라운드": ["패", "승", "무", "무", "승"], "점수": (2, 1), "결과": "승리", "결과칸": "승리"},
    "D7": {"상대": "초보운전3년차", "등급": "A", "상대점수": 85, "라운드": ["승", "무", "무", "무", "패"], "점수": (1, 1), "결과": "패배", "결과칸": "패배 (종합 84 대 85)"},
}

ITEMS = [
    ("홈-1", "자동"), ("홈-2", "자동"), ("홈-3", "자동"),
    ("점검-1", "수동"), ("점검-2", "자동"), ("점검-3", "자동"), ("점검-4", "수동"), ("점검-5", "자동"),
    ("상태-1", "자동"), ("상태-2", "자동"), ("상태-3", "자동"), ("상태-4", "자동"), ("상태-5", "자동"),
    ("보이스-1", "수동"), ("보이스-2", "수동"), ("보이스-3", "자동"), ("보이스-4", "자동"),
    ("보이스-5", "자동"), ("보이스-6", "수동"),
    ("대전-1", "자동"), ("대전-2", "자동"), ("대전-3", "자동"), ("대전-4", "자동"),
    ("공통-1", "자동"), ("공통-2", "수동"), ("공통-3", "자동"),
    ("이어-1", "자동"), ("이어-2", "자동"), ("이어-3", "자동"),
    ("순위-1", "자동"), ("순위-2", "자동"),
    ("화면-1", "수동"), ("화면-2", "자동"), ("화면-3", "수동"), ("화면-4", "자동"),
    ("화면-5", "자동"), ("화면-6", "자동"), ("화면-7", "자동"), ("화면-8", "수동"),
]

MANUAL = {
    "점검-1": "수동. 까닭: 데모를 끄고 새로 설치한 뒤 카드 상세 다섯의 원본 값을 확인하는 항목이다.",
    "점검-4": "수동. 까닭: 화면 녹화(기준표 명령 4)로 3초를 재야 한다.",
    "보이스-1": "수동. 까닭: 마이크가 있는 기기에서 소리 내어 말해야 한다.",
    "보이스-2": "수동. 까닭: 서버를 연결한 상태에서 같은 질문을 세 번 보내야 한다.",
    "보이스-6": "수동. 까닭: 화면 녹화(기준표 명령 4)로 6초를 재야 한다.",
    "공통-2": "수동. 까닭: 주행 제한 코드 검색과 주행 상태는 에뮬레이터에서 자동으로 재지 않는다.",
    "화면-1": "수동. 까닭: 누르는 횟수는 화면 글자 한 장이 아니라 조작을 세어야 한다.",
    "화면-3": "수동. 까닭: 채워진 강조 단추인지는 화면 글자에 없다.",
    "화면-8": "수동. 까닭: 목적지가 같은지는 화면 글자에 없다.",
}

# 기준표 3-5. lead 가 2026-11-11 에 잰 값. 없는 시나리오는 재지 않았다.
MEASURED_RANKS = {"D3": 9, "D0": 5}

PARTIAL = "서버 연결 쪽은 수동. 까닭: 자동으로 재는 것은 서버 끊김 쪽뿐이다."
COMMON_SCREENS = ["홈", "결과가 보이는 화면", "상태관리", "AI 보이스", "대전하기", "설정"]
HOME_BUTTONS = ["결과가 보이는 화면", "상태관리", "AI 보이스", "대전하기"]


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
            label = type(exc).__name__
            if label == "DeviceError":
                label = "오류"
            return Verdict(False, f"{label}: {exc}")

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
    lines.append("상대의 카테고리 점수(동력, 제동·조향, 타이어, 전기, 안전): 달리는곰 100·100·75·100·100, 세차는사랑 100·100·100·90·100, 출근길요정 100·100·80·100·100, 주말캠퍼 100·100·100·100·100, 퇴근길질주 60·100·75·100·100, 초보운전3년차 60·100·100·100·100.")
    lines.append("")
    lines.append("3-5 순위 정답")
    lines.append("D3\t9위\t13명")
    lines.append("D0\t5위\t13명")
    lines.append("D7\t6위\t13명")
    lines.append("다른 시나리오는 재지 않았다.")
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
                "text": node.attrib.get("text", "") or node.attrib.get("content-desc", ""),
                "bounds": node.attrib.get("bounds", ""),
                "enabled": node.attrib.get("enabled", ""),
                "checked": node.attrib.get("checked", ""),
                "clickable": node.attrib.get("clickable", ""),
                "scrollable": node.attrib.get("scrollable", ""),
                "selected": node.attrib.get("selected", ""),
                "resource_id": resource_id,
            })
    return found


def bounds_box(bounds):
    matched = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds.strip())
    if not matched:
        raise ValueError(f"bounds 형식이 아니다: {bounds}")
    return tuple(int(group) for group in matched.groups())


def bounds_center(bounds):
    x1, y1, x2, y2 = bounds_box(bounds)
    return (x1 + x2) // 2, (y1 + y2) // 2


def bounds_height(bounds):
    _x1, y1, _x2, y2 = bounds_box(bounds)
    return y2 - y1


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
    spec = app["places"]["상태.카드"]
    if "rows" in spec:
        found = {}
        package = app["package"]
        for row in spec["rows"]:
            status = " ".join(node["text"] for node in find_nodes(xml, row["status"], package))
            score = " ".join(node["text"] for node in find_nodes(xml, row["score"], package))
            found[row["name"]] = f"{status} {score}".strip()
        return found
    container = first_element(xml, spec["container"], app["package"])
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
    spec = app["places"]["상태.상세"]
    if "ids" in spec or "id" in spec:
        return place_text(xml, app, "상태.상세")
    container = first_element(xml, spec["container"], app["package"])
    if container is None:
        return ""
    package = app["package"]
    for parent in container.iter("node"):
        kids = _packaged_children(parent, package)
        for index, kid in enumerate(kids):
            if kid.attrib.get("text", "") == name and index + 2 < len(kids):
                return kids[index + 2].attrib.get("text", "")
    return ""


def place_found(xml, app, name):
    spec = app["places"][name]
    package = app["package"]
    if "id" in spec:
        return bool(find_nodes(xml, spec["id"], package))
    if "ids" in spec:
        return all(find_nodes(xml, resource_id, package) for resource_id in spec["ids"])
    if "rows" in spec:
        return all(find_nodes(xml, row["status"], package) for row in spec["rows"])
    if "container" in spec:
        return first_element(xml, spec["container"], package) is not None
    return False


def any_text(xml):
    parts = []
    if not xml or "<" not in xml:
        return ""
    for node in parse_root(xml).iter("node"):
        text = node.attrib.get("text", "")
        if text:
            parts.append(text)
    return "\n".join(parts)


def rank_number(text):
    """`위` 바로 앞의 숫자. 없으면 첫 숫자."""
    matched = re.search(r"(?<!\d)(\d+)\s*위", text)
    if matched:
        return int(matched.group(1))
    return first_number(text)


def my_rank_number(xml, app):
    spec = app["places"]["순위.내줄"]
    container = first_element(xml, spec["container"], app["package"])
    if container is None:
        return None
    package = app["package"]
    for kid in container.iter("node"):
        if kid is container or kid.attrib.get("package") != package:
            continue
        if kid.attrib.get("selected") != "true":
            continue
        texts = []
        own = kid.attrib.get("text", "")
        if own:
            texts.append(own)
        for node in kid.iter("node"):
            if node is kid:
                continue
            text = node.attrib.get("text", "")
            if text:
                texts.append(text)
        return rank_number(" ".join(texts))
    return None


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
        ("결과가 보이는 화면", lambda xml: place_found(xml, app, "점검.결과")),
        ("상태관리", lambda xml: place_found(xml, app, "상태.카드") or all(
            count_exact(xml, package, name) >= 1 for name in CATEGORY_NAMES
        )),
        ("AI 보이스", lambda xml: place_found(xml, app, "보이스.상태")),
        ("대전하기", lambda xml: place_found(xml, app, "대전.시작")),
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
def judge_inspect_5(first_xml, next_day_xml, app):
    first = place_text(first_xml, app, "홈.연속")
    later = place_text(next_day_xml, app, "홈.연속")
    ok = first_number(first) == 1 and first_number(later) == 2
    return Verdict(ok, f"첫날 {first} / 다음 날 {later}")


@pure
def judge_status_1(xml, app):
    spec = app["places"]["상태.카드"]
    states = category_states(xml, app)
    numbers_ok = all(first_number(states.get(name, "")) is not None for name in CATEGORY_NAMES)
    if "rows" in spec and "container" in spec:
        blob = container_text(xml, spec["container"], app["package"])
        names_ok = all(name in blob for name in CATEGORY_NAMES)
        return Verdict(names_ok and numbers_ok and len(states) == len(CATEGORY_NAMES), str(states))
    package = app["package"]
    counts_ok = all(count_exact(xml, package, name) == 1 for name in CATEGORY_NAMES)
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
def judge_link_1(xml, app, taps):
    keyword = app["ops"]["검색어"]
    blob = any_text(xml)
    others = []
    if xml and "<" in xml:
        for node in parse_root(xml).iter("node"):
            text = node.attrib.get("text", "").strip()
            if text and text != keyword:
                others.append(text)
    ok = taps <= 2 and keyword in blob and len(others) >= 1
    return Verdict(ok, f"횟수 {taps} / {blob}")


@pure
def judge_link_2(xml, app):
    text = place_text(xml, app, "QR.도메인")
    return Verdict("." in text, text)


@pure
def judge_link_3(xml, app):
    nodes = find_nodes(xml, app["places"]["해결.단추"]["id"], app["package"])
    return Verdict(len(nodes) == 0, f"해결 단추 {len(nodes)}")


@pure
def judge_rank_1(pairs, app):
    ok = True
    bits = []
    for sid, (home_xml, rank_xml) in pairs.items():
        home_n = rank_number(place_text(home_xml, app, "홈.순위"))
        mine = my_rank_number(rank_xml, app)
        row_ok = home_n is not None and home_n == mine
        if sid in MEASURED_RANKS:
            row_ok = row_ok and home_n == MEASURED_RANKS[sid]
        ok = ok and row_ok
        bits.append(f"{sid} 홈 {home_n} 내줄 {mine}")
    return Verdict(ok, " / ".join(bits))


@pure
def judge_rank_2(xml, app):
    text = all_text(xml, app["package"])
    return Verdict("연습 순위" in text, text)


def _same_line(one, other):
    return one[1] < other[3] and other[1] < one[3]


@pure
def judge_screen_2(xml, app):
    tabs = set(app["ops"]["아래탭"])
    count = 0
    for node in elements(xml, app["package"]):
        if node.attrib.get("clickable") == "true" and node.attrib.get("resource-id") not in tabs:
            count += 1
    return Verdict(count <= 6, str(count))


@pure
def judge_screen_4(xml, app):
    boxes = []
    texts = []
    for node in elements(xml, app["package"]):
        raw = node.attrib.get("bounds", "")
        if not raw:
            continue
        box = bounds_box(raw)
        boxes.append(box)
        if node.attrib.get("text", ""):
            texts.append(box)
    if not boxes:
        return Verdict(False, "창 없음")
    top = min(box[1] for box in boxes)
    bottom = max(box[3] for box in boxes)
    mid = (top + bottom) / 2
    lower = [box for box in texts if (box[1] + box[3]) / 2 > mid]
    return Verdict(len(lower) >= 3, str(len(lower)))


@pure
def judge_screen_5(xml, app):
    grade_id = app["places"]["화면.등급"]["id"]
    nodes = find_nodes(xml, grade_id, app["package"])
    if not nodes or not nodes[0]["bounds"]:
        return Verdict(False, "등급 노드 없음")
    height = bounds_height(nodes[0]["bounds"])
    others = []
    for node in elements(xml, app["package"]):
        if node.attrib.get("resource-id") == grade_id or not node.attrib.get("text"):
            continue
        if node.attrib.get("clickable") == "true":
            continue
        raw = node.attrib.get("bounds", "")
        if not raw:
            continue
        others.append(bounds_height(raw))
    ok = bool(others) and all(height > other for other in others)
    return Verdict(ok, f"등급 {height} / 다른 글자 {others}")


def _scrolls(xml, package):
    return any(node.attrib.get("scrollable") == "true" for node in elements(xml, package))


@pure
def judge_screen_6(home_xml, detail_xml, app):
    package = app["package"]
    home = _scrolls(home_xml, package)
    detail = _scrolls(detail_xml, package)
    return Verdict(not home and not detail, f"홈 {home} 상세 {detail}")


@pure
def judge_screen_7(xml, app):
    package = app["package"]
    seen = 0
    ok = True
    for status_id, score_id in app["places"]["화면.줄"]:
        status = find_nodes(xml, status_id, package)
        score = find_nodes(xml, score_id, package)
        if not status or not score:
            continue
        seen += 1
        if not any(word in status[0]["text"] for word in STATUS_WORDS):
            ok = False
        if not score[0]["text"]:
            ok = False
        if not status[0]["bounds"] or not score[0]["bounds"]:
            ok = False
            continue
        if not _same_line(bounds_box(status[0]["bounds"]), bounds_box(score[0]["bounds"])):
            ok = False
    return Verdict(ok and seen >= 5, str(seen))


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

    def keyevent(self, code):
        self._call(["adb", "shell", "input", "keyevent", str(code)])

    def airplane(self, on):
        self._call(["adb", "shell", "cmd", "connectivity", "airplane-mode", "enable" if on else "disable"])

    def current_user(self):
        """포그라운드 사용자. 이 에뮬레이터는 10이고, 앱은 그 사용자에게만 깔려 있다."""
        cached = getattr(self, "_user", "")
        if cached:
            return cached
        result = self._call(["adb", "shell", "am", "get-current-user"])
        user = (result.stdout or "").strip()
        if not user.isdigit():
            raise DeviceError("현재 사용자를 읽지 못했다")
        self._user = user
        return user

    def clear(self, package):
        self._call(["adb", "shell", "pm", "clear", "--user", self.current_user(), package])

    def force_stop(self, package):
        self._call(["adb", "shell", "am", "force-stop", "--user", self.current_user(), package])

    def start(self, package):
        user = self.current_user()
        resolved = self._call([
            "adb", "shell", "cmd", "package", "resolve-activity", "--user", user, "--brief", package,
        ])
        lines = [line.strip() for line in (resolved.stdout or "").splitlines() if line.strip()]
        if not lines:
            raise DeviceError("시작할 액티비티가 없다")
        self._call(["adb", "shell", "am", "start", "--user", user, "-W", "-n", lines[-1]])

    def set_clock(self, epoch_ms):
        self._call(["adb", "shell", "settings", "put", "global", "auto_time", "0"])
        self._call(["adb", "shell", "cmd", "alarm", "set-time", str(int(epoch_ms))])

    def restore_clock(self):
        now_ms = int(__import__("time").time() * 1000)
        self._call(["adb", "shell", "cmd", "alarm", "set-time", str(now_ms)])
        self._call(["adb", "shell", "settings", "put", "global", "auto_time", "1"])

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
        self.last_xml = ""

    def dump(self):
        xml = self.adb.dump()
        if not xml or "<" not in xml:
            self.last_xml = ""
            return ""
        self.last_xml = xml
        return xml

    def missing_reason(self, resource_id, xml):
        shown = any_text(xml).replace("\n", " ").strip()
        if len(shown) > 240:
            shown = shown[:240] + "…"
        return f"10초 안에 화면에서 찾지 못했다 ({resource_id}). 화면에 있던 글자: {shown or '없음'}"

    def wait_until(self, pred, limit_s=10.0, interval=0.5):
        """누른 뒤 노드가 나올 때까지. 최대 10초, 0.5초 간격."""
        elapsed = 0.0
        last = ""
        while True:
            last = self.dump()
            try:
                if pred(last):
                    return last
            except Exception:
                pass
            if elapsed >= limit_s:
                return last
            self.adb.sleep(interval)
            elapsed += interval

    def node_ready(self, xml, resource_id):
        nodes = find_nodes(xml, resource_id, self.pkg)
        return bool(nodes) and bool(nodes[0]["bounds"])

    def tap_id(self, resource_id):
        xml = self.wait_until(lambda dumped: self.node_ready(dumped, resource_id))
        nodes = find_nodes(xml, resource_id, self.pkg) if xml else []
        if not nodes or not nodes[0]["bounds"]:
            raise DeviceError(self.missing_reason(resource_id, xml))
        x, y = bounds_center(nodes[0]["bounds"])
        self.adb.tap(x, y)

    def tap_seq(self, ids):
        for resource_id in ids:
            self.tap_id(resource_id)

    def tap_text(self, text):
        xml = self.wait_until(lambda dumped: self._text_bounds(dumped, text) is not None)
        box = self._text_bounds(xml, text)
        if box is None:
            raise DeviceError(f"10초 안에 글자를 찾지 못했다: {text}. {self.missing_reason(text, xml)}")
        self.adb.tap(*box)

    def _text_bounds(self, xml, text):
        if not xml:
            return None
        try:
            found = elements(xml, self.pkg)
        except Exception:
            return None
        for node in found:
            if node.attrib.get("text") == text and node.attrib.get("bounds"):
                return bounds_center(node.attrib["bounds"])
        return None

    def go(self, name):
        self.tap_seq(self.ops["조작-3"][name])

    def _switch_id(self):
        return self.ops["조작-1"]["켜기"][-1]

    def _switch_is(self, xml, on):
        nodes = find_nodes(xml, self._switch_id(), self.pkg) if xml else []
        return bool(nodes) and (nodes[0]["checked"] == "true") == on

    def open_demo_panel(self):
        """설정 → 데모 묶음. 스위치와 시나리오는 이 묶음을 눌러야 보인다."""
        self.tap_seq(self.ops["조작-1"]["켜기"][:-1])
        switch = self._switch_id()
        xml = self.wait_until(lambda dumped: self.node_ready(dumped, switch))
        if not self.node_ready(xml, switch):
            raise DeviceError(self.missing_reason(switch, xml))
        return xml

    def ensure_demo(self, on):
        xml = self.open_demo_panel()
        if self._switch_is(xml, on):
            return
        self.tap_id(self._switch_id())
        # 스위치를 바꾸면 액티비티가 다시 만들어진다. 다시 뜨기를 기다린다.
        self.adb.sleep(0.5)
        xml = self.wait_until(lambda dumped: self._switch_is(dumped, on))
        if self._switch_is(xml, on):
            return
        xml = self.open_demo_panel()
        if not self._switch_is(xml, on):
            want = "켜짐" if on else "꺼짐"
            raise DeviceError(f"데모 스위치가 {want}으로 바뀌지 않았다. {self.missing_reason(self._switch_id(), xml)}")

    def select_scenario(self, sid):
        self.ensure_demo(True)
        scenario = self.ops["조작-2"][sid][-1]
        self.tap_id(scenario)
        # 시나리오를 고르면 액티비티가 다시 만들어진다.
        self.adb.sleep(0.5)
        home = self.ops["조작-3"]["홈"][0]
        self.wait_until(lambda xml: self.node_ready(xml, scenario) or self.node_ready(xml, home))

    def inspection_ready(self, xml):
        if not xml or "점검하는 중" in all_text(xml, self.pkg):
            return False
        return grade_of(place_text(xml, self.app, "점검.결과")) != ""

    def run_inspect(self):
        self.tap_seq(self.ops["조작-3"]["홈"])
        self.tap_seq(self.ops["조작-4"].get("시작") or [])
        return self.wait_until(self.inspection_ready)

    def pass_first_run(self):
        """첫 실행(동의하고 시작)이면 넘긴다. 그 화면에서는 아래 탭이 없다."""
        start_id = self.ops["첫실행"][0]
        home_id = self.app["places"]["홈.등급"]["id"]
        tab_id = self.ops["아래탭"][0]

        def arrived(xml):
            return self.node_ready(xml, start_id) or self.node_ready(xml, home_id) or self.node_ready(xml, tab_id)

        xml = self.wait_until(arrived)
        if not arrived(xml):
            raise DeviceError(self.missing_reason(start_id, xml))
        if self.node_ready(xml, start_id) and not self.node_ready(xml, tab_id):
            self.tap_id(start_id)
            xml = self.wait_until(lambda dumped: self.node_ready(dumped, home_id) or self.node_ready(dumped, tab_id))
            if not (self.node_ready(xml, home_id) or self.node_ready(xml, tab_id)):
                raise DeviceError(self.missing_reason(home_id, xml))

    def prepare(self):
        self.bring_up()

    def bring_up(self):
        """앱을 앞으로 가져온다. 내비게이션처럼 다른 앱이 앞에 있으면 탭이 안 보인다."""
        self.adb.start(self.pkg)
        self.pass_first_run()

    def fresh(self):
        self.adb.clear(self.pkg)
        self.adb.start(self.pkg)
        self.pass_first_run()

    def home_after(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("홈")
        return self.wait_until(lambda xml: grade_of(place_text(xml, self.app, "홈.등급")) != "")

    def ask(self, key):
        self.go("AI 보이스")
        self.wait_until(lambda xml: place_found(xml, self.app, "보이스.답"))
        before = place_text(self.dump(), self.app, "보이스.답")
        seq = self.ops["조작-6"][key]
        if key == "질문-무관":
            self.tap_id(seq[0])
            self.adb.sleep(0.5)
            self.adb.text(self.app["questions"][key].replace(" ", "%s"))
            self.adb.sleep(0.5)
            # 키보드가 보내기 단추를 가리면 탭이 닿지 않는다. 뒤로 가기는 키보드만 닫는다.
            self.adb.keyevent(4)
            self.adb.sleep(0.5)
            self.tap_id(seq[1])
        else:
            self.tap_seq(seq)
        return self.wait_until(lambda xml: place_text(xml, self.app, "보이스.답") not in ("", before))

    def _battle_done(self, xml):
        text = place_text(xml, self.app, "대전.결과")
        return "승리" in text or "패배" in text or "무승부" in text

    def _start_ready(self, xml):
        """대전을 시작하는 단추. 진 뒤 해결 단추가 보이면 「다시」가 옅은 단추로 대신 보인다."""
        for name in ("대전.시작", "대전.다시"):
            resource_id = self.app["places"][name]["id"]
            if self.node_ready(xml, resource_id):
                return resource_id
        return None

    def tap_start(self):
        xml = self.wait_until(lambda dumped: self._start_ready(dumped))
        resource_id = self._start_ready(xml)
        if not resource_id:
            raise DeviceError(self.missing_reason(self.app["places"]["대전.시작"]["id"], xml))
        self.tap_id(resource_id)

    def finish_battle(self):
        self.go("대전하기")
        self.tap_start()
        skip_ids = self.ops.get("대전건너뛰기") or []
        skip_id = skip_ids[0] if skip_ids else ""

        def skip_or_done(xml):
            return self._battle_done(xml) or (skip_id and self.node_ready(xml, skip_id))

        xml = self.wait_until(skip_or_done)
        if skip_id and self.node_ready(xml, skip_id) and not self._battle_done(xml):
            self.tap_id(skip_id)
        return self.wait_until(self._battle_done)

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
            "이어-1": self.item_link_1,
            "이어-2": self.item_link_2,
            "이어-3": self.item_link_3,
            "순위-1": self.item_rank_1,
            "순위-2": self.item_rank_2,
            "화면-2": self.item_screen_2,
            "화면-4": self.item_screen_4,
            "화면-5": self.item_screen_5,
            "화면-6": self.item_screen_6,
            "화면-7": self.item_screen_7,
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

        def counted(xml):
            return first_number(place_text(xml, self.app, "홈.연속")) is not None

        first = self.wait_until(counted)
        self.adb.force_stop(self.pkg)
        self.adb.set_clock(__import__("time").time() * 1000 + 86_400_000)
        try:
            self.bring_up()
            later = self.wait_until(counted)
        finally:
            self.adb.force_stop(self.pkg)
            self.adb.restore_clock()
            self.adb.clear(self.pkg)
        verdict = judge_inspect_5(first, later, self.app)
        return _done(verdict.ok, verdict.measured)

    def _status_after(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("상태관리")
        return self.wait_until(lambda xml: place_found(xml, self.app, "상태.카드"))

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
        self.tap_seq(self.ops["조작-5"]["제동·조향"])
        brake = self.wait_until(lambda xml: place_text(xml, self.app, "상태.상세") != "")
        brake_text = place_text(brake, self.app, "상태.상세")
        self.tap_seq(self.ops["조작-5"]["타이어"])
        tire = self.wait_until(lambda xml: place_text(xml, self.app, "상태.상세") not in ("", brake_text))
        verdict = judge_status_4(brake, tire, self.app)
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
        start_id = self.app["places"]["대전.시작"]["id"]
        xml = self.wait_until(
            lambda dumped: "먼저 점검해 주세요" in all_text(dumped, self.pkg) or self.node_ready(dumped, start_id)
        )
        verdict = judge_battle_4(xml, self.app)
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

    def _home_ready(self, sid):
        self.select_scenario(sid)
        self.run_inspect()
        self.go("홈")
        return self.wait_until(lambda xml: grade_of(place_text(xml, self.app, "홈.등급")) != "")

    def item_link_1(self):
        self._home_ready("D3")
        ids = self.ops["조작-9"]
        self.tap_seq(ids)
        keyword = self.app["ops"]["검색어"]
        xml = self.wait_until(lambda dumped: keyword in any_text(dumped))
        verdict = judge_link_1(xml, self.app, len(ids))
        return _done(verdict.ok, verdict.measured)

    def item_link_2(self):
        spec = self.ops["조작-10"]
        sid = spec["시나리오"]
        ids = spec["누르기"]
        self._home_ready(sid)
        self.tap_seq(ids)
        xml = self.wait_until(lambda dumped: "." in place_text(dumped, self.app, "QR.도메인"))
        verdict = judge_link_2(xml, self.app)
        # QR 창을 닫지 않으면 다음 항목이 탭을 찾지 못한다.
        if place_text(xml, self.app, "QR.도메인"):
            self.tap_seq(spec.get("닫기") or [])
        return _done(verdict.ok, verdict.measured)

    def item_link_3(self):
        verdict = judge_link_3(self._home_ready("D0"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_rank_1(self):
        pairs = {}
        for sid in ("D3", "D0"):
            home = self._home_ready(sid)
            self.go("대전하기")
            pairs[sid] = (home, self.dump())
        verdict = judge_rank_1(pairs, self.app)
        return _done(verdict.ok, verdict.measured)

    def item_rank_2(self):
        self.go("대전하기")
        verdict = judge_rank_2(self.dump(), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_screen_2(self):
        verdict = judge_screen_2(self._home_ready("D3"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_screen_4(self):
        verdict = judge_screen_4(self._home_ready("D3"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_screen_5(self):
        verdict = judge_screen_5(self._home_ready("D3"), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_screen_6(self):
        home = self._home_ready("D3")
        self.go("상태관리")
        verdict = judge_screen_6(home, self.dump(), self.app)
        return _done(verdict.ok, verdict.measured)

    def item_screen_7(self):
        home = self._home_ready("D3")
        home_verdict = judge_screen_7(home, self.app)
        self.go("상태관리")
        detail_verdict = judge_screen_7(self.dump(), self.app)
        ok = home_verdict.ok and detail_verdict.ok
        return _done(ok, f"홈 {home_verdict.measured} / 상세 {detail_verdict.measured}")


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
    lines.append(f"통과 수: {passed} / {len(ITEMS)}")
    lines.append("후보에서 빼는 항목(점검-2, 보이스-3, 공통-1) 가운데 실패: " + (", ".join(gate) if gate else "없음"))
    lines.append("점검-4 평균 시간:        초      보이스-6 평균 시간:        초")
    lines.append("")
    return "\n".join(lines)


def _screen_excerpt(xml):
    shown = any_text(xml).replace("\n", " ").strip()
    if len(shown) > 240:
        return shown[:240] + "…"
    return shown


def evaluate(app, adb=None, today=None):
    device = adb or Adb()
    runner = Runner(app, device)
    rows = []
    try:
        prelude = ""
        try:
            runner.prepare()
        except DeviceError as exc:
            prelude = str(exc)
        for item, mode in ITEMS:
            if mode == "수동":
                rows.append((item, "", "", MANUAL[item]))
                continue
            if prelude:
                rows.append((item, "실패", _screen_excerpt(runner.last_xml), prelude))
                sys.stderr.write(f"{item} 실패\n")
                sys.stderr.flush()
                continue
            try:
                runner.bring_up()
                result, measured, note = runner.run_item(item)
                rows.append((item, result, measured, note))
                sys.stderr.write(f"{item} {result or '수동'}\n")
                sys.stderr.flush()
            except DeviceError as exc:
                rows.append((item, "실패", _screen_excerpt(runner.last_xml), str(exc)))
                sys.stderr.write(f"{item} 실패\n")
                sys.stderr.flush()
            except Exception as exc:
                rows.append((item, "실패", "", f"{type(exc).__name__}: {exc}"))
                sys.stderr.write(f"{item} 실패\n")
                sys.stderr.flush()
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
