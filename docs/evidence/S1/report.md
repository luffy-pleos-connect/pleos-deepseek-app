# 시험 S1: NaviHelper 길안내 시작

호스트 시각 [실측] `date` = Wed Nov 11 18:52:53 KST 2026.
에뮬레이터 시각 [실측] `adb shell date` = Wed Nov 11 01:52:53 PST 2026. logcat 시각은 에뮬레이터 시계다.

## 결론

된다.

Pleos 에뮬레이터 v2.1.0, 사용자 10에서 시험 앱이 `NaviHelper.requestRoute` 를 한 번 호출했고, 약 7초 뒤 내비게이션 쪽 콜백이 `onRouteStarted` 와 `onNavigationStatus ROUTE_DRIVE`, `naviMode=ROUTE_GUIDING` 을 보냈다. 목적지 이름은 요청한 `서울시청` 이었다. 로그 파일 `logcat-s1navi.txt`, 화면 글자 `ui-dump.xml`.

장소 검색은 이 SDK 의 공개 요청 API 에 없다. `requestRoute` 는 좌표와 이름·주소·poiId·poiSubId 를 담은 `RouteInfo` 를 받는다. `NavigationScreenRequest.SearchPlace(keyword)` 는 검색 화면을 여는 요청이고, 장소 목록을 돌려주는 함수가 아니다. `NearbyChargingStations` 도 화면 요청이다. 가까운 정비소를 찾아 좌표를 받는 API 는 공개 메서드 목록에 없다. 좌표와 주소는 앱이 넣어야 한다.

## SDK 와 API

의존성 [실측] Maven 메타데이터 `https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/maven-metadata.xml`: group `ai.pleos.playground`, artifact `NaviHelper`, latest `3.0.5` (versions 2.0.2, 2.0.3, 3.0.5, lastUpdated 20261019002623). 시험 전 `~/.gradle/caches/modules-2/files-2.1/ai.pleos.playground` 에는 `Vehicle`, `vehicle-aidl`, `vehicle-data` 만 있었고 NaviHelper 는 없었다. 빌드가 `3.0.5` 를 받았다.

POM [실측] `NaviHelper-3.0.5.pom`: packaging aar, compile 의존 `ai.pleos.playground:navi-data:3.0.4`, runtime `ai.pleos.playground:navi-aidl:3.0.1`.

AAR 매니페스트 [실측] `NaviHelper-3.0.5.aar` 의 `AndroidManifest.xml`: `minSdkVersion=23`, intent action `ai.pleos.playground.intent.action.NAVI_START`, meta-data `min_version_name=2.0.1`, `min_version_code=11`, `current_version_name=3.0.5`, `current_version_code=75`.

