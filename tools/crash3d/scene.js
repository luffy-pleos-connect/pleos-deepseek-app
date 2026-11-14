// 대전하기 충돌 시네마틱 — 장면·연출. render.mjs 가 setupVariant / renderFrame / renderStill 을 호출한다.
// 물리 엔진 없음: 키프레임 + 탄도 계산. 난수는 시드 고정이라 같은 입력이면 같은 프레임이 나온다.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { buildCar, L, WHEEL_R, getTextures } from './car.js';

// 색: pleos-ds values/pleos_colors.xml — pleos_background_primary / pleos_informative_positive(파랑) / pleos_informative_negative(빨강)
const BG = 0xF7F8FA, BLUE = 0x0064FF, RED = 0xFE3D16;
const W = 1600, H = 900;
const G = 9.8;
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
const lerp = (a, b, t) => a + (b - a) * t;
function rng(seed) {
  return () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
// 카트멀-롬 키프레임 보간
function kf(keys, s) {
  const n = keys.length;
  if (s <= keys[0][0]) return keys[0][1];
  if (s >= keys[n - 1][0]) return keys[n - 1][1];
  let i = 0; while (s > keys[i + 1][0]) i++;
  const [s0, v0] = keys[i], [s1, v1] = keys[i + 1];
  const h = s1 - s0, t = (s - s0) / h;
  const m0 = i > 0 ? (v1 - keys[i - 1][1]) / (s1 - keys[i - 1][0]) : (v1 - v0) / h;
  const m1 = i + 2 < n ? (keys[i + 2][1] - v0) / (keys[i + 2][0] - s0) : (v1 - v0) / h;
  const t2 = t * t, t3 = t2 * t;
  return (2 * t3 - 3 * t2 + 1) * v0 + (t3 - 2 * t2 + t) * h * m0 + (-2 * t3 + 3 * t2) * v1 + (t3 - t2) * h * m1;
}
const kfv = (keys, s) => kf(keys, s);

// ---------- 시간 재매핑 (슬로모션) ----------
const TAU_I = 1.15; // 충돌이 일어나는 시뮬레이션 시각
function rate(u) {
  if (u < -0.15) return 1;
  if (u < -0.03) return lerp(1, 0.2, ss(-0.15, -0.03, u));
  if (u < 0.08) return 0.2;
  if (u < 0.36) return lerp(0.2, 1, ss(0.08, 0.36, u));
  return 1;
}
const TT = [0];
{ const d = 0.001; let t = 0; for (let i = 0; i < 9000; i++) { t += d / rate(i * d + d / 2 - TAU_I); TT.push(t); } }
function tauOf(t) {
  let lo = 0, hi = TT.length - 1;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (TT[m] <= t) lo = m; else hi = m; }
  return (lo + (t - TT[lo]) / (TT[hi] - TT[lo])) * 0.001;
}
const T_IMPACT = TT[Math.round(TAU_I * 1000)];
const HOLD_FROM = 5.3; // 이 시각부터 최종 구도로 정지
const TAU_END = tauOf(HOLD_FROM);

// ---------- 탄도 ----------
function makePath(p0, v0, { restY = 0.05, e = 0.38, k = 2.4, fr = 0.78 } = {}) {
  const segs = []; let t = 0; const p = p0.clone(), v = v0.clone();
  let rest = null;
  for (let b = 0; b < 4; b++) {
    const c = p.y - restY, bq = v.y;
    const disc = Math.max(0, bq * bq + 2 * G * c);
    const tau = (bq + Math.sqrt(disc)) / G;
    segs.push({ t0: t, p0: p.clone(), v0: v.clone(), dur: tau });
    const vyEnd = v.y - G * tau;
    p.x += v.x * tau; p.z += v.z * tau; p.y = restY; t += tau;
    const nv = -vyEnd * e;
    v.set(v.x * fr, nv, v.z * fr);
    if (nv < 1.1 || b === 3) { rest = { t0: t, p0: p.clone(), vx: v.x, vz: v.z, k }; break; }
  }
  const out = new THREE.Vector3();
  return {
    tLand: segs[0].dur,
    tRest: rest.t0 + Math.log(Math.max(1.0001, Math.hypot(rest.vx, rest.vz) / 0.3)) / k,
    at(s, o = out) {
      if (s <= 0) return o.copy(p0);
      for (const sg of segs) {
        if (s < sg.t0 + sg.dur) { const x = s - sg.t0; return o.set(sg.p0.x + sg.v0.x * x, sg.p0.y + sg.v0.y * x - 0.5 * G * x * x, sg.p0.z + sg.v0.z * x); }
      }
      const x = s - rest.t0, f = (1 - Math.exp(-rest.k * x)) / rest.k;
      return o.set(rest.p0.x + rest.vx * f, restY, rest.p0.z + rest.vz * f);
    },
    hSpeed(s) { if (s < rest.t0) return Math.hypot(v0.x, v0.z); return Math.hypot(rest.vx, rest.vz) * Math.exp(-rest.k * (s - rest.t0)); },
    firstVh: Math.hypot(v0.x, v0.z),
  };
}

