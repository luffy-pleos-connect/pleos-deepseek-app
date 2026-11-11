# 시험 S3: 내비게이션 검색 화면 SearchPlace("정비소")

호스트 시각 [실측] `date` = Wed Nov 11 21:07:41 KST 2026.
에뮬레이터 시각 [실측] `adb shell date` = Wed Nov 11 04:07:41 PST 2026. logcat 시각은 에뮬레이터 시계다.

## 결론

된다.

Pleos 에뮬레이터 v2.1.0, 사용자 10에서 시험 앱이 `NaviHelper.requestNavigationScreen(NavigationScreenRequest.SearchPlace("정비소"))` 를 한 번 호출했다. 호출은 예외 없이 돌아왔고, 내비게이션 앱 로그에 `SearchPlace(keyword=정비소)` 와 `place search success` 가 남았다. 시험 앱이 앞에 있는 동안의 화면 덤프에는 검색 목록이 없었다. 내비게이션 태스크를 앞으로 가져온 뒤의 화면 덤프에는 검색어 `정비소` 와 결과 `삼성모터스 판교점` 이 있었다.

리스너 콜백(`onRouteStarted`, `onError`, `onNavigationStatus`)은 이 구간의 `S3Navi` 로그에 없다.

## 돌린 명령

에뮬레이터 [실측] 시험 동안 백그라운드. 준비 확인 `adb shell getprop sys.boot_completed` = `1`. `adb shell am get-current-user` = `10`.

```
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -gpu swiftshader_indirect -no-audio -no-window
```

시험 앱: `<작업 폴더>/.spike/s1`

```
cd <작업 폴더>/.spike/s1 && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleDebug
```

[실측] 첫 빌드 `BUILD SUCCESSFUL` (34 up-to-date). `moveTaskToBack` 를 넣은 뒤 다시 빌드, [실측] `BUILD SUCCESSFUL` (4 executed, 30 up-to-date).

```
adb install -r --user 10 <작업 폴더>/.spike/s1/app/build/outputs/apk/debug/app-debug.apk
```

[실측] `Success`. 두 번 설치했다.

```
adb logcat -c
adb shell am start --user 10 -n ai.pleos.spike.s1/.MainActivity
```

[실측] `Starting: Intent { cmp=ai.pleos.spike.s1/.MainActivity }`.

첫 실행 18초 뒤:

```
adb shell uiautomator dump /sdcard/u.xml
adb shell cat /sdcard/u.xml
adb logcat -d -s S3Navi:V
adb shell dumpsys window
```

둘째 실행은 호출 직후 `moveTaskToBack(true)` 를 했다. `sleep 6` 뒤 `dumpsys window` 의 포커스는 여전히 시험 앱이었다(`window-focus-after-back.txt`). 그 다음:

```
adb shell am start --user 10 -n ai.umos.maps.android.navigation.app/.MainActivity
```

[실측] `Warning: Activity not started, its current task has been brought to the front`. 3초 뒤 `uiautomator dump`.

`adb shell monkey` 는 쓰지 않았다.

## 로그

파일: `logcat-s3navi.txt`, `logcat-s3-searchplace.txt`, `logcat-s3navi-back.txt`, `ui-dump-covered.txt`, `ui-dump-after-back.txt`, `ui-dump-navi-app.txt`, `window-focus.txt`, `window-focus-after-back.txt`, `window-focus-navi.txt`.

시험 앱 [실측] `logcat-s3navi.txt`:

```
11-11 04:07:41.640 I S3Navi  : create
11-11 04:07:41.658 I S3Navi  : initialize called
11-11 04:07:41.673 I S3Navi  : onConnected
11-11 04:07:41.673 I S3Navi  : requestNavigationScreen SearchPlace(정비소)
11-11 04:07:41.675 I S3Navi  : requestNavigationScreen returned
```

내비게이션 쪽 [실측] `logcat-s3-searchplace.txt`:

```
11-11 04:07:41.673 I [NaviHelper][SDK]NaviHelper: [main]requestNavigationScreen
11-11 04:07:41.675 I [Navi]a : [binder:1753_5]requestToNaviProvider(RequestNavigationScreen) ai.pleos.spike.s1 >> ai.umos.maps.android.navigation.app
11-11 04:07:41.676 D [NaviProvider][SDK]b: [binder:3397_7]onNavigationScreenRequest
11-11 04:07:41.676 D MainViewModel$startConnectNaviProviderCollect: connectNaviProviderEventFlow: SearchPlace(keyword=정비소)
11-11 04:07:41.676 D MainViewModel: NavigationScreenRequest.SearchPlace with keyword: 정비소
11-11 04:07:41.677 D SearchViewModel$searchPlace: Place search query:정비소
11-11 04:07:41.982 D NavigationUseCase: place search success: SearchResponse(code=200, ... query=정비소, ... placeName=삼성모터스 판교점 ...)
11-11 04:07:42.077 D [GAA]   : observeSearchResult() >> NaviApp is Background. ai.umos.maps.android.navigation.app clear
```

`place search success` 한 줄은 길어서 여기서는 앞부분을 적었다. 원문은 `logcat-s3-searchplace.txt` 에 있다. 그 줄에 `placeName=삼성모터스 판교점` 이 있다.

첫 화면 덤프 [실측] 패키지 `ai.pleos.spike.s1`. 텍스트는 `create`, `initialize called`, `onConnected`, `requestNavigationScreen SearchPlace(정비소)`, `requestNavigationScreen returned`. 정비소 이름은 없다. `window-focus.txt`: `mCurrentFocus` 와 `mFocusedApp` 이 `ai.pleos.spike.s1`.

둘째 실행 [실측] `logcat-s3navi-back.txt`: `requestNavigationScreen returned` 다음 줄 `moveTaskToBack true`. 6초 뒤 포커스는 시험 앱이었다(`/tmp` 의 dumpsys, 보고서 작성 시 확인). `ui-dump-after-back.txt` 패키지도 `ai.pleos.spike.s1`.

내비게이션 태스크를 앞으로 가져온 뒤 [실측] `window-focus-navi.txt` 포커스 `ai.umos.maps.android.navigation.app`. `ui-dump-navi-app.txt` 에서 읽은 글자: `정비소`, `삼성모터스 판교점`, `자동차정비,수리`, `440m`, `경기도 성남시 수정구 달래내로32번길 4`, `기아오토큐 삼평점`, `GSW 판교점`, `지우기`.

`onFailed`, `SecurityException`, `onError` 는 `S3Navi` 로그에 없다.

## 확인하지 못한 것

- 시험 앱이 앞에 있는 동안 검색 목록이 다른 영역에 떠 있었는지는 그 덤프에 없었다. 목록을 본 것은 내비게이션 태스크를 앞으로 가져온 뒤다.
- `moveTaskToBack` 가 `true` 를 돌려준 뒤에도 포커스가 시험 앱에 남은 이유는 확인하지 못했다.
- 검색어 `전기차 충전소`, `주유소` 는 호출하지 않았다.
- 이 호출로 경로 안내(`ROUTE_GUIDING`)가 시작되는지는 보지 않았다. `S3Navi` 에 `onRouteStarted` 가 없다.
- 결과 거리 `440m`, `1.1km` 가 어떤 기준점인지는 확인하지 않았다. 화면 글자에 그렇게 적혀 있었다.
