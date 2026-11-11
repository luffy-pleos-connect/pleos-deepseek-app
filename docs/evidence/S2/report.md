# 시험 S2: 부팅 때 백그라운드 점검

호스트 시각 [실측] `date` = Wed Nov 11 19:04:09 KST 2026.
에뮬레이터 시각 [실측] `adb shell date` = Wed Nov 11 02:04:08 PST 2026. logcat 시각은 에뮬레이터 시계다.
프로세스 [실측] `pgrep -lf qemu-system-aarch64` = `qemu-system-aarch64-headless -avd Pleos_Connect_v2.1.0 -no-snapshot-load -gpu swiftshader_indirect -no-audio -no-window`.

시험 앱은 저장소 밖 `<작업 폴더>/.spike/s2`. 패키지 `ai.pleos.spike.s2`, 사용자 10, appId=10176. 로그 파일 `logcat-s2boot.txt`.

## 결론

(가) 설치만 하고 한 번도 실행하지 않았으면 안 된다. 액티비티를 한 번 연 뒤에는 된다.

사용자 10에서 `stopped=true notLaunched=true` 인 채로 `adb reboot` 한 첫 시험은, 시스템이 `Posting BOOT_COMPLETED user #10` 을 남겼는데 `S2Boot` 로그는 0줄이었다. 그 뒤 `am start` 로 `MainActivity` 를 한 번 열어 `stopped=false notLaunched=false` 가 된 다음 다시 `adb reboot` 하자 수신기가 `BOOT_COMPLETED` 를 받았고 `uid=1010176` 이었다.

(나) 된다.

두 번째 부팅에서 액티비티 로그는 없고, 수신기가 띄운 서비스가 `vehicle.initialize` 다음에 `getEvBatteryStateOfCharge` 를 호출해 `soc=100` 을 받았다. `dumpsys activity activities` 에 `ai.pleos.spike.s2` 액티비티는 없었다.

(다) 된다. 측정한 구간은 등록 후 20초다.

같은 서비스가 `registerTelltales returned activity=none` 을 남기고, `onTelltaleInfoUpdated count=0` 을 받은 뒤 `still alive 20s after registerTelltales activity=none` 까지 살아 있었다. `dumpsys activity services` 는 `createdFromFg=false`, `isForeground=true`(포그라운드 서비스), `code:BOOT_COMPLETED` 였다. 화면의 resumed 액티비티는 주행 화면, 내비게이션, 홈이었다.

## 돌린 명령

에뮬레이터는 위 `pgrep` 줄의 플래그로 이미 떠 있었다. 준비 확인은 앞선 측정에서 `adb shell getprop sys.boot_completed` = `1`. `adb shell monkey` 는 쓰지 않았다.

```
adb install -r --user 10 <작업 폴더>/.spike/s2/app/build/outputs/apk/debug/app-debug.apk
```

[실측] `Success`.

첫 재부팅, 실행 전:

```
adb reboot
adb shell getprop sys.boot_completed
adb shell cat /proc/uptime
adb logcat -d -s S2Boot:V
adb shell dumpsys package ai.pleos.spike.s2
```

[실측] `adb reboot` 종료 코드 0, 표준출력 비어 있음. 첫 폴의 `boot_completed` 는 `1`, 그때 `/proc/uptime` 첫 칸 `19.08`. 25초 뒤 uptime `55.14`. `S2Boot` 로그 없음. User 10 `stopped=true notLaunched=true`.

한 번 실행:

```
adb shell am start --user 10 -n ai.pleos.spike.s2/.MainActivity
```

[실측] `Starting: Intent { cmp=ai.pleos.spike.s2/.MainActivity }`, 종료 코드 0. 로그 `activity onCreate`. 그 뒤 User 10 `stopped=false notLaunched=false`.

두 번째 재부팅:

```
adb shell input keyevent 3
adb reboot
```

[실측] 둘 다 종료 코드 0. 재부팅 전 uptime `226.71`. 폴링 `try=2 uptime=3`, `try=4 uptime=13 boot=1`. 그 뒤 45초 대기, uptime `58.93`.

```
adb logcat -d -s S2Boot:V
adb shell dumpsys package ai.pleos.spike.s2
adb shell dumpsys activity activities
adb shell dumpsys activity services ai.pleos.spike.s2
```

## 로그

첫 부팅 버퍼 `/tmp/s2-full-logcat.txt` 17755행 [실측]:

```
11-11 01:57:42.739 I/ActivityManager(  764): Posting BOOT_COMPLETED user #10
```

그 파일에서 `S2Boot` 검색은 이번 확인에서 일치 없음. `logcat -d -s S2Boot:V` 도 빈 출력이었다. 설치 직후와 그 재부팅 뒤 dumpsys [실측]:

```
User 10: ceDataInode=286763 installed=true hidden=false suspended=false distractionFlags=0 stopped=true notLaunched=true enabled=0 instant=false virtual=false
```

권한 [실측] `RECEIVE_BOOT_COMPLETED: granted=true`, `FOREGROUND_SERVICE: granted=true`, `FOREGROUND_SERVICE_DATA_SYNC: granted=true`, `CAR_ENERGY: granted=true`, `CAR_SAFETY: granted=true`, `PRIVATE_CAR_SAFETY: granted=true`, `VEHICLE_BINDING: granted=true`. `POST_NOTIFICATIONS: granted=false`. 수신기 테이블에 `BOOT_COMPLETED` → `ai.pleos.spike.s2/.BootReceiver`.