// ---------- 장면 공통 ----------
let renderer = null;
function ensureRenderer() {
  if (renderer) return renderer;
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: false });
  renderer.setPixelRatio(1);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.NoToneMapping;
  document.body.appendChild(renderer.domElement);
  return renderer;
}
let envTex = null;
function envMap() {
  if (!envTex) { const pm = new THREE.PMREMGenerator(renderer); envTex = pm.fromScene(new RoomEnvironment(), 0.04).texture; }
  return envTex;
}
function radialAlpha() {
  const c = document.createElement('canvas'); c.width = c.height = 512; const g = c.getContext('2d');
  const gr = g.createRadialGradient(256, 256, 0, 256, 256, 256);
  gr.addColorStop(0, 'rgb(190,190,190)'); gr.addColorStop(0.08, 'rgb(205,205,205)'); gr.addColorStop(0.16, 'rgb(255,255,255)'); gr.addColorStop(1, 'rgb(255,255,255)');
  g.fillStyle = gr; g.fillRect(0, 0, 512, 512);
  return new THREE.CanvasTexture(c);
}
function glowTex(stops) {
  const k = document.createElement('canvas'); k.width = k.height = 128; const g = k.getContext('2d');
  const gr = g.createRadialGradient(64, 64, 0, 64, 64, 64);
  for (const [o, col] of stops) gr.addColorStop(o, col);
  g.fillStyle = gr; g.fillRect(0, 0, 128, 128);
  const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t;
}
const FX = {};
function fxTextures() {
  if (FX.flash) return FX;
  FX.flash = glowTex([[0, 'rgba(255,255,255,1)'], [0.2, 'rgba(255,246,170,0.95)'], [0.5, 'rgba(255,170,40,0.55)'], [1, 'rgba(255,120,0,0)']]);
  FX.smoke = glowTex([[0, 'rgba(112,116,126,0.85)'], [0.5, 'rgba(112,116,126,0.45)'], [1, 'rgba(112,116,126,0)']]);
  FX.ring = (() => { const k = document.createElement('canvas'); k.width = k.height = 256; const g = k.getContext('2d');
    const gr = g.createRadialGradient(128, 128, 60, 128, 128, 128);
    gr.addColorStop(0, 'rgba(255,170,40,0)'); gr.addColorStop(0.7, 'rgba(255,170,40,0.55)'); gr.addColorStop(0.85, 'rgba(255,200,90,0.3)'); gr.addColorStop(1, 'rgba(255,200,90,0)');
    g.fillStyle = gr; g.fillRect(0, 0, 256, 256); const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t; })();
  return FX;
}

function makeStage({ still = false } = {}) {
  ensureRenderer();
  const scene = new THREE.Scene();
  scene.environment = envMap();
  scene.environmentIntensity = 0.85;
  const world = new THREE.Group(); scene.add(world);
  const fx = new THREE.Group(); scene.add(fx);
  scene.add(new THREE.HemisphereLight(0xffffff, 0xdfe3ea, 0.75));
  const key = new THREE.DirectionalLight(0xffffff, 2.1);
  key.position.set(-3, 11, 7); key.target.position.set(1.5, 0, 0);
  key.castShadow = true;
  key.shadow.mapSize.set(3072, 3072);
  const sc = key.shadow.camera; sc.left = -15; sc.right = 15; sc.top = 9; sc.bottom = -9; sc.near = 1; sc.far = 40;
  key.shadow.bias = -0.0004; key.shadow.normalBias = 0.03;
  scene.add(key, key.target);
  const rim = new THREE.DirectionalLight(0xcfe4ff, 0.6); rim.position.set(6, 4, -6); scene.add(rim);

  const shadowPlane = new THREE.Mesh(new THREE.PlaneGeometry(80, 40), new THREE.ShadowMaterial({ opacity: 0.3 }));
  shadowPlane.rotation.x = -Math.PI / 2; shadowPlane.position.y = 0.003; shadowPlane.receiveShadow = true; shadowPlane.renderOrder = 2;
  scene.add(shadowPlane);
  let mirror = null, floor = null;
  if (!still) {
    scene.background = new THREE.Color(BG);
    floor = new THREE.Mesh(new THREE.PlaneGeometry(240, 240), new THREE.MeshBasicMaterial({ color: BG, transparent: true, alphaMap: radialAlpha(), toneMapped: false, depthWrite: false }));
    floor.rotation.x = -Math.PI / 2; floor.position.set(1.5, 0, 0); floor.renderOrder = 1; floor.scale.set(1, 1, 1);
    scene.add(floor);
  }
  return { scene, world, fx, key, mirror, floor, still };
}

function addMirror(stage) {
  const mirror = stage.world.clone(true);
  mirror.scale.set(1, -1, 1);
  mirror.traverse((o) => { o.castShadow = false; o.receiveShadow = false; });
  stage.scene.add(mirror);
  stage.mirror = mirror;
}
function syncMirror(a, b) {
  b.matrix.copy(a.matrix); b.position.copy(a.position); b.quaternion.copy(a.quaternion); b.scale.copy(a.scale);
  b.visible = a.visible && !a.userData.noMirror;
  for (let i = 0; i < a.children.length; i++) syncMirror(a.children[i], b.children[i]);
}
function syncMirrorTop(stage) {
  if (!stage.mirror) return;
  for (let i = 0; i < stage.world.children.length; i++) syncMirror(stage.world.children[i], stage.mirror.children[i]);
}

// ---------- 차 포즈 ----------
const _m = new THREE.Matrix4(), _q = new THREE.Quaternion(), _e = new THREE.Euler(), _v = new THREE.Vector3(), _one = new THREE.Vector3(1, 1, 1);
function poseMatrix(out, x, y, z, theta, pitch, roll) {
  _e.set(roll, theta, pitch, 'YZX'); // 월드 = Ry(theta) * Rz(pitch) * Rx(roll)
  _q.setFromEuler(_e);
  return out.compose(_v.set(x, y, z), _q, _one);
}

