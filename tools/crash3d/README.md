# crash3d — 대전하기 충돌 시네마틱 사전 렌더 (앱용 가로 + 홍보용 세로)

앱은 영상을 재생만 한다. 이 도구가 three.js 장면을 프레임마다 렌더해 mp4/png 로 뽑는다.
같은 입력이면 같은 영상이 나온다(시간 직접 주입, 난수 시드 고정, 물리 엔진 없음).

## 필요한 것
- Node v24 (전역 WebSocket), 시스템 Chrome, `/opt/homebrew/bin/ffmpeg`
- `npm install` (three@0.170.0 고정)

## 다시 렌더하기
```
cd tools/crash3d
npm install
node render.mjs all                 # 영상 3개 + 정지 그림 4개를 res/ 에 덮어쓴다
node render.mjs mine_wins           # 한 가지만 (mine_wins | theirs_wins | draw)
node render.mjs stills              # 정지 그림 4장만
node render.mjs draw --frames 50,60 # 지정한 프레임 PNG 만 frames/ 에 (mp4 안 만듦)
```
- 출력: `app/app/src/main/res/raw/battle_crash_{mine_wins,theirs_wins,draw}.mp4`,
  `app/app/src/main/res/drawable-nodpi/battle_car_{mine,mine_wrecked,theirs,theirs_wrecked}.png`
- 1600x900, 30fps, 6초, 무음, H.264 main / yuv420p / faststart. 3MB 이하가 되도록 crf 18부터 올린다.
- Chrome 은 10120~10199 중 빈 포트에 CDP 로 붙는다(다른 세션 Chrome 에 붙지 않음).

## 파일
- `car.js` 차 모델(로프트 + 기본 도형, 앞부분 찌그러짐 함수)
- `scene.js` 시간 재매핑(슬로모션), 차 동선, 파편 탄도, 불꽃/연기, 카메라, 정지 그림
- `render.mjs` Chrome CDP 구동 + ffmpeg 인코딩

## 색
`pleos-connect-design-system/pleos-ds/.../values/pleos_colors.xml` 값: 배경 `pleos_background_primary` #F7F8FA,
내 차 `pleos_informative_positive` #0064FF, 상대 차 `pleos_informative_negative` #FE3D16.

## 홍보용 세로판 (1080x1920)
앱용과 같은 차 모델(`car.js`)·같은 방식(시간 직접 주입, 난수 시드 고정)이고, 장면은 `promo.js`/`promo.html` 에 따로 있다.
앱용 파일·명령은 건드리지 않는다. 어두운 스튜디오(바닥 반사, 파랑·주황 림 라이트, 안개, 블룸, 비네팅).
```
cd tools/crash3d
node render.mjs promo                    # 영상 3개 + 정지 그림 2장을 promo/assets/crash/ 에 덮어쓴다 (영상 한 개에 약 4분)
node render.mjs promo_blue_wins          # 한 가지만 (promo_blue_wins | promo_red_wins | promo_slowmo | promo_stills)
node render.mjs promo_red_wins --frames 40,60,173 --probe   # 지정 프레임 PNG 만 frames/red_wins/ 에, 두 차·충돌 지점의 화면 px 위치도 출력(확인용)
```
- 출력: `promo/assets/crash/crash_v_{blue_wins,red_wins,slowmo}.mp4`, `car_{blue,red}_hero.png`
- 영상: 1080x1920, 30fps, H.264 High / yuv420p / BT.709 / faststart, 무음. 파랑·빨강 승리 5.8초(마지막 0.5초 정지), 슬로모션 클로즈업 2.8초. crf 16부터 15MB 이하가 될 때까지 올린다.
- 구도: 카메라는 고도 약 45°, 두 차는 화면 위(이긴 차)·아래(진 차)에서 대각선으로 마주 달려와 세로 약 42% 지점에서 부딪친다. 차와 충돌 지점은 세로 300~1250px 안(자막·플랫폼 UI 영역 밖)에 둔다 — `--probe` 로 확인.
- 정지 그림: 배경 투명 1080x680, 3/4 앞모습(파랑은 오른쪽, 빨강은 왼쪽을 본다), 멀쩡한 상태.
- 디버그: `--pre "window.__off={bloom:1}"` 처럼 장면 준비 뒤 JS 를 실행할 수 있다(`floor|mirror|haze|bloom` 끄기).
