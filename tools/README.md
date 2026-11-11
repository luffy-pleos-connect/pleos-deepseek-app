# MOCA 기능별 평가 도구

기준표 26항목을 연결된 에뮬레이터에서 재고 기록지를 만든다. 에뮬레이터는 이미 켜져 있어야 한다. 이 도구는 에뮬레이터를 켜지 않는다.

    python3 tools/moca_eval.py --list
    python3 tools/moca_eval.py --answers
    python3 tools/moca_eval.py --app tools/moca.json --out 기록지.md

`--list` 는 번호와 `자동` 또는 `수동` 이다. `--answers` 는 기준표 3-2·3-3 정답이다. 수동 항목은 결과 칸이 비고, 덧붙임에 까닭이 적힌다.

다른 앱은 JSON 을 복사해 채운다. `package` 는 패키지 이름이다. `ops` 의 조작-1~7 은 누를 노드의 resource-id 를 순서대로 적는다. 조작-2 는 설정 화면의 scenarioD0~scenarioD6 이다. `places` 는 볼 곳 이름마다 `id` 를 적고, id 가 없는 곳은 `container` 와 `rule` 로 가려낸다.

판정은 화면 XML 만 보는 함수다. `python3 -m unittest tools/test_moca_eval.py` 는 에뮬레이터 없이 돈다.