function makeCarObj(hex) {
  const car = buildCar(hex);
  for (const p of Object.values(car.parts)) { car.parts[p.name].group.visible = true; }
  return car;
}

// 장면 구성 --------------------------------------------------------------
let ST = null; // 현재 variant 상태
function disposeStage(st) { if (!st) return; (st.scene ?? st.stage.scene).traverse((o) => { if (o.geometry) o.geometry.dispose(); }); }

window.setupVariant = (variant) => {
  disposeStage(ST);
  const stage = makeStage();
  const d = variant === 'theirs_wins' ? -1 : 1; // d-프레임: 이긴 차가 -x_d 쪽에서 +x_d 방향으로 달려든다
  const S = -d; // 진 차의 망가지는 쪽(차 로컬 z 부호). 카메라(+z) 쪽 옆면이다.
  const isDraw = variant === 'draw';
  const aColor = variant === 'theirs_wins' ? RED : BLUE; // A = d-프레임 왼쪽(이긴 차 / 무승부는 내 차)
  const bColor = variant === 'theirs_wins' ? BLUE : RED;
  const A = makeCarObj(aColor), B = makeCarObj(bColor);
  for (const car of [A, B]) for (const p of Object.values(car.parts)) stage.world.add(p.group);
  addMirror(stage);
  ST = { variant, stage, d, S, isDraw, A, B, rnd: rng(7), cache: {} };
  buildFx(ST);
  window.__ready = true;
};

// 차 포즈 함수 (d-프레임 계산 → 월드 변환) ---------------------------------
const D_APPROACH = 7.0;
function approach(tau) { const u = clamp(tau / TAU_I, 0, 1); return D_APPROACH * (1 - Math.pow(u, 1.4)); }
const DL = 3.0, KL = 2.4;
const dl = (s) => DL * (1 - Math.exp(-KL * Math.max(0, s)));
const cW = (s) => ss(0, 0.22, s);

function poseOf(st, role, tau) {
  const { d, S } = st;
  const s = tau - TAU_I;
  const P = { x: 0, y: 0, z: 0, h: 0, pitch: 0, roll: 0, c: 0, spin: 0, hood: 0, scorch: 1 };
  if (role === 'W') {
    if (s < 0) { P.x = -L - approach(tau); }
    else {
      const dw = s < 0.22 ? dl(s) + 0.7 * cW(s) : kf([[0.22, dl(0.22) + 0.7], [0.5, 2.0], [0.95, 1.5], [1.55, 1.8], [2.3, 1.7], [3.5, 1.7]], s);
      P.x = -L + dw;
    }
    P.c = 0.07 * cW(s); P.scorch = 0.3;
    P.pitch = s < 0 ? 0.012 * ss(-0.4, 0, s) * -1 : kf([[0, 0], [0.08, -0.03], [0.4, 0.022], [0.9, -0.012], [1.6, 0.006], [2.4, 0]], s);
    P.y = s < 0 ? 0 : kf([[0, 0], [0.12, 0.03], [0.4, 0], [0.8, 0.015], [1.2, 0]], s);
    P.spin = -(P.x + L + (s < 0 ? 0 : 0) + D_APPROACH) / WHEEL_R * 1;
    P.spin = -((P.x - (-L - D_APPROACH))) / WHEEL_R;
    P.h = s < 0 ? 0 : kf([[0, 0], [0.4, 0.015], [1.2, -0.006], [2.2, 0]], s);
  } else if (role === 'L') {
    if (s < 0) { P.x = L + approach(tau); }
    else {
      P.x = L + dl(s);
      P.z = -0.7 * (1 - Math.exp(-2.2 * s));
    }
    const k = 3.2;
    P.h = Math.PI + (s < 0 ? 0 : 1.05 * (1 - (1 + k * s) * Math.exp(-k * s)));
    P.c = cW(s); P.scorch = 1;
    P.pitch = s < 0 ? 0 : kf([[0, 0], [0.1, 0.09], [0.35, 0.05], [0.8, -0.04], [1.3, -0.02], [2.2, -0.025]], s);
    P.roll = s < 0 ? 0 : S * kf([[0, 0], [0.12, -0.03], [0.5, 0.05], [1.2, 0.065], [2.2, 0.065]], s);
    P.y = s < 0 ? 0 : kf([[0, 0], [0.12, 0.16], [0.4, 0], [0.62, 0.06], [0.85, 0], [1.2, -0.03], [2.2, -0.03]], s);
    P.hood = s < 0 ? 0 : kf([[0, 0], [0.06, 0.1], [0.25, 0.7], [0.5, 0.58], [0.9, 0.66], [1.5, 0.64]], s);
    P.spin = -((L + D_APPROACH) - P.x) / WHEEL_R;
  } else { // 무승부: 'DA'(왼쪽) / 'DB'(오른쪽)
    const sgn = role === 'DA' ? -1 : 1;
    const c = ss(0, 0.1, s);
    const eo = (x) => 1 - Math.exp(-2.6 * Math.max(0, x));
    if (s < 0) P.x = sgn * (L + approach(tau));
    else P.x = sgn * (L - 0.8 * c + 2.0 * eo(s - 0.08));
    P.c = 0.7 * c; P.scorch = 0.6;
    P.h = (role === 'DA' ? 0 : Math.PI) + (s < 0 ? 0 : (role === 'DA' ? 1 : -1) * 0.2 * (1 - (1 + 4 * s) * Math.exp(-4 * s)));
    P.pitch = s < 0 ? 0 : kf([[0, 0], [0.1, 0.07], [0.45, -0.03], [1.0, 0.015], [1.8, 0]], s);
    P.y = s < 0 ? 0 : kf([[0, 0], [0.14, 0.12], [0.4, 0], [0.6, 0.04], [0.85, 0]], s);
    P.z = s < 0 ? 0 : (role === 'DA' ? -1 : 1) * 0.0;
    P.spin = -(sgn * (L + D_APPROACH) - P.x) * sgn / WHEEL_R * -1;
    P.spin = -Math.abs((L + D_APPROACH) - Math.abs(P.x)) / WHEEL_R;
  }
  return P;
}
// 월드 변환 (d 적용)
function worldOf(st, P, role) {
  const { d } = st;
  if (role === 'DA' || role === 'DB') return { x: P.x, z: P.z, th: P.h };
  return { x: d * P.x, z: P.z, th: d > 0 ? P.h : Math.PI - P.h };
}
function carMatrixOf(st, role, tau, out) {
  const P = poseOf(st, role, tau); const w = worldOf(st, P, role);
  poseMatrix(out, w.x, P.y, w.z, w.th, P.pitch, P.roll);
  return { P, w };
}

