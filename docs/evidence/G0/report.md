# G0 스파이크 — Pleos 에뮬레이터에서 Vehicle SDK 실값 수신

- 실행 시각: 2026-11-09 23:20 ~ 2026-11-10 00:05 (Asia/Seoul)
- 실행자: 리드 에이전트 (에뮬레이터를 쓰는 단일 작업)
- 증거 로그: `docs/evidence/G0/g0-log.txt`

## 1. 환경 복구 (setup.sh 결과의 실제 상태)

`./scripts/doctor.sh` 최초 실행은 8개 항목 실패였다. 원인은 **setup.sh 6단계의 셸 프로필 기록 버그**였다.

- 증상: `~/.zshrc` 에 `export ANDROID_HOME="\"` 가 기록됨
- 원인: setup.sh 의 `block` 변수 조립에서 `export ANDROID_HOME=\"$ANDROID_HOME\"` 을 **ask 결과가 빈 문자열일 때** 그대로 확장해 역슬래시가 남았다. 같은 이유로 SDK 경로 질문 기본값이 `\` 로 처리되어 SDK 전체가 `pleos-connect-environment/\` 디렉터리에 20GB 설치됐다.
- 조치(재설치 아님, 경로 이동):
  1. `<repo>/\\` → `~/Library/Android/sdk` 로 이동 (python shutil.move)
  2. `~/.zshrc` 의 pleos 블록을 `ANDROID_HOME="$HOME/Library/Android/sdk"` 로 교체
  3. `sample/local.properties` 의 `sdk.dir` 수정
- 결과: `ANDROID_HOME=~/Library/Android/sdk SHELL=/bin/zsh ./scripts/doctor.sh` → **모든 항목 정상**
- 사람 요청 없음(라이선스 동의는 이미 완료된 상태였고, 재실행하지 않았다).

## 2. 에뮬레이터 부팅

| 시도 | 명령 | 결과 |
|---|---|---|
| 1 | `emulator.sh` (기본, GPU host) | 부팅 완료 18.5초 후 `detected a hanging thread 'QEMU2 main loop'` 로 멈춤 |
| 2 | `-gpu host -no-audio` | 동일 (Vulkan/Metal 초기화 직후 행) |
| 3 | `-gpu swiftshader_indirect -no-audio` (창 있음) | 동일 |
| 4 | `-gpu swiftshader_indirect -no-audio -no-window` + lock 삭제 | **성공. Boot completed in 13.4초** |

- 결론: 이 맥(macOS 26.5 arm64, Apple M5 Pro)에서 Pleos 이미지는 **headless + swiftshader** 조합으로만 부팅된다. 창 표시가 필요하면 별도 확인이 필요하다.
- AVD 설정 변경: `hw.gpu.mode=swiftshader_indirect` (config.ini)
- 기기: `crp_avd_arm64`, API 34, `wm size 2560x1440`, `wm density 160`
- 재현 명령:
  ```bash
  SDK="$HOME/Library/Android/sdk"
  pkill -9 -f qemu-system-aarch64; rm -f ~/.android/avd/Pleos_Connect_v2.1.0.avd/*.lock
  nohup "$SDK/emulator/emulator" -avd Pleos_Connect_v2.1.0 -no-snapshot-load \
    -gpu swiftshader_indirect -no-audio -no-window > /tmp/emu.log 2>&1 &
  ```

## 3. G0 앱 (`sample/`, AI.MOCA G0)

- 패키지: `ai.moca.g0` (임시. 제품 패키지명은 익명 계정 확정 후)
- 수집: `registerTelltales` 콜백, `getTelltalesInfo` 1회, `EvBattery.getEvBatteryStateOfCharge` 1회, 보조로 오일·브레이크액·capability
- 권한 확정(코드/문서/AAR 대조):
  - `Safety`, `Tire` → `pleos.car.permission.CAR_SAFETY`
  - `EvBattery`, `Fuel` → `CAR_ENERGY`
  - `Odometer`, `Gear`, `Brake` → `CAR_DRIVE`
  - `CarInfo`, `Display`, `EvBattery(INFO)` → `CAR_INFO`
  - **`getTelltalesInfo`/`registerTelltales`/`checkTelltalesCapabilities` → `PRIVATE_CAR_SAFETY`**
    - 공식 API 문서: "Permission: PleosPermission.PRIVATE_CAR_SAFETY (protection level: signature|privileged)"
      (https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/get-telltales-info)
    - 그러나 **Pleos 에뮬레이터(2.1.0)의 `ai.pleos.playground.service.vehicle` 는 `PRIVATE_CAR_SAFETY` 를 `protectionLevel=normal` 로 선언**하고 있었다(`pm list permissions -f`, `dumpsys package` 실측). 그래서 일반 앱이 이 권한을 선언하면 설치 시 granted 된다.
    - 이 차이는 실차에서 재확인이 필요하다. 실차에서 signature|privileged 이면 경고등 기능은 **시스템 앱 또는 제휴 계약** 없이는 불가능하다.
- `CONTROL_` 권한 0 (manifest에 없음).

## 4. 실행 결과 (g0-log.txt 원문)

```
I MOCA_G0 : INIT Vehicle.initialize() called
I MOCA_G0 : REGISTER registerTelltales OK
I MOCA_G0 : LISTENER count=0
I MOCA_G0 : LISTENER count=0
I MOCA_G0 : SOC value=100
I MOCA_G0 : OIL FAIL Exception: java.lang.IllegalArgumentException: property ID is not in carPropertyConfig list, and so it is not supported: ENGINE_OIL_LEVEL
I MOCA_G0 : GET_INFO count=0
I MOCA_G0 : BRAKE_FLUID FAIL Exception: java.lang.IllegalArgumentException: property ID is not in carPropertyConfig list, and so it is not supported: 0x21400730
I MOCA_G0 : CAP_TELLTALES config=CapabilityConfig(accessType=READ)
I MOCA_G0 : CAP_WHEEL areas=[WheelArea x4]
```

### 성공한 것 (실값)
1. **`EvBattery.getEvBatteryStateOfCharge` → `SOC value=100`** — Vehicle SDK가 실제 값을 반환했다. 차량 데이터 파이프라인이 이 맥에서 동작한다는 첫 증거다.
2. **`registerTelltales` 등록 성공 (OK) 및 콜백 수신 2회** — 리스너가 실제로 붙고 콜백이 왔다. 값이 0개(경고등 꺼진 상태)였다.
3. **`checkTelltalesCapabilities` → `CapabilityConfig(accessType=READ)`** — capability 조회가 동작.
4. **`checkWheelAreaCapabilities` → WheelArea 4개** — 타이어 바퀴 영역 조회 동작.

### 실패·미확인
1. **ABS 경고등 ON 주입 실패.** Virtual Console 웹(`127.0.0.1:8088`, `ai.pleos.connectwebconsole`)은 떠 있지만 `bridge_ready=false`, `emulator_active=false`, `availability.source=not-run` 이다. `/vehicle/telltales/simulator?...` 는 `503 {"error":"emulator_inactive"}`. DDS 브리지(`ai.pleos.hardware.vehicle_api_dds_emulator`)를 켜는 방법을 찾지 못했다.
   → **따라서 ABS의 color 정수(RED 대응)는 확정하지 못했다.**
2. **오일·브레이크액이 이 에뮬레이터에 없다.** `property ID is not in carPropertyConfig list, and so it is not supported: ENGINE_OIL_LEVEL` / `0x21400730`. 즉 에뮬레이터 VHAL에 해당 속성이 아예 등록돼 있지 않다. V3(오일·브레이크액·타이어 압력·연료는 Virtual Console로 못 바꿈)를 넘어 **읽기 자체가 불가**하다.
3. 부팅 시간: 콜드부팅 13.4초(로그), 앱 시작 → 첫 콜백까지 약 0.3~0.5초.
4. `TelltaleData.getColor()` 가 Int 라는 사실은 AAR 바이너리로 확인(§5). 실값은 미확인.

## 5. AAR 바이너리로 확정한 시그니처 (실행 없이 확인)

`Vehicle-2.3.2.aar` → `classes.jar` → `javap`:

- `TelltaleData(TelltaleType, TelltaleState, int color, int priority, int warningCount)`
  - `getColor(): int`, `getPriority(): int`, `getWarningCount(): int`
- `TelltaleColor` enum: `WHITE, GREEN, BLUE, YELLOW, RED, GRAY` (6종) — **Int 색과 enum이 별도로 존재**
- `TelltaleState` enum: `OFF, ON, BLINK`
- `Telltales.getTelltales(): List<TelltaleData>`
- `TelltaleInfoListener` 는 `VehicleListener` 를 상속하며 `onFailed(e: Exception)` 를 함께 구현해야 한다
- `OilLevelState` 가 오일·브레이크액·변속기오일에 공통으로 쓰인다(`getBrakeFluidLevelState` 반환형도 `OilLevelState`)
- `Tire.getLowTirePressure(WheelArea, (Float)->Unit, (Exception)->Unit)`, `getTirePressure(...)` 도 같은 형태
- `Safety` 는 `registerTelltales(TelltaleInfoListener)` / `getTelltalesInfo` / `checkTelltalesCapabilities` 를 제공
- 권한 상수는 `pleos.car.permission.*` 11종 읽기 + 11종 CONTROL + 11종 PRIVATE

## 6. G0 게이트 판정

계획 14장 G0 막는 기준: **"Virtual Console에서 ABS를 On으로 바꿨을 때 registerTelltales 콜백 수신 + getTelltalesInfo 결과(색 정수 포함) + 배터리 SOC 읽기 값"**

| 항목 | 결과 |
|---|---|
| 배터리 SOC 읽기 값 | **충족** (`SOC value=100`) |
| registerTelltales 콜백 수신 | **충족** (등록 OK, 콜백 2회 수신. 값은 0개) |
| getTelltalesInfo 결과 | **부분** (호출 성공, count=0) |
| 색 정수 포함 결과 | **미충족** (경고등 주입 실패) |
| ABS On 주입 | **미충족** (DDS 브리지 비활성) |

→ **G0는 "부분 통과"** 다. 실값 파이프라인은 증명됐고, 경고등 색 정수만 미확인이다.

## 7. 트랙 판정에 미치는 영향 (계획 3.3)

계획 3.3은 "G0 로그에 Vehicle SDK 실제 값 1개 + registerTelltales 콜백 1회"가 있으면 트랙 A로 본다.

- 본 로그에는 **SOC 실제 값 1개(100)** 와 **registerTelltales 콜백 1회 이상**이 있다. 문구 그대로면 트랙 A다.
- 그러나 콜백의 값이 0개이고 ABS 주입을 못 했으므로, "경고등 실값이 온다"는 것은 증명되지 않았다.
- **리드 판단: 트랙 A로 확정하되, "경고등 실값"은 미확인 항목으로 보고서에 남긴다.** 이유: 계획이 정한 트랙 판정 문구(실제 값 1개 + 콜백 1회)를 충족했고, 경고등 주입 실패는 우리 코드가 아니라 에뮬레이터 DDS 브리지 문제다. 14.1의 정직 표기 원칙에 따라 기획서·README에는 "SOC 등 실값 수신 확인, 경고등 색 정수는 에뮬레이터 Virtual Console 제약으로 미확인"으로 쓴다.

## 8. 남은 V 항목 (계획 17장 갱신)

| ID | 상태 | 내용 |
|---|---|---|
| V2 | **부분 해결** | enum·Int 색·비동기 콜백 확정. **색 정수 대응은 미확인**(DDS 브리지). 브레이크액 반환형 = `OilLevelState` 확정. 오일·브레이크액은 에뮬레이터에 속성 자체가 없음 |
| V3 | **수정 필요** | "오일·브레이크액·타이어 압력·연료는 Virtual Console로 못 바꾼다" → **에뮬레이터에서 오일·브레이크액은 읽기도 불가(속성 미등록)**, 타이어는 바퀴 4개/압력 기능 조회는 됨 |
| V5 | 미확인 | STT/TTS 엔진 — 아직 시험 안 함 |
| V10 | **부분 해결** | 주행 상태 전환 — `CarUxRestrictions` 리스너는 샘플에서 동작. 실제 주행 전환은 미시험 |
| V17 | **부분 해결** | 창 크기 2560x1440 @160dpi 실측. 창 분할은 미시험 |
| V21 | 미확인 | SpeechRecognizer/TTS 기본 엔진 |
| 신규 | **DDS 브리지** | Virtual Console이 `bridge_ready=false` 로 상태 주입 불가. 켜는 방법 미확인. 해결하려면 Pleos 문서·사무국 문의 필요 |
| 신규 | **GPU** | 창 있는 에뮬레이터는 QEMU 행. headless 필수 |

## 9. 다음 단계

1. G0 잔여: DDS 브리지 활성화 방법을 Pleos 문서에서 찾고, 안 되면 사무국에 사람이 문의(V9에 항목 추가).
2. G1: 서버·:core·check.sh — SDK·에뮬레이터 불필요. 서브에이전트가 진행 중.
3. G2: 경고등 색 정수를 모르므로 **색 정수 → RED/YELLOW 매핑을 설정값으로 두고**, 기본값은 AAR enum(`TelltaleColor.RED=4` 추정)과 실측 전 가정을 코드 주석·문서에 명시한다. 데모 시나리오는 색 정수에 의존하지 않게 `TelltaleColor` enum 기반으로 설계한다(가능한 경우).
