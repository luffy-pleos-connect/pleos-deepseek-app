# G2 앱 보고서 — 화면 골격 + 실차 값 연결

- 시각: 2026-11-10 00:50
- 담당: 리드 (앱 서브에이전트가 20분간 산출물 0건이라 중단하고 리드가 직접 작성)
- 에뮬레이터: Pleos Connect v2.1.0 (API 34, 2560×1440 @160dpi), **headless + swiftshader**

## 결론

Pleos Connect 에뮬레이터에서 MOCA 앱이 **실제 Vehicle SDK 값으로 등급을 계산해 화면에 표시**한다.
`데모 데이터` 배지 없이 실제 프로필 화면이 동작하며, 데모 프로필은 별도 경로다.

## 실측 화면 (스크린샷: `docs/evidence/G2/screens/`)

| 화면 | 파일 | 확인한 내용 |
|---|---|---|
| 첫 실행 | `onboarding.png` | 동의 3개 + 닉네임 + [동의하고 시작]/[건너뛰기], DS 체크박스·텍스트필드·버튼 |
| 홈 | `home.png` | **등급 B · 종합 73 · 건강 84 · 관리 50 · "A까지 7점"**, 타일 4개, 하단 탭 5개 |
| 점검 결과 | `inspect.png` | **B · 73**, 카테고리 5개(동력 100 제동·조향 100 타이어 20 전기 100 안전 100), "A까지 7점" |
| 상태관리 | `status.png` | "타이어부터 보면 좋아요 (20점)", 카드 5개(정상 100 / 위험 20 / 정상 100 / 정상 100 / 정상 100) |

- 홈: `2560×1440` 원본 해상도, 가로 창(우측 패널).
- 점검·상태관리 수치는 **실차 HAL 값**이다. 타이어 20점은 HAL이 저압 바퀴 4개를 보고한 결과다(데모 아님).

## 돌린 명령

```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
cd moca/app && ./gradlew -q :app:assembleDebug
$HOME/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$HOME/Library/Android/sdk/platform-tools/adb shell am start -n ai.moca.app/.MainActivity
```

- APK: 18,307,598 bytes (debug)
- `./gradlew :app:assembleDebug` → 성공
- 설치·실행 → 성공, 크래시 0건

## DS 검수

```bash
python3 ../pleos-connect-design-system/.claude/skills/pleos-connect-ui-review/scripts/pleos_lint.py app
# → 0 finding(s) in 37 file(s). UX restriction check present: True
```

| 규칙 | 결과 |
|---|---|
| hex 색 | 0 (체크: `grep -rnE '#[0-9a-fA-F]{6}' app/src/main/res` → `pleos_colors.xml` 외 0) |
| 임의 sp/dp | 0 (글자는 전부 `Pleos.Text.*`, 치수는 `@dimen/*`) |
| 리플 | 0 |
| 무한 애니메이션 | 0 |
| 방향 고정 | 0 |
| 하드코딩 문자열 | 0 (전부 `strings.xml`) |
| 야간 강제 | 0 |
| 터치 64dp | 버튼 L/M/S = 100/80/64dp. 탭 버튼은 `@dimen/pleos_button_height_s`(64dp) |
| `distractionOptimized` | 선언됨 |
| CarUxRestrictions | `PleosUxRestrictions` 사용, 주행 중 상태관리·대전 탭 비활성 |
| `CONTROL_` 권한 | 0 |

## DS에 없어 조합한 컴포넌트

| 컴포넌트 | 구현 | 새 색 |
|---|---|---|
| 등급 카드 | `Widget.Pleos.Card` + `Pleos.Text.*` + 시맨틱 색 | 0 |
| 카테고리 카드 | `Widget.Pleos.Card`(스타일) + `Pleos.Text.Title.S`/`Body.M` + `pleos_informative_positive`·`pleos_regulation_yellow`·`pleos_regulation_red` | 0 |
| 기능 타일 | `Widget.Pleos.Card`(스타일) | 0 |

- 주의: DS 카드는 **`style="@style/Widget.Pleos.Card"`** 로 적용해야 한다. `android:background="@style/..."` 로 쓰면 `InflateException`(스타일을 drawable 로 해석)이 난다. 이번에 실제로 겪고 고쳤다.

## 구현 범위 (이번 단계)

| 기능 | 상태 |
|---|---|
| F-23 첫 실행(동의 3개·닉네임) | 구현. 동의 2·3 미체크 시 오프라인 경로는 미구현 |
| F-01/F-02 홈 | 등급·종합·건강·관리·다음 등급까지 구현. 마지막 점검 경과일은 미구현 |
| F-04 점검 | 구현. 카테고리 5개 진행 표시는 없음(즉시 읽기) |
| F-05 점검 결과 | 등급·카테고리 5·다음 등급 표시. 지난 점검 대비·감점 상위 3·상승 연출 미구현 |
| F-07 상태관리 카드 | 구현. 점수 링·상세 시트 미구현 |
| F-11 데모 프로필 | 시나리오 D0~D6 정의 + 설정 토글. 화면 배지는 상단 배지로 구현 |
| F-22 설정 | 닉네임·데모·동의·데이터 삭제 구현. 오픈소스 라이선스·앱 정보 미구현 |
| F-26 내 데이터 삭제 | 로컬 초기화 구현. **서버 DELETE 미연결** |
| F-03 경고 배지 | 리스너 연결. 색 정수 매핑 미확인 |
| F-12~F-15 대전 | 로비 골격만 |
| F-16~F-21 보이스 | 화면 골격만 (STT/TTS·/chat 미연결) |
| F-25 월 시즌 | 엔진에 구현, 앱 기록 저장(Room) 미구현 |

## 알려진 제약 (환경)

1. **창 있는 에뮬레이터는 이 맥에서 부팅 직후 QEMU 메인 루프가 멈춘다.** `-gpu swiftshader_indirect -no-audio -no-window`(headless)로만 동작한다. 시연 영상 촬영에는 별도 방법이 필요하다(사람 확인 필요).
2. headless 에뮬레이터에서 시스템 주행 화면(`ai.umos.drivingview`)이 GPU 크래시를 반복한다. 앱 자체 오류는 아니다.
3. 앱은 우측 패널(가로 창)에 뜬다. 좌측은 시스템 Driving view 다.
4. 오일·브레이크액은 에뮬레이터 VHAL에 속성이 없어 "측정 불가"로 나온다(G0 §4).

## 다음 단계

1. 보이스(F-16~F-21): Android STT/TTS + `/chat` 루프 + 도구 8개 + 오프라인 의도 6개
2. 대전(F-12~F-15): 시드 + 연습 상대 + 고정 상대
3. Room 기록 저장 → 관리 점수·시즌·추이
4. 데이터 삭제의 서버 DELETE 연결
5. 6창 × 주간·야간 스크린샷(LY-03)
