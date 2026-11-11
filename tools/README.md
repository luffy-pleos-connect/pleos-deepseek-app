# MOCA 기능별 평가 도구

기준표 39항목을 연결된 에뮬레이터에서 재고 기록지를 만든다. 에뮬레이터는 이미 켜져 있어야 한다. 이 도구는 에뮬레이터를 켜지 않는다.

띄울 때는 아래를 쓴다. `-gpu swiftshader_indirect` 로 띄우면 주행 화면 앱 `ai.umos.drivingview` 가 1~2초마다 죽고 다시 떠서, 터치가 앱에 전달되지 않고 화면 읽기가 멈춘다.

    emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -gpu host -no-audio -no-window

`pm clear`, `am force-stop`, `am start` 는 `am get-current-user` 로 읽은 사용자에게 한다. 이 판의 현재 사용자는 10 이고, 앱은 그 사용자에게만 깔려 있다. 사용자를 빼면 명령이 보이는 앱에 닿지 않는다.

첫 실행 화면이 보이면 `buttonStart`(동의하고 시작)를 눌러 넘긴다. 그 화면에서는 아래 탭이 없다. 노드를 누르기 전에는 그 노드가 나올 때까지 0.5초 간격으로 최대 10초를 기다린다. 데모 스위치와 시나리오를 바꾸면 화면이 다시 만들어지므로, 그 뒤의 노드도 같은 방식으로 기다린다. 시나리오 단추는 설정에서 `settingsGroupDemo` 를 누른 뒤, 데모 스위치가 켜져 있을 때만 보인다. 항목을 시작하기 전에 앱을 다시 앞으로 가져온다. 정비소 검색처럼 다른 앱이 앞에 남아 있으면 다음 항목이 탭을 찾지 못하기 때문이다. 글자 질문은 입력 뒤에 키보드를 닫고 보내기 단추를 누른다.

    python3 tools/moca_eval.py --list
    python3 tools/moca_eval.py --answers
    python3 tools/moca_eval.py --app tools/moca.json --out 기록지.md

`--list` 는 번호와 `자동` 또는 `수동` 이다. `--answers` 는 기준표 3-2·3-3 정답이다. 수동 항목은 결과 칸이 비고, 덧붙임에 까닭이 적힌다.

다른 앱은 JSON 을 복사해 채운다. `package` 는 패키지 이름이다. `ops` 의 조작-1~7 은 누를 노드의 resource-id 를 순서대로 적는다. 조작-2 는 설정 화면의 scenarioD0~scenarioD7 이다. `places` 는 볼 곳 이름마다 `id` 를 적고, id 가 없는 곳은 `container` 와 `rule` 로 가려낸다.

판정은 화면 XML 만 보는 함수다. `python3 -m unittest tools/test_moca_eval.py` 는 에뮬레이터 없이 돈다.