에뮬레이터에 설치된 서비스 [실측] `adb shell pm path ai.pleos.playground.service.navi` = `package:/system_ext/priv-app/NaviService/NaviService.apk`. `dumpsys package` : versionName `3.0.4`, versionCode `55`, 사용자 0과 10에 installed=true. 서비스 액션 `ai.pleos.playground.intent.action.NAVI_START`. 권한 `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `NAVI_CUSTOM_ETC`, `NAVI_CUSTOM_ROUTE`, `NAVI_NOTIFY` 의 prot=normal.

내비 앱 [실측] `ai.umos.maps.android.navigation.app`, codePath `/system_ext/priv-app/Navi42`, versionName `3.3.2-rc5-hotfix.3`. 사용자 10에서 stopped=false. `queriesIntents` 에 `ai.pleos.playground.intent.action.NAVI_START` 가 있다.

`javap -public` [실측] `ai.pleos.playground.navi.helper.NaviHelper`:

- `NaviHelper(android.content.Context)`
- `initialize()`
- `initialize(Function0<Unit>, Function1<? super Throwable, Unit>)`
- `release()`, `addListener`, `removeListener`
- `requestRoute(RouteInfo)`
- `requestRouteWithWaypoints(RouteWithWaypointsRequest)`
- `cancelRoute()`, `addWaypoint`, `removeWaypoint`, `changeRouteOption`, `requestReRoute`
- `getBookmarkInfo`, `getRecentDestinationInfo`, `getRouteStateInfo`, `getCurrentLocationInfo`, `getDestinationInfo`, `getWaypointInfo`, `getTBTInfo`, `getChargerOperatorInfo`
- `requestNavigationScreen(NavigationScreenRequest)`
- `requestTrip(TripRequest)`
- `getInternalApi()`

`InternalApi` [실측] 공개 메서드는 `updateOccupiedRegion(android.graphics.Rect)` 하나다. `PrivateApi` 는 애너테이션이다.

`RouteInfo` 생성자 [실측] `(double, double, Double, Double, String, String, String, String, RouteOption, String, boolean)`. getter 순서: latitude, longitude, displayLatitude, displayLongitude, poiName, poiId, address, poiSubId, routeOption, poiProvider, isMobileRequest. 컴파일 [실측] `poiId` 와 `poiSubId` 를 빼면 `No value passed for parameter 'poiId'` / `'poiSubId'`. 이번 호출은 둘 다 빈 문자열이었다. 화면에 찍힌 toString 은 `routeOption=RECOMMENDED`, `poiProvider=` 빈 값, `isMobileRequest=false`.

`NavigationScreenRequest.SearchPlace` [실측] 생성자 `(String)`, `getKeyword()`. 같은 클래스의 다른 화면 요청에 `NearbyChargingStations` 가 있다. 장소 목록을 반환하는 메서드는 `NaviHelper` 공개 메서드에 없다.

권한 [문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions) 문장: "`pleos.car.permission.NAVI_ROUTE_SEARCH` | `requestRoute()`, `requestRouteWithWaypoints()`, `cancelRoute()`, `requestReRoute()`, `addWaypoint()`, `removeWaypoint()`, `changeRouteOption()`". 같은 표에서 `NAVI_ROUTE` 는 `get*Info()`, `requestTrip()`. `NAVI_CUSTOM_MAP` 은 `requestNavigationScreen()`.

초기화 [문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release) 문장: "`initialize(onConnected, onFailed)` | 서비스 연결 결과를 콜백으로 받습니다. 최초 서비스 연결이 성공하면 `onConnected`가, 초기 연결 재시도가 모두 실패하면 `onFailed`가 호출됩니다."

목적지 [문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations) 문장: "`requestRoute()`로 목적지를 설정하고, `getDestinationInfo()`로 확인한 뒤, `cancelRoute()`로 취소합니다." 같은 페이지: "`requestRoute()` 호출이 정상적으로 수행되더라도 경로 탐색 완료를 의미하지 않습니다. 최종 결과는 이벤트 콜백으로 확인합니다." 콜백 표: `onRouteStarted(routeStartInfo)` 는 "Navigation 앱이 경로 탐색을 시작하면 호출됩니다." `onRoutingCompleted()` 는 "경로 탐색이 완료되면 호출됩니다."

소개 [문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) 문장: "이 가이드는 NaviHelper SDK v3.0.5를 기준으로 작성되었습니다." 제공 기능 목록은 목적지 설정·확인·취소, 경로 옵션 변경 및 재탐색, 경유지, 경유지 포함 경로, 경로 탐색과 주행 상태 이벤트, 내비게이션 정보 조회 및 Navigation 화면 요청이다. 장소 검색으로 결과를 받는 항목은 그 목록에 없다.

가져오기 [문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk) 문장: `implementation("ai.pleos.playground:NaviHelper:3.0.5")`, Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`.

