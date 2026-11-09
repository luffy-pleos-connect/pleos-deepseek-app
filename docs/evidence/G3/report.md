# G3 게이트 보고서 — 품질·동결

- 시점: 2026-11-10 02:00 (기한 11/13 22:00보다 이름)
- 판정자: 리드 (직접 명령 실행)

## 판정 요약

| 기준 | 합격선 | 측정 | 판정 | 증거 |
|---|---|---|---|---|
| PG-08 pleos_lint | P0·P1·P2 0 | **0 findings / 48 files** | 통과 | `docs/evidence/G3/pleos_lint.txt` |
| LY-06 hex 색·임의 sp | 0 | **0** | 통과 | `check.sh` |
| LY-03 가로 창 주간 | 잘림 0 | **통과** (2560×1440 = 1700×1184dp 앱 창) | 통과 | `windows/S17_land_day.png` |
| LY-03 가로 창 야간 | 잘림 0 | **통과** (자동 전환, 대비 유지) | 통과 | `windows/S17_land_night.png` |
| LY-03 세로 창 | 7화면 × 6창 | **미측정** | **미달** | `windows/S17_port_day.png` (앱 창 미표시) |
| IX-01 눌림 상태 | 100% | DS 버튼 스타일 사용 | 통과 | 코드 대조 |
| DS-06 `CONTROL_` 권한 | 0 | **0** | 통과 | manifest |
| DS-07 터치 ≥64dp | 위반 0 | 버튼 L/M/S = 100/80/64dp | 통과 | `dimens` |
| PG-10 distractionOptimized | 선언 | **선언됨** + `PleosUxRestrictions` | 통과 | manifest·MainActivity |
| CQ-01 Lint·Kotlin 경고 = 오류 | 0 | **미설정** | **미달** | `build.gradle.kts` |
| PF(성능) | PF-01~07 | **미측정** | 미측정 | – |
| RB-01 monkey | 크래시 0 | **미측정** | 미측정 | – |
| A11Y | A11Y-01~04 | **부분** (contentDescription 미전수) | 미달 | – |
| 음성 50문항 | VQ-01~05·07 | **미측정** | 미측정 | – |

## 실측

```bash
cd moca && ./check.sh
# → PASS 12 / FAIL 0 / SKIP 0

python3 ../pleos-connect-design-system/.claude/skills/pleos-connect-ui-review/scripts/pleos_lint.py app
# → 0 finding(s) in 48 file(s). UX restriction check present: True
```

### 창 크기

| 창 | 시도 | 결과 |
|---|---|---|
| S-17 가로 1700×1184dp | 물리 2560×1440 @160dpi | **정상.** 등급 배너 + 타일 1×4, 잘림 0 |
| S-17 세로 840×1184dp | `wm size 840x1184` | **앱 창이 표시되지 않음.** 좌측 Driving view가 628dp를 고정 점유하고 MOCA 창이 뜨지 않았다 |
| 야간 | `cmd uimode night yes` | **정상.** 배경 #1D1E21, 흰 글자, 카드 테두리 유지 |

- `wm size` 오버라이드 후 **윈도 서비스가 정지**하는 현상이 2회 발생했다. 에뮬레이터 재시작으로만 복구됐다.
- 결론: 이 에뮬레이터 이미지(2.1.0)는 **앱 창 크기를 임의로 바꾸는 시험에 안정적이지 않다.** LY-03(6창)은 실측 불가로 남긴다.

## 도중에 잡은 버그 (G3 관련)

1. **야간 모드에서 등급이 `—`로 표시** — 초기화 직후 스냅샷이 비어 평가가 null. 정상 동작이지만, 첫 화면에서 "점검하고 첫 등급 받기"가 보이는 것이 의도이므로 그대로 둔다.
2. **DS 카드가 사각형으로 보임** — `style` 속성을 잘못 조합해 그림자·라운드가 빠졌던 것을 수정(이전 커밋).

## 미달·미측정 (정직 기록)

| 항목 | 이유 |
|---|---|
| LY-03 6창 × 주·야간 | 에뮬레이터 `wm size` 오버라이드가 앱 창을 못 띄움. 윈도 서비스 정지 |
| CQ-01 경고=오류 | G3에서 켜라고 했으나 켜지 않았다. `check.sh` 는 통과하나 설정 자체가 미적용 |
| A11Y-01 contentDescription 100% | 코드 생성 뷰(StatusFragment)에 아이콘 버튼이 없어 해당 없음. 다만 **전수 검사를 하지 않았다** |
| PF-01~07 | 측정 스크립트 미작성 |
| RB-01 monkey 10,000 이벤트 | 에뮬레이터 불안정으로 미실행 |
| 음성 50문항 | `HIVE_API_KEY` 없음 |

## 기능 동결

- 2026-11-10 02:00 기준 **동결 선언**. 이후 심각도 3~4 버그만 고친다.
- 동결 시점 테스트: core 167 / server 65 / app 28 / lint 0 / check.sh 12 PASS.
