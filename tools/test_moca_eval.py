import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("moca_eval", ROOT / "moca_eval.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

PKG = "ai.moca.app"


def node(resource_id, text, bounds="[0,0][10,10]", enabled="true", package=PKG, inner="",
         clickable="false", selected="false", scrollable="false"):
    return (
        f'<node package="{package}" resource-id="{resource_id}" text="{text}" '
        f'bounds="{bounds}" enabled="{enabled}" clickable="{clickable}" '
        f'selected="{selected}" scrollable="{scrollable}">{inner}</node>'
    )


def xml_of(*parts):
    return "<hierarchy>" + "".join(parts) + "</hierarchy>"


class EvalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = json.loads((ROOT / "moca.json").read_text(encoding="utf-8"))

    def place(self, name):
        spec = self.app["places"][name]
        return spec.get("id") or spec.get("container")

    def test_number_75_does_not_match_175(self):
        self.assertFalse(mod.contains_number("175", 75))

    def test_number_75_does_not_match_750(self):
        self.assertFalse(mod.contains_number("750", 75))

    def test_number_75_matches_grade_line(self):
        self.assertTrue(mod.contains_number("B · 75", 75))

    def test_grade_of_b_dot_75(self):
        self.assertEqual(mod.grade_of("B · 75"), "B")

    def test_grade_of_s_plus(self):
        self.assertEqual(mod.grade_of("S+"), "S+")

    def test_grade_of_ab_is_not_b(self):
        self.assertNotEqual(mod.grade_of("AB"), "B")

    def test_find_node_by_id_and_bounds_center(self):
        xml = xml_of(
            node("ai.moca.app:id/gradeValue", "B", bounds="[10,30][50,70]"),
            node("ai.moca.app:id/gradeValue", "Z", bounds="[0,0][2,2]", package="other.app"),
        )
        found = mod.find_nodes(xml, "ai.moca.app:id/gradeValue", PKG)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["text"], "B")
        self.assertEqual(mod.bounds_center(found[0]["bounds"]), (30, 50))

    def test_scenarios_match_section_3_2(self):
        expected = {
            "D0": ("이상 없음", 100, 100, 100, 100, 100, 100, 85, "A", "S까지 5점"),
            "D1": ("엔진오일 부족", 60, 100, 100, 100, 100, 92, 80, "A", "S까지 10점"),
            "D2": ("타이어 공기압 노란 경고등, 저압 바퀴 2개", 100, 100, 60, 100, 100, 92, 80, "A", "S까지 10점"),
            "D3": ("ABS 빨간 경고등, 타이어 공기압 노란 경고등, 저압 바퀴 1개", 100, 50, 75, 100, 100, 85, 75, "B", "A까지 5점"),
            "D4": ("배터리 9%", 100, 100, 100, 90, 100, 98, 84, "A", "S까지 6점"),
            "D5": ("엔진오일 부족, 타이어 공기압 노란 경고등, 저압 바퀴 1개", 60, 100, 75, 100, 100, 87, 76, "B", "A까지 4점"),
            "D6": ("타이어 값을 지원하지 않음", 100, 100, None, 100, 100, 100, 85, "A", "S까지 5점"),
        }
        self.assertEqual(mod.CARE, 53)
        self.assertEqual(list(mod.SCENARIOS), ["D0", "D1", "D2", "D3", "D4", "D5", "D6"])
        for sid, row in expected.items():
            got = mod.SCENARIOS[sid]
            self.assertEqual(
                (got["차"], got["동력"], got["제동·조향"], got["타이어"], got["전기"], got["안전"], got["건강"], got["종합"], got["등급"], got["다음"]),
                row,
            )
        text = mod.answer_text()
        self.assertIn("관리 점수는 모든 시나리오에서 53 이다.", text)
        self.assertIn("D3\tABS 빨간 경고등, 타이어 공기압 노란 경고등, 저압 바퀴 1개\t100\t50\t75\t100\t100\t85\t75\tB\tA까지 5점", text)
        self.assertIn("D6\t타이어 값을 지원하지 않음\t100\t100\t없음\t100\t100\t100\t85\tA\tS까지 5점", text)
        self.assertIn("켜진 경고등 수: D2 1개, D3 2개, D5 1개, 나머지 0개. 빨간 경고등은 D3 에만 있다.", text)

    def test_battles_match_section_3_3(self):
        expected = {
            "D0": ("달리는곰", "A", 84, ["무", "무", "승", "무", "무"], (1, 0), "승리"),
            "D1": ("세차는사랑", "A", 83, ["패", "무", "무", "승", "무"], (1, 1), "패배 (종합 80 대 83)"),
            "D2": ("출근길요정", "S", 91, ["무", "무", "패", "무", "무"], (0, 1), "패배"),
            "D4": ("주말캠퍼", "S", 92, ["무", "무", "무", "패", "무"], (0, 1), "패배"),
            "D6": ("퇴근길질주", "B", 76, ["승", "무", "무", "무", "무"], (1, 0), "승리"),
            "D3": ("라이벌드라이버", "A", 88, ["무", "무", "패", "승", "승"], (2, 1), "승리"),
            "D5": ("경고등수집가", "C", 64, ["패", "승", "무", "무", "승"], (2, 1), "승리"),
        }
        for sid, row in expected.items():
            got = mod.BATTLES[sid]
            self.assertEqual(
                (got["상대"], got["등급"], got["상대점수"], got["라운드"], got["점수"], got["결과칸"]),
                row,
            )
        text = mod.answer_text()
        self.assertIn("D3\t라이벌드라이버\tA\t88\t무 무 패 승 승\t2 : 1\t승리", text)
        self.assertIn("D1\t세차는사랑\tA\t83\t패 무 무 승 무\t1 : 1\t패배 (종합 80 대 83)", text)
        self.assertIn("화면에 「데모 시나리오」라는 글자가 함께 나와야 한다.", text)
        self.assertIn("달리는곰 100·100·75·100·100", text)

    def _home(self, total, health, care, hint):
        return xml_of(
            node(self.place("홈.종합"), str(total)),
            node(self.place("홈.건강"), str(health)),
            node(self.place("홈.관리"), str(care)),
            node(self.place("홈.남은점수"), hint),
        )

    def test_home_2_passes_matching_screens(self):
        screens = {
            "D3": self._home(75, 85, 53, "A까지 5점"),
            "D1": self._home(80, 92, 53, "S까지 10점"),
        }
        self.assertTrue(mod.judge_home_2(screens, self.app).ok)

    def test_home_2_fails_when_one_number_differs(self):
        screens = {
            "D3": self._home(76, 85, 53, "A까지 5점"),
            "D1": self._home(80, 92, 53, "S까지 10점"),
        }
        self.assertFalse(mod.judge_home_2(screens, self.app).ok)

    def _inspect(self, grade, overall):
        spec = self.app["places"]["점검.결과"]
        if "ids" in spec:
            return xml_of(node(spec["ids"][0], grade), node(spec["ids"][1], str(overall)))
        return xml_of(node(spec["id"], f"{grade} · {overall}"))

    def test_inspect_3_passes_matching_screens(self):
        screens = {sid: self._inspect(mod.SCENARIOS[sid]["등급"], mod.SCENARIOS[sid]["종합"]) for sid in mod.SCENARIO_ORDER}
        self.assertTrue(mod.judge_inspect_3(screens, self.app).ok)

    def test_inspect_3_fails_when_one_number_differs(self):
        screens = {sid: self._inspect(mod.SCENARIOS[sid]["등급"], mod.SCENARIOS[sid]["종합"]) for sid in mod.SCENARIO_ORDER}
        screens["D3"] = self._inspect("B", 76)
        self.assertFalse(mod.judge_inspect_3(screens, self.app).ok)

    def _battle(self, rounds, result):
        rows = "".join(node("", verdict) for verdict in rounds)
        container = (
            f'<node package="{PKG}" resource-id="{self.place("대전.라운드")}" text="" '
            f'bounds="[0,0][10,10]">{rows}</node>'
        )
        return xml_of(container, node(self.place("대전.결과"), result))

    def _battle_screens(self):
        screens = {}
        for sid in mod.BATTLE_ORDER:
            row = mod.BATTLES[sid]
            result = f"{row['결과']} ({row['점수'][0]} : {row['점수'][1]})"
            if row["데모"]:
                result += " 데모 시나리오"
            screens[sid] = self._battle(row["라운드"], result)
        return screens

    def test_battle_2_passes_matching_screens(self):
        self.assertTrue(mod.judge_battle_2(self._battle_screens(), self.app).ok)

    def test_battle_2_fails_when_one_number_differs(self):
        screens = self._battle_screens()
        screens["D0"] = self._battle(mod.BATTLES["D0"]["라운드"], "승리 (2 : 0)")
        self.assertFalse(mod.judge_battle_2(screens, self.app).ok)

    def test_dump_retries_until_dumped_to(self):
        calls = []
        sleeps = []
        state = {"n": 0}

        def run(args, **_kwargs):
            calls.append(list(args))

            class Proc:
                stdout = ""
                stderr = ""
                returncode = 0

            proc = Proc()
            if "uiautomator" in args:
                state["n"] += 1
                proc.stdout = "" if state["n"] < 2 else "UI hierchary dumped to: /sdcard/u.xml"
            elif args[-2:] == ["cat", "/sdcard/u.xml"]:
                proc.stdout = "<hierarchy></hierarchy>"
            return proc

        xml = mod.Adb(run=run, sleep=lambda seconds: sleeps.append(seconds)).dump()
        self.assertEqual(xml, "<hierarchy></hierarchy>")
        self.assertEqual(sleeps, [2])
        self.assertIn(["adb", "shell", "rm", "-f", "/sdcard/u.xml"], calls)
        self.assertIn(["adb", "shell", "uiautomator", "dump", "/sdcard/u.xml"], calls)
        self.assertIn(["adb", "shell", "cat", "/sdcard/u.xml"], calls)

    def test_list_marks_items(self):
        lines = [line for line in mod.list_text().splitlines() if line]
        self.assertEqual(len(lines), 39)
        self.assertTrue(all(line.endswith(" 자동") or line.endswith(" 수동") for line in lines))
        self.assertIn("홈-1 자동", lines)
        self.assertIn("점검-1 수동", lines)
        self.assertIn("보이스-3 자동", lines)
        self.assertIn("공통-2 수동", lines)
        self.assertIn("이어-1 자동", lines)
        self.assertIn("이어-2 수동", lines)
        self.assertIn("이어-3 자동", lines)
        self.assertIn("순위-1 자동", lines)
        self.assertIn("순위-2 자동", lines)
        self.assertIn("화면-1 수동", lines)
        self.assertIn("화면-2 자동", lines)
        self.assertIn("화면-8 수동", lines)

    def test_json_scenario_buttons(self):
        self.assertEqual(self.app["package"], PKG)
        for index in range(7):
            ids = self.app["ops"]["조작-2"][f"D{index}"]
            self.assertIn(f"ai.moca.app:id/scenarioD{index}", ids)

    def test_dump_gives_up_after_five(self):
        def run(args, **_kwargs):
            class Proc:
                stdout = "ERROR"
                stderr = ""
                returncode = 1

            return Proc()

        xml = mod.Adb(run=run, sleep=lambda _seconds: None).dump()
        self.assertEqual(xml, "")

    def test_link_1_passes_when_keyword_and_another_text_exist(self):
        xml = xml_of(
            node("nav:id/query", "정비소", package="ai.navi"),
            node("nav:id/result", "강남점", package="ai.navi"),
        )
        self.assertTrue(mod.judge_link_1(xml, self.app, 1).ok)

    def test_link_1_fails_when_only_the_keyword_is_present(self):
        xml = xml_of(node("nav:id/query", "정비소", package="ai.navi"))
        self.assertFalse(mod.judge_link_1(xml, self.app, 1).ok)

    def test_link_1_fails_when_taps_exceed_two(self):
        xml = xml_of(
            node("nav:id/query", "정비소", package="ai.navi"),
            node("nav:id/result", "강남점", package="ai.navi"),
        )
        self.assertFalse(mod.judge_link_1(xml, self.app, 3).ok)

    def test_link_3_passes_when_action_node_is_absent(self):
        xml = xml_of(node(self.place("홈.등급"), "A"))
        self.assertTrue(mod.judge_link_3(xml, self.app).ok)

    def test_link_3_fails_when_action_node_is_present(self):
        xml = xml_of(node(self.app["places"]["해결.단추"]["id"], "가까운 정비소 안내"))
        self.assertFalse(mod.judge_link_3(xml, self.app).ok)

    def _rank_pair(self, home_line, place):
        home = xml_of(node(self.app["places"]["홈.순위"]["id"], home_line))
        row = node("", "", selected="true", inner=node("", str(place)))
        rank = xml_of(
            node(self.app["places"]["순위.내줄"]["container"], "", inner=row),
            node(self.app["places"]["순위.표기"]["id"], "연습 순위"),
        )
        return home, rank

    def test_rank_1_passes_measured_scenarios(self):
        pairs = {
            "D3": self._rank_pair("오늘 9위 · 13명 중 · 연습 순위", 9),
            "D0": self._rank_pair("오늘 5위 · 13명 중 · 연습 순위", 5),
        }
        self.assertTrue(mod.judge_rank_1(pairs, self.app).ok)

    def test_rank_1_fails_when_home_and_row_differ(self):
        pairs = {"D3": self._rank_pair("오늘 9위 · 13명 중 · 연습 순위", 8)}
        self.assertFalse(mod.judge_rank_1(pairs, self.app).ok)

    def test_rank_2_passes_when_practice_label_is_present(self):
        xml = xml_of(node(self.app["places"]["순위.표기"]["id"], "연습 순위"))
        self.assertTrue(mod.judge_rank_2(xml, self.app).ok)

    def test_rank_2_fails_when_practice_label_is_absent(self):
        xml = xml_of(node(self.app["places"]["순위.표기"]["id"], "실시간 순위"))
        self.assertFalse(mod.judge_rank_2(xml, self.app).ok)

    def test_screen_2_counts_clickable_nodes_except_tabs(self):
        tabs = "".join(node(tab, "탭", clickable="true") for tab in self.app["ops"]["아래탭"])
        rows = "".join(
            node(f"ai.moca.app:id/row{index}", "줄", clickable="true") for index in range(6)
        )
        self.assertTrue(mod.judge_screen_2(xml_of(tabs, rows), self.app).ok)
        extra = node("ai.moca.app:id/row6", "줄", clickable="true")
        self.assertFalse(mod.judge_screen_2(xml_of(tabs, rows, extra), self.app).ok)

    def test_screen_4_needs_three_text_nodes_in_the_lower_half(self):
        high = "".join(node(f"ai.moca.app:id/h{index}", "위", bounds="[0,0][10,10]") for index in range(3))
        low = "".join(
            node(f"ai.moca.app:id/l{index}", "아래", bounds="[0,40][10,50]") for index in range(3)
        )
        self.assertTrue(mod.judge_screen_4(xml_of(high, low), self.app).ok)
        self.assertFalse(mod.judge_screen_4(xml_of(high), self.app).ok)

    def test_screen_5_grade_is_taller_than_other_text(self):
        grade = node(self.app["places"]["화면.등급"]["id"], "B", bounds="[0,0][40,40]")
        other = node(self.place("홈.종합"), "종합 75", bounds="[0,40][40,50]")
        self.assertTrue(mod.judge_screen_5(xml_of(grade, other), self.app).ok)
        short = node(self.app["places"]["화면.등급"]["id"], "B", bounds="[0,0][40,8]")
        self.assertFalse(mod.judge_screen_5(xml_of(short, other), self.app).ok)

    def test_screen_6_fails_when_a_node_scrolls(self):
        plain = xml_of(node(self.place("홈.등급"), "B"))
        scrolling = xml_of(node(self.place("홈.등급"), "B", scrollable="true"))
        self.assertTrue(mod.judge_screen_6(plain, plain, self.app).ok)
        self.assertFalse(mod.judge_screen_6(scrolling, plain, self.app).ok)

    def test_screen_7_status_and_score_share_a_line(self):
        parts = []
        for status_id, score_id in self.app["places"]["화면.줄"][:5]:
            parts.append(node(status_id, "정상", bounds="[0,10][20,20]"))
            parts.append(node(score_id, "100", bounds="[30,10][50,20]"))
        self.assertTrue(mod.judge_screen_7(xml_of(*parts), self.app).ok)
        parts[1] = node(self.app["places"]["화면.줄"][0][1], "100", bounds="[30,40][50,50]")
        self.assertFalse(mod.judge_screen_7(xml_of(*parts), self.app).ok)


if __name__ == "__main__":
    unittest.main()