// ---------- 이펙트 / 파편 구성 ----------
function buildFx(st) {
  const { stage, d, S, isDraw } = st;
  const T = fxTextures();
  const rnd = rng(1234 + (isDraw ? 1 : d + 5));
  st.fx = { rnd };
  const roleL = isDraw ? null : 'L';

  // 섬광 / 충격파
  const flash = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.flash, transparent: true, depthWrite: false, depthTest: false }));
  flash.renderOrder = 20; stage.fx.add(flash); st.fx.flash = flash;
  const ring = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: T.ring, transparent: true, depthWrite: false }));
  ring.rotation.x = -Math.PI / 2; ring.renderOrder = 5; stage.fx.add(ring); st.fx.ring = ring;

  // 불꽃 (길쭉한 박스 인스턴스)
  const NS = isDraw ? 260 : 190;
  const sparks = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial({ toneMapped: false }), NS);
  sparks.frustumCulled = false; sparks.renderOrder = 6; sparks.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(NS * 3), 3);
  stage.fx.add(sparks);
  st.fx.sparks = [];
  for (let i = 0; i < NS; i++) {
    const sd = rnd() * 0.3; // 방출 시각(충돌 후)
    const sp = 3 + rnd() * 8;
    const ang = rnd() * Math.PI * 2, el = 0.15 + rnd() * 1.0;
    const dir = new THREE.Vector3(Math.cos(ang) * Math.cos(el) * (0.5 + rnd() * 0.8), Math.sin(el) * 0.9, Math.sin(ang) * Math.cos(el) * 1.3);
    const x0 = isDraw ? (rnd() - 0.5) * 0.3 : 0;
    const origin = new THREE.Vector3(x0, 0.45 + rnd() * 0.35, (rnd() - 0.5) * 1.3);
    st.fx.sparks.push({ sd, life: 0.35 + rnd() * 0.75, path: makePath(origin, dir.multiplyScalar(sp), { restY: 0.02, e: 0.3, k: 4 }), w: 0.03 + rnd() * 0.03 });
  }
  st.fx.sparkMesh = sparks;

  // 연기
  st.fx.smokes = [];
  const mkSmoke = (role, n, t0, dt, local, scale = 1) => {
    for (let i = 0; i < n; i++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.smoke, transparent: true, depthWrite: false, opacity: 0 }));
      sp.renderOrder = 8; stage.fx.add(sp);
      st.fx.smokes.push({ sp, role, ts: t0 + i * dt, local: local.clone().add(new THREE.Vector3((rnd() - 0.5) * 0.5, 0, (rnd() - 0.5) * 0.6)), life: 1.9 + rnd() * 0.6, vy: 0.7 + rnd() * 0.6, drift: new THREE.Vector3((rnd() - 0.5) * 0.5 + d * 0.3, 0, (rnd() - 0.5) * 0.4), sz: scale * (0.7 + rnd() * 0.5), ph: rnd() * 6 });
    }
  };
  if (isDraw) { mkSmoke('DA', 24, 0.15, 0.17, new THREE.Vector3(1.5, 0.85, 0), 0.7); mkSmoke('DB', 24, 0.15, 0.17, new THREE.Vector3(1.5, 0.85, 0), 0.7); }
  else mkSmoke('L', 40, 0.12, 0.07, new THREE.Vector3(1.4, 0.85, 0), 1.1);

  // 파편(진 차) — 부품 탄도
  st.fx.debris = [];
  if (!isDraw) {
    const car = st.B;
    const specs = [
      { name: 'bumperF', s: 0.07, v: [2.6, 3.0, 1.8], w: [1.5, 3.0, 5.5], rest: 0.17, thin: 'y' },
      { name: 'wheelF' + (S > 0 ? 'R' : 'L'), s: 0.1, v: [2.2, 2.6, 2.6], wheel: true, rest: WHEEL_R },
      { name: 'door' + 'F' + (S > 0 ? 'R' : 'L'), s: 0.12, v: [1.4, 3.0, 2.4], w: [4, -6, 7], rest: 0.04, thin: 'z' },
      { name: 'mirror' + (S > 0 ? 'R' : 'L'), s: 0.04, v: [1.0, 2.4, 2.8], w: [9, 5, 11], rest: 0.06, thin: 'y' },
      { name: 'lamp' + (S > 0 ? 'R' : 'L'), s: 0.05, v: [2.4, 2.6, 1.8], w: [7, -5, 4], rest: 0.05, thin: 'y' },
    ];
    for (const sp of specs) {
      const tauSep = TAU_I + sp.s;
      const m = new THREE.Matrix4(); carMatrixOf(st, 'L', tauSep, m);
      const part = car.parts[sp.name];
      const wm = m.clone().multiply(part.anchor);
      const pos = new THREE.Vector3().setFromMatrixPosition(wm);
      const quat = new THREE.Quaternion().setFromRotationMatrix(wm);
      const v0 = new THREE.Vector3(sp.v[0] * d, sp.v[1], sp.v[2]);
      const base = new THREE.Vector3(1.2 * d, 0, -0.3); // 본체가 밀려가는 속도 성분 근사
      v0.add(base);
      const path = makePath(pos, v0, { restY: sp.rest, e: sp.wheel ? 0.3 : 0.36, k: sp.wheel ? 1.8 : 3.0, fr: 0.8 });
      const deb = { part, sp, tauSep, path, q0: quat, pos0: pos };
      if (!sp.wheel) {
        deb.w = new THREE.Vector3(...sp.w);
        const a = { x: new THREE.Vector3(1, 0, 0), y: new THREE.Vector3(0, 1, 0), z: new THREE.Vector3(0, 0, 1) }[sp.thin];
        // 땅에 닿은 순간의 방향 → 납작하게 눕힌 방향
        const wq = qAt(deb, path.tLand);
        const wa = a.clone().applyQuaternion(wq);
        const tgt = new THREE.Vector3(0, wa.y >= 0 ? 1 : -1, 0);
        deb.qFlat = new THREE.Quaternion().setFromUnitVectors(wa.normalize(), tgt).multiply(wq);
        deb.qLand = wq;
      } else {
        const hv = new THREE.Vector3(v0.x, 0, v0.z).normalize();
        deb.phi = Math.atan2(-hv.z, hv.x);
        deb.tEnd = path.tRest;
      }
      st.fx.debris.push(deb);
    }
  }
  // 유리 파편 + 도장 조각 (인스턴스)
  const mkChips = (n, geom, mat, spawn) => {
    const im = new THREE.InstancedMesh(geom, mat, n); im.frustumCulled = false; im.castShadow = true; stage.fx.add(im);
    const arr = [];
    for (let i = 0; i < n; i++) arr.push(spawn(i));
    return { im, arr };
  };
  const dummy = new THREE.Object3D();
  st.fx.dummy = dummy;
  const spawnChip = (role, localY, kind) => (i) => {
    const sp = rnd() * 0.16;
    const tauS = TAU_I + 0.02 + sp;
    const m = new THREE.Matrix4(); carMatrixOf(st, role, tauS, m);
    const local = kind === 'glass' ? new THREE.Vector3(0.2 + rnd() * 0.4, 1.35 + rnd() * 0.25, (rnd() - 0.5) * 1.0) : new THREE.Vector3(1.6 + rnd() * 0.6, 0.45 + rnd() * 0.4, (rnd() - 0.5) * 1.6);
    const pos = local.applyMatrix4(m);
    const sgnX = role === 'L' ? d : (role === 'DA' ? -1 : 1) * -1; // 진행 반대/정면 방향
    const v = new THREE.Vector3((1 + rnd() * 5) * (role === 'L' ? d : (role === 'DA' ? -1 : 1) * -1) * (kind === 'glass' ? 0.9 : 1), 1.5 + rnd() * 4.5, (rnd() - 0.2) * 5);
    return { tauS, path: makePath(pos, v, { restY: 0.02, e: 0.32, k: 3.5 }), axis: new THREE.Vector3(rnd() - 0.5, rnd() - 0.5, rnd() - 0.5).normalize(), rate: 6 + rnd() * 12, sz: (kind === 'glass' ? 0.05 + rnd() * 0.08 : 0.03 + rnd() * 0.05), kind };
  };
  const shardGeom = new THREE.BufferGeometry();
  shardGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array([0, 0, 0, 1, 0.1, 0, 0.35, 0.9, 0]), 3)); shardGeom.setIndex([0, 1, 2]); shardGeom.computeVertexNormals();
  const glassMat = new THREE.MeshPhysicalMaterial({ color: 0x78b8e8, roughness: 0.1, metalness: 0.1, clearcoat: 1, side: THREE.DoubleSide, transparent: true, opacity: 0.85 });
  st.fx.chips = [];
  const roles = isDraw ? ['DA', 'DB'] : ['L'];
  for (const role of roles) {
    const car = role === 'DA' ? st.A : role === 'DB' ? st.B : st.B;
    const nGlass = isDraw ? 0 : 38, nPaint = isDraw ? 14 : 34;
    if (nGlass) { const g = mkChips(nGlass, shardGeom, glassMat, spawnChip(role, 0, 'glass')); st.fx.chips.push(g); }
    const pm = new THREE.MeshStandardMaterial({ color: car.M.paint.color, roughness: 0.4, metalness: 0.2 });
    const p = mkChips(nPaint, new THREE.BoxGeometry(1, 0.4, 0.7), pm, spawnChip(role, 0, 'paint')); st.fx.chips.push(p);
  }
  // 카메라
  st.cam = new THREE.PerspectiveCamera(30, W / H, 0.1, 200);
}

