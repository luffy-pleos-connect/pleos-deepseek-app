# crash3d — 대전하기 충돌 시네마틱 사전 렌더

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