샘플 [문서](https://github.com/Pleos-Playground/pleos-sample-navi-app) README 문장: "The sample downloads NaviHelper 3.0.5 from the Pleos Playground Maven repository" 와 `implementation("ai.pleos.playground:NaviHelper:3.0.5")`. 샘플 매니페스트는 `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `NAVI_CUSTOM_ROUTE`, `NAVI_CUSTOM_ETC` 를 선언한다. 샘플 `RouteInfo` 예시는 poiId `36khp37hh8gqcm5nqhe5` 를 넣는다. 이번 시험은 그 poiId 를 쓰지 않았다.

`document.pleos.ai` 의 request-route 독카 페이지 본문은 이번 fetch 에서 제목만 왔다. 시그니처는 AAR `javap` 으로 확인했다.

## 돌린 명령

에뮬레이터 [실측] 종료 전까지 백그라운드로 실행. 준비 확인 `adb shell getprop sys.boot_completed` = `1`. `adb shell am get-current-user` = `10`.

```
/Users/heodongun/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -gpu swiftshader_indirect -no-audio -no-window
```

시험 앱 위치: `/Users/heodongun/Desktop/플레오스/.spike/s1` (저장소 밖).

```
cd /Users/heodongun/Desktop/플레오스/.spike/s1 && ./gradlew assembleDebug
```

[실측] `BUILD SUCCESSFUL`.

```
adb install -r --user 10 /Users/heodongun/Desktop/플레오스/.spike/s1/app/build/outputs/apk/debug/app-debug.apk
```

[실측] `Success`.

```
adb logcat -c
adb shell am start --user 10 -n ai.pleos.spike.s1/.MainActivity
```

[실측] `Starting: Intent { cmp=ai.pleos.spike.s1/.MainActivity }`. 12초 뒤

```
adb shell rm -f /sdcard/u.xml
adb shell uiautomator dump /sdcard/u.xml
adb shell cat /sdcard/u.xml
adb logcat -d -s S1Navi:V
```

`adb shell monkey` 는 쓰지 않았다.

## 로그

파일: `logcat-s1navi.txt`, `ui-dump.xml`.

핵심 줄 [실측]:

```
01:51:51.314 I S1Navi  : onConnected
01:51:51.332 I S1Navi  : requestRoute RouteInfo(latitude=37.566535, longitude=126.977969, ... poiName=서울시청, poiId=, address=서울특별시 중구 세종대로 110, poiSubId=, routeOption=RECOMMENDED ...)
01:51:51.335 I S1Navi  : requestRoute returned
01:51:58.413 I S1Navi  : onRouteStarted RouteStartInfo(duration=3240, distance=22000, tollFare=700, name=서울시청, remainDestinationChargingValue=74514)
01:51:58.418 I S1Navi  : onRouteEnded
01:51:58.421 I S1Navi  : onNavigationStatus ROUTE_DRIVE
01:51:58.439 I S1Navi  : onRouteStateInfo RouteStateInfo(... naviMode=ROUTE_GUIDING, isRouting=false, routeOption=RECOMMENDED ...)
```

화면 글자 [실측] 패키지 `ai.pleos.spike.s1` 의 TextView 가 위와 같은 순서를 그대로 보여 줬다. `onFailed`, `SecurityException`, 예외 이름은 이 로그에 없다. `onRoutingCompleted` 도 이 로그에 없다.

duration, distance, tollFare 의 단위는 이번 시험의 로그 문자열에 없다. 숫자를 단위로 바꾸지 않는다.

## 앱에 넣으려면

저장소 `settings.gradle.kts` 에 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/` 를 두고, 앱 모듈에 `implementation("ai.pleos.playground:NaviHelper:3.0.5")`.

매니페스트에 최소한

```xml
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" />
```

호출은 `onConnected` 다음에 한 번.

```kotlin
val navi = NaviHelper(applicationContext)
navi.initialize(
    onConnected = {
        navi.addListener(listener)
        navi.requestRoute(
            RouteInfo(
                latitude = 37.566535,
                longitude = 126.977969,
                poiName = "서울시청",
                poiId = "",
                poiSubId = "",
                address = "서울특별시 중구 세종대로 110",
            )
        )
    },
    onFailed = { /* 연결 실패 */ },
)
```

가까운 정비소는 이 호출 전에 앱이 좌표와 주소를 가지고 있어야 한다. 정비소 검색 결과는 SDK 가 주지 않는다.

## 확인하지 못한 것

- Navi42 지도 화면에 경로선이 그려졌는지는 보지 못했다. 화면 덤프는 시험 앱이 앞에 있는 상태다. [미확인]
- `onRoutingCompleted` 는 시작 후 이 logcat 구간에 없었다. 안내 상태로 읽은 근거는 `naviMode=ROUTE_GUIDING` 과 `ROUTE_DRIVE` 문자열이다. [실측] 그 문자열이 지도 위 안내와 같은지는 [미확인]
- `poiId` 에 샘플의 실제 POI 아이디를 넣은 호출은 하지 않았다. 빈 문자열 한 번만 했다.
- `NavigationScreenRequest.SearchPlace` 와 `NearbyChargingStations` 는 호출하지 않았다.
- `request-route` 독카 페이지 본문은 가져오지 못했다.
- duration=3240, distance=22000 의 단위는 확인하지 못했다.