function qAt(deb, s) {
  // 공중 회전: q0 * rot(w*s)
  const ang = deb.w.length() * s;
  const dq = new THREE.Quaternion().setFromAxisAngle(deb.w.clone().normalize(), ang);
  return dq.multiply(deb.q0);
}
const _tmpV = new THREE.Vector3();
function debrisMatrix(deb, tau, out) {
  const s = tau - deb.tauSep;
  const p = deb.path.at(s, _tmpV.set(0, 0, 0));
  let q;
  if (!deb.sp.wheel) {
    const tl = deb.path.tLand;
    if (s <= tl) q = qAt(deb, s);
    else q = deb.qLand.clone().slerp(deb.qFlat, ss(0, 0.45, s - tl));
    // 납작해질 때 높이는 rest 값이므로 그대로
  } else {
    const tl = deb.path.tLand;
    const hs = deb.path.hSpeed(s);
    // 굴러간 거리 → 회전각 (공중에서는 초기 속도로 근사)
    const dist = Math.hypot(p.x - deb.pos0.x, p.z - deb.pos0.z);
    const spin = -dist / WHEEL_R;
    const tilt = (Math.PI / 2) * ss(deb.tEnd - 0.55, deb.tEnd, s);
    const qRoll = new THREE.Quaternion().setFromEuler(new THREE.Euler(tilt, deb.phi, spin * 0 + 0, 'YXZ'));
    // 스핀은 z축(차축) 회전: 로컬에서 먼저
    qRoll.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), spin));
    q = s < tl ? deb.q0.clone().slerp(qRoll, ss(0, tl, s)) : qRoll;
    p.y = WHEEL_R * Math.cos(tilt) + 0.13 * Math.sin(tilt) * (s >= tl ? 1 : 0) + (s < tl ? p.y - WHEEL_R : 0);
    if (s >= tl) p.y = Math.max(p.y, WHEEL_R * Math.cos(tilt) + 0.13 * Math.sin(tilt));
  }
  return out.compose(p, q, _one);
}