실행 직후 [실측]:

```
11-11 02:00:34.689 32388 32388 I S2Boot  : activity onCreate
```

User 10 `stopped=false notLaunched=false`.

두 번째 부팅 `logcat -d -s S2Boot:V` [실측]:

```
11-11 02:01:48.735  5798  5798 I S2Boot  : onReceive action=android.intent.action.BOOT_COMPLETED uid=1010176
11-11 02:01:48.737  5798  5798 I S2Boot  : startForegroundService returned
11-11 02:01:48.739  5798  5798 I S2Boot  : onStartCommand uid=1010176
11-11 02:01:48.814  5798  5868 I S2Boot  : vehicle.initialize returned
11-11 02:01:50.330  5798  5868 I S2Boot  : getEvBatteryStateOfCharge called
11-11 02:01:50.350  5798  5826 I S2Boot  : soc=100
11-11 02:01:50.906  5798  5868 I S2Boot  : registerTelltales returned activity=none
11-11 02:01:51.022  5798  5826 I S2Boot  : onTelltaleInfoUpdated count=0
11-11 02:02:10.907  5798  5868 I S2Boot  : still alive 20s after registerTelltales activity=none
```

서비스 dumpsys 에서 출력된 줄 [실측]: `ServiceRecord{90d3b3e u10 ai.pleos.spike.s2/.BootService}`, `ProcessRecord{45b2894 5798:ai.pleos.spike.s2/u10a176}`, `allowStartForeground=BOOT_COMPLETED`, `startForegroundCount=1`, `code:BOOT_COMPLETED`, `isForeground=true foregroundId=1 types=00000001`, `createdFromFg=false`. `infoAllowStartForeground` 의 `tempAllowListReason` 값은 수집 때 240자에서 잘렸다. 잘리기 전까지는 `<7415c53 android.intent.action.B` 이다.

액티비티 dumpsys 에서 `spike.s2` 줄은 없었다. resumed 로 나온 것 [실측]:

```
topResumedActivity=ActivityRecord{53b2f8c u10 ai.umos.drivingview/.DrivingViewActivity t1004811}
topResumedActivity=ActivityRecord{22720a0 u10 ai.umos.maps.android.navigation.app/.MainActivity t1004798}
topResumedActivity=ActivityRecord{c741d4a u10 ai.umos.homescreen/.HomeActivity t1004795}
```

멈춘 앱이 브로드캐스트를 못 받는 쪽 [문서](https://developer.android.com/about/versions/android-3.1.html) 문장: "Note that the system adds FLAG_EXCLUDE_STOPPED_PACKAGES to all broadcast intents." 같은 절: "Applications are in a stopped state when they are first installed but are not yet launched and when they are manually stopped by the user (in Manage Applications)." 이번 첫 시험은 설치 직후 미실행이고, 강제 종료 경로는 돌리지 않았다.

## 앱에 넣으려면

이번 과제에서 `app/` 은 고치지 않았다. 에뮬레이터에서 된 경로는 아래다.

수신기는 `BOOT_COMPLETED` 만 받고 `startForegroundService` 로 `BootService` 를 띄운다. 서비스는 `onStartCommand` 에서 바로 `startForeground` 한 다음 `Vehicle(applicationContext).initialize()`, `getEvBattery().getEvBatteryStateOfCharge`, `getSafety().registerTelltales` 를 호출한다. 매니페스트 권한은 `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `CAR_ENERGY`, `CAR_SAFETY`, `PRIVATE_CAR_SAFETY` 이고, Vehicle AAR 이 `VEHICLE_BINDING` 을 추가했다. 서비스 `foregroundServiceType` 은 `dataSync` 였다. targetSdk 는 34.

사용자 10에 설치한 직후처럼 `stopped=true` 이면 수신기가 불리지 않았다. 앱을 한 번 열어 그 플래그가 내려간 뒤에야 부팅 수신이 됐다. 백그라운드 시작 제한에 걸리지는 않았다. `startForegroundService` 가 예외 없이 돌아왔고, dumpsys 이유가 `BOOT_COMPLETED` 였다.

`POST_NOTIFICATIONS` 는 설치 시점 `granted=false` 였고, 그 상태에서 두 번째 부팅의 `startForeground` 는 성공했다(`isForeground=true`). 다른 기기에서 알림 권한이 필요한지는 이번 측정 밖이다.

## 확인하지 못한 것

[미확인] 등록 후 20초보다 긴 유지, 프로세스 종료 뒤의 재등록, 화면이 꺼진 뒤의 유지.

[미확인] 경고등 값이 바뀐 콜백. 이번 콜백은 `count=0` 한 번이다. `adb root` 와 `cmd car_service inject-vhal-event` 는 이번 시험에서 실행하지 않았다.

[미확인] 설정에서 강제 종료한 뒤의 부팅. 문서의 stopped 조건과 설치 직후 미실행은 맞춰 봤고, 강제 종료 자체는 재부팅하지 않았다.

[미확인] 에뮬레이터가 아닌 기기. SOC 가 100 이 아닌 값도 보지 않았다.