// ---------- 카메라 ----------
function cameraFor(st, t) {
  const d = st.d;
  const tI = T_IMPACT;
  let keys;
  if (st.isDraw) {
    keys = [
      [0, [-3.8, 0.55, 13.2], [-0.8, 0.85, 0], 30],
      [tI - 0.6, [-1.6, 0.7, 11.8], [0, 0.85, 0], 30],
      [tI, [-1.0, 0.95, 10.8], [0, 0.85, 0], 29],
      [tI + 0.9, [0.4, 1.1, 11.4], [0, 0.85, 0], 30],
      [tI + 2.2, [0.6, 1.5, 13.6], [0, 0.8, 0], 30],
      [HOLD_FROM - 0.2, [0.5, 1.8, 15.4], [0, 0.8, 0], 30],
    ];
    return camKeys(keys, t, 1);
  }
  keys = [
    [0, [-4.2, 0.55, 13.2], [-0.8, 0.85, 0], 30],
    [tI - 0.6, [-2.0, 0.7, 11.8], [0.0, 0.85, 0], 30],
    [tI, [-1.4, 0.95, 10.8], [0.15, 0.85, 0], 29],
    [tI + 0.9, [0.2, 1.05, 11.0], [1.2, 0.85, 0], 30],
    [tI + 2.2, [1.4, 1.5, 13.2], [2.4, 0.8, 0], 30],
    [HOLD_FROM - 0.2, [2.4, 1.7, 14.2], [2.6, 0.8, 0], 30],
  ];
  return camKeys(keys, t, d);
}
function camKeys(keys, t, d) {
  const ks = (i) => keys.map((k) => [k[0], k[i][0]]);
  const f = (arr, c) => kf(arr, t);
  const pos = new THREE.Vector3(kf(keys.map((k) => [k[0], k[1][0]]), t) * d, kf(keys.map((k) => [k[0], k[1][1]]), t), kf(keys.map((k) => [k[0], k[1][2]]), t));
  const look = new THREE.Vector3(kf(keys.map((k) => [k[0], k[2][0]]), t) * d, kf(keys.map((k) => [k[0], k[2][1]]), t), kf(keys.map((k) => [k[0], k[2][2]]), t));
  const fov = kf(keys.map((k) => [k[0], k[3]]), t);
  return { pos, look, fov };
}
function shake(t) {
  const a = t < T_IMPACT ? 0.004 * ss(T_IMPACT - 1.2, T_IMPACT, t) : 0.17 * Math.exp(-(t - T_IMPACT) / 0.33);
  return [a * (Math.sin(t * 47.1) + 0.6 * Math.sin(t * 91.3 + 1)), a * (Math.sin(t * 53.7 + 2) + 0.5 * Math.sin(t * 77 + 3)), a * 0.6 * Math.sin(t * 61 + 5)];
}

// ---------- 프레임 ----------
const _w = new THREE.Matrix4(), _am = new THREE.Matrix4();
function placeCar(st, car, role, tau, damaged, detached) {
  const m = new THREE.Matrix4();
  const { P, w } = carMatrixOf(st, role, tau, m);
  for (const p of Object.values(car.parts)) {
    p.extra.identity();
    if (p.hinge) p.extra.makeRotationZ(P.hood);
    if (p.wheel) p.spin.rotation.z = P.spin;
    const deb = detached && detached.get(p.name);
    if (deb && tau >= deb.tauSep) { debrisMatrix(deb, tau, p.group.matrix); p.group.visible = true; if (p.seamMesh) p.seamMesh.visible = false; }
    else { p.group.matrix.copy(m).multiply(p.anchor).multiply(p.extra); }
  }
  car.setDamage(P.c, role === 'L' ? st.S : (role === 'W' ? 1 : 1), P.scorch);
  return { P, w, m };
}

window.renderFrame = (t) => {
  const st = ST; const stage = st.stage; const T = fxTextures();
  const tauU = tauOf(t); const tau = Math.min(tauU, TAU_END);
  const s = tau - TAU_I; const sU = tauU - TAU_I;
  const detached = new Map(); for (const deb of st.fx.debris) detached.set(deb.part.name, deb);
  let LP = null;
  if (st.isDraw) {
    placeCar(st, st.A, 'DA', tau); placeCar(st, st.B, 'DB', tau);
  } else {
    placeCar(st, st.A, 'W', tau);
    LP = placeCar(st, st.B, 'L', tau, true, detached);
    st.B.crackMesh.visible = s > 0.02;
  }
  // 전조등·후미등 연출
  const lampState = (car, role) => {
    const P = poseOf(st, role, tau); const ss0 = tau - TAU_I;
    let head = 1, pulse = 1;
    if (role === 'L') { head = ss0 < 0.1 ? 1 : (ss0 < 0.9 ? (Math.sin(ss0 * 70) > -0.3 ? 0.8 : 0.1) : 0.0); }
    if (role === 'W') { const b = ss0 > 1.5 && ss0 < 2.5 ? (Math.sin((ss0 - 1.5) * 9) > 0 ? 1 : 0.15) : 1; head = b; pulse = ss0 > 1.5 && ss0 < 2.5 ? 1 + 0.6 * Math.max(0, Math.sin((ss0 - 1.5) * 9)) : 1; }
    for (const side of ['lampL', 'lampR']) {
      const p = car.parts[side]; p.halo.material.opacity = 0.95 * head; p.halo.scale.setScalar(0.95 * pulse * (0.6 + 0.4 * head));
      p.led.material.color.setScalar(head > 0.05 ? 0.55 + 0.45 * head : 0.35);
    }
    car.parts.bar.group.children[0].material.color.setScalar(head > 0.05 ? 0.6 + 0.4 * head : 0.32);
    car.parts.body.tailHalo.material.opacity = ss0 > -0.12 ? 0.95 : 0.3;
  };
  if (st.isDraw) { lampState(st.A, 'DA'); lampState(st.B, 'DB'); } else { lampState(st.A, 'W'); lampState(st.B, 'L'); }
  // 접지 블롭은 차 기울기와 무관하게 바닥에 둔다 (차 그룹 안이라 약간 기울지만 투명도로 흡수)
  syncMirrorTop(stage);

  // 섬광 / 충격파
  const contactX = (st.d) * (st.isDraw ? 0 : (dl(Math.max(0, s)) + 0.7 * cW(Math.max(0, s))));
  stage.fx.position.set(0, 0, 0);
  const f = st.fx.flash;
  const fs = sU;
  f.position.set(contactX, 0.7, 0.9);
  const fa = fs < 0 ? 0 : (1 - ss(0.04, 0.3, fs)) * (0.25 + 0.75 * ss(0, 0.03, fs));
  f.material.opacity = fa; f.visible = fa > 0.01;
  f.scale.setScalar(lerp(1.0, 4.2, ss(0, 0.2, fs)));
  const rg = st.fx.ring; rg.position.set(contactX, 0.02, 0);
  const ra = fs < 0 ? 0 : 1 - ss(0.05, 0.7, fs); rg.material.opacity = ra; rg.visible = ra > 0.01; rg.scale.set(lerp(0.5, 11, ss(0, 0.7, fs)), lerp(0.5, 11, ss(0, 0.7, fs)) * 0.6, 1);
  rg.scale.set(rg.scale.x, rg.scale.y, 1);

  // 불꽃
  const dm = st.fx.dummy; const sm = st.fx.sparkMesh; const col = new THREE.Color();
  st.fx.sparks.forEach((sk, i) => {
    const a = sU - sk.sd;
    const cx = contactX + 0; // 방출 지점 = 충돌점
    if (a < 0 || a > sk.life) { dm.scale.set(0, 0, 0); dm.position.set(0, -9, 0); dm.updateMatrix(); sm.setMatrixAt(i, dm.matrix); return; }
    const p = sk.path.at(a, _tmpV.set(0, 0, 0));
    const p1 = sk.path.at(a + 0.012, new THREE.Vector3());
    const dir = p1.clone().sub(p); const len = Math.max(dir.length(), 1e-4); dir.divideScalar(len);
    dm.position.set(p.x * st.d + cx, p.y, p.z);
    dir.x *= st.d;
    dm.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir);
    const life = a / sk.life; const L0 = Math.min(0.55, 0.05 + len * 22) * (1 - life * 0.7);
    dm.scale.set(sk.w * (1 - life * 0.6), sk.w * (1 - life * 0.6), L0);
    dm.updateMatrix(); sm.setMatrixAt(i, dm.matrix);
    col.setRGB(1, lerp(0.95, 0.42, ss(0, 0.7, life)), lerp(0.55, 0.0, ss(0, 0.4, life))); sm.setColorAt(i, col);
  });
  sm.instanceMatrix.needsUpdate = true; if (sm.instanceColor) sm.instanceColor.needsUpdate = true;

  // 연기
  for (const sm2 of st.fx.smokes) {
    const age = tauU - TAU_I - sm2.ts;
    if (age < 0 || age > sm2.life) { sm2.sp.visible = false; continue; }
    const m = new THREE.Matrix4(); carMatrixOf(st, sm2.role, TAU_I + Math.min(sm2.ts, TAU_END - TAU_I), m);
    const p0 = sm2.local.clone().applyMatrix4(m);
    const u = age / sm2.life;
    sm2.sp.position.set(p0.x + sm2.drift.x * age + Math.sin(age * 1.7 + sm2.ph) * 0.12, p0.y + sm2.vy * age * (1 - 0.3 * u), p0.z + sm2.drift.z * age);
    sm2.sp.scale.setScalar(sm2.sz * lerp(0.5, 2.1, Math.sqrt(u)));
    sm2.sp.material.opacity = 0.34 * ss(0, 0.12, u) * (1 - ss(0.35, 1, u));
    sm2.sp.visible = true;
  }

  // 유리/도장 조각
  for (const grp of st.fx.chips) {
    grp.arr.forEach((c, i) => {
      const a = tauU - c.tauS;
      if (a < 0) { dm.scale.set(0, 0, 0); dm.position.set(0, -9, 0); }
      else {
        const aa = Math.min(a, TAU_END - c.tauS);
        const p = c.path.at(aa, _tmpV.set(0, 0, 0));
        dm.position.copy(p); dm.quaternion.setFromAxisAngle(c.axis, c.rate * Math.min(aa, c.path.tLand + 0.3)); dm.scale.setScalar(c.sz);
      }
      dm.updateMatrix(); grp.im.setMatrixAt(i, dm.matrix);
    });
    grp.im.instanceMatrix.needsUpdate = true;
  }
  void T;

  // 카메라
  const cp = cameraFor(st, t); const sh = shake(t);
  const cam = st.cam; cam.fov = cp.fov - 2.5 * Math.exp(-Math.max(0, t - T_IMPACT) / 0.25) * (t >= T_IMPACT ? 1 : 0); cam.updateProjectionMatrix();
  cam.position.set(cp.pos.x + sh[0], cp.pos.y + sh[1], cp.pos.z + sh[2]);
  cam.lookAt(cp.look.x + sh[0] * 0.25, cp.look.y + sh[1] * 0.25, cp.look.z);
  stage.key.target.position.set(cp.look.x, 0, 0); stage.key.position.set(cp.look.x - 3, 11, 7);
  renderer.setClearColor(BG, 1);
  renderer.setSize(W, H);
  renderer.render(stage.scene, cam);
  return renderer.domElement.toDataURL('image/png');
};

// ---------- 정지 그림 ----------
window.renderStill = (name) => {
  disposeStage(ST); ST = null;
  const stage = makeStage({ still: true });
  const mine = name.startsWith('mine'); const wrecked = name.endsWith('wrecked');
  const car = makeCarObj(mine ? BLUE : RED);
  for (const p of Object.values(car.parts)) stage.world.add(p.group);
  const theta = mine ? -0.5 : Math.PI + 0.5;
  const S = mine ? 1 : -1; // 카메라 쪽 옆면(로컬 z 부호)
  const m = new THREE.Matrix4();
  poseMatrix(m, 0, wrecked ? -0.02 : 0, 0, theta, wrecked ? -0.03 : 0.0, wrecked ? S * 0.06 : 0);
  const detach = new Set(wrecked ? ['bumperF', 'wheelF' + (S > 0 ? 'R' : 'L'), 'lamp' + (S > 0 ? 'R' : 'L'), 'mirror' + (S > 0 ? 'R' : 'L')] : []);
  for (const p of Object.values(car.parts)) {
    p.extra.identity(); if (p.hinge && wrecked) p.extra.makeRotationZ(0.62);
    if (detach.has(p.name)) p.group.visible = false; else p.group.matrix.copy(m).multiply(p.anchor).multiply(p.extra);
  }
  if (wrecked) {
    car.setDamage(1, S, 1); car.crackMesh.visible = true;
    // 떨어진 범퍼를 앞쪽 바닥에 눕힌다
    const b = car.parts['bumperF'];
    const dir = new THREE.Vector3(Math.cos(theta), 0, -Math.sin(theta));
    const pos = dir.clone().multiplyScalar(3.0).add(new THREE.Vector3(0, 0.17, S * 0.1 + 1.0 * (mine ? 1 : 1)));
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(0.1, theta + 0.5, 0));
    b.group.matrix.compose(pos, q, _one); b.group.visible = true;
    // 연기 몇 줄기
    const T = fxTextures();
    for (let i = 0; i < 6; i++) {
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.smoke, transparent: true, depthWrite: false, opacity: 0.55 * (1 - i / 7) }));
      const base = new THREE.Vector3(1.4, 0.95, 0).applyMatrix4(m);
      sp.position.set(base.x + Math.sin(i * 1.3) * 0.2 + i * 0.05 * (mine ? 1 : -1), base.y + i * 0.26, base.z + Math.cos(i) * 0.1);
      sp.scale.setScalar(0.6 + i * 0.16); sp.renderOrder = 8; stage.fx.add(sp);
    }
  } else car.setDamage(0, S, 1);
  car.parts.body.blob.visible = false;
  // 정지 그림은 전조등 켠 채 (멀쩡한 차) / 꺼진 채 (부서진 차)
  for (const side of ['lampL', 'lampR']) { car.parts[side].halo.visible = !wrecked; }
  car.parts.body.tailHalo.visible = false;
  renderer.setClearColor(0x000000, 0);
  renderer.setSize(800, 400);
  const cam = new THREE.PerspectiveCamera(12.5, 2, 0.1, 100);
  cam.position.set(0.0, 2.3, wrecked ? 19 : 16.5); cam.lookAt(wrecked ? (mine ? 0.9 : -0.9) : 0, 0.78, 0);
  stage.key.target.position.set(0, 0, 0); stage.key.position.set(-3, 11, 7);
  renderer.render(stage.scene, cam);
  const url = renderer.domElement.toDataURL('image/png');
  disposeStage(stage);
  return url;
};
window.__ready = false;
window.setupVariant = window.setupVariant;
window.__ready = true;
