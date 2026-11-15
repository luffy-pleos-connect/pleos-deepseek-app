// 홍보용 세로(1080x1920) 충돌 장면 — 어두운 스튜디오. render.mjs promo 가 setupVariant / renderFrame / renderStill 을 호출한다.
// 앱용 scene.js 와 섞이지 않게 따로 둔다(차 모델 car.js 만 공유). 연출 방식은 같다:
// 시간을 직접 받고(결정적), 난수는 시드 고정, 물리 엔진 없이 키프레임 + 탄도.
import * as THREE from 'three';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { ShaderPass } from 'three/addons/postprocessing/ShaderPass.js';
import { buildCar, L, WHEEL_R } from './car.js';

const BLUE = 0x0064FF, RED = 0xFE3D16;
const BGC = 0x03050b;
const W = 1080, H = 1920;
const ANCHOR_SHIFT = 150; // 화면 중심(960px)에서 시선 목표를 810px(42%)로 올리는 렌즈 이동 — 차 뒤쪽이 자막 영역(1250px 아래)에 안 들게
const G = 9.8;
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
const lerp = (a, b, t) => a + (b - a) * t;
const DEG = Math.PI / 180;
function rng(seed) {
  return () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
function kf(keys, s) { // 카트멀-롬 키프레임 보간
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

// ---------- 시간 재매핑 (슬로모션) ----------
const TAU_I = 1.35; // 충돌이 일어나는 시뮬레이션 시각
const RATES = {
  main: (u) => { // 접근 → 충돌 직전 감속 → 충돌 후 서서히 복귀
    if (u < -0.22) return 1;
    if (u < -0.04) return lerp(1, 0.16, ss(-0.22, -0.04, u));
    if (u < 0.1) return 0.16;
    if (u < 0.5) return lerp(0.16, 1, ss(0.1, 0.5, u));
    return 1;
  },
  slowmo: (u) => { // 충돌 순간만 길게
    if (u < -0.12) return 0.5;
    if (u < 0) return lerp(0.5, 0.1, ss(-0.12, 0, u));
    if (u < 0.06) return 0.1;
    if (u < 0.45) return lerp(0.1, 0.5, ss(0.06, 0.45, u));
    return 0.5;
  },
};
function makeTimeMap(rateFn, tau0) {
  const d = 0.001; const TT = [0]; let t = 0;
  for (let i = 0; i < 12000; i++) { t += d / rateFn(tau0 + i * d + d / 2 - TAU_I); TT.push(t); }
  const tauOf = (tt) => {
    let lo = 0, hi = TT.length - 1;
    if (tt >= TT[hi]) return tau0 + hi * d;
    while (hi - lo > 1) { const m = (lo + hi) >> 1; if (TT[m] <= tt) lo = m; else hi = m; }
    return tau0 + (lo + (tt - TT[lo]) / (TT[hi] - TT[lo])) * d;
  };
  const tOf = (tau) => { const x = (tau - tau0) / d; const i = Math.floor(x); return TT[i] + (TT[i + 1] - TT[i]) * (x - i); };
  return { tauOf, tOf };
}

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
  };
}

// ---------- 렌더러 / 후처리 ----------
let renderer = null, composer = null, renderPass = null, bloomPass = null, finalPass = null;
function ensureRenderer() {
  if (renderer) return renderer;
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: false });
  renderer.setPixelRatio(1);
  renderer.setSize(W, H);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.NeutralToneMapping;
  renderer.toneMappingExposure = 1.0;
  document.body.appendChild(renderer.domElement);
  return renderer;
}
const FinalShader = { // 비네팅 + 충돌 섬광 + 디더(어두운 그라데이션 줄무늬 방지)
  uniforms: { tDiffuse: { value: null }, uFlash: { value: 0 }, uSeed: { value: 0 } },
  vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }',
  fragmentShader: `uniform sampler2D tDiffuse; uniform float uFlash; uniform float uSeed; varying vec2 vUv;
    float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed) * 43758.5453); }
    void main(){
      vec4 c = texture2D(tDiffuse, vUv);
      vec2 q = (vUv - 0.5) * vec2(1.0, 0.72);
      float vg = smoothstep(0.18, 0.62, length(q));
      c.rgb *= 1.0 - 0.62 * vg;
      c.rgb += uFlash * vec3(1.0, 0.82, 0.55) * (1.0 - 0.5 * vg);
      c.rgb += (hash(gl_FragCoord.xy) + hash(gl_FragCoord.yx + 7.0) - 1.0) / 255.0 * 2.2;
      gl_FragColor = vec4(c.rgb, 1.0);
    }`,
};
function ensureComposer() {
  if (composer) return composer;
  const rt = new THREE.WebGLRenderTarget(W, H, { type: THREE.HalfFloatType, samples: 4 });
  composer = new EffectComposer(renderer, rt);
  composer.setPixelRatio(1);
  composer.setSize(W, H);
  renderPass = new RenderPass(new THREE.Scene(), new THREE.PerspectiveCamera());
  bloomPass = new UnrealBloomPass(new THREE.Vector2(W, H), 0.4, 0.12, 1.0);
  finalPass = new ShaderPass(FinalShader);
  composer.addPass(renderPass); composer.addPass(bloomPass); composer.addPass(new OutputPass()); composer.addPass(finalPass);
  return composer;
}

// 어두운 스튜디오 반사용 환경: 거의 검정 방 + 소프트박스 몇 개 (차체 하이라이트가 이것에서 나온다)
let envTex = null;
function envMap() {
  if (envTex) return envTex;
  const s = new THREE.Scene();
  s.add(new THREE.Mesh(new THREE.BoxGeometry(70, 36, 70), new THREE.MeshBasicMaterial({ color: 0x04060c, side: THREE.BackSide })));
  const panel = (w, h, pos, color, k) => {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(k), side: THREE.DoubleSide }));
    m.position.set(...pos); m.lookAt(0, 0.5, 0); s.add(m);
  };
  panel(20, 9, [0, 15, 0], 0xffffff, 3.2);          // 천장 큰 소프트박스
  panel(3, 14, [-15, 6, 3], 0xdfeaff, 5);            // 왼쪽 스트립 (차가운 흰색)
  panel(3, 14, [15, 6, 3], 0xffffff, 4);             // 오른쪽 스트립
  panel(3, 12, [-9, 5, -15], 0x3d7bff, 5);          // 뒤 왼쪽 파랑 림
  panel(3, 12, [10, 5, -15], 0xff8a3a, 5);           // 뒤 오른쪽 주황 림
  panel(16, 2.2, [0, 1.2, 17], 0xcfdcff, 2.2);       // 앞 낮은 띠 (범퍼·휠 반사)
  envTex = new THREE.PMREMGenerator(renderer).fromScene(s, 0.03).texture;
  return envTex;
}
function glowTex(stops, size = 128) {
  const k = document.createElement('canvas'); k.width = k.height = size; const g = k.getContext('2d');
  const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  for (const [o, col] of stops) gr.addColorStop(o, col);
  g.fillStyle = gr; g.fillRect(0, 0, size, size);
  const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t;
}
const FX = {};
function fxTextures() {
  if (FX.flash) return FX;
  FX.flash = glowTex([[0, 'rgba(255,255,255,1)'], [0.18, 'rgba(255,240,180,0.95)'], [0.5, 'rgba(255,160,50,0.5)'], [1, 'rgba(255,110,0,0)']]);
  FX.smoke = glowTex([[0, 'rgba(150,160,185,0.8)'], [0.5, 'rgba(120,130,155,0.42)'], [1, 'rgba(100,110,135,0)']]);
  FX.haze = glowTex([[0, 'rgba(90,130,230,0.5)'], [0.5, 'rgba(70,100,190,0.2)'], [1, 'rgba(50,80,160,0)']], 256);
  FX.dust = glowTex([[0, 'rgba(255,255,255,1)'], [0.35, 'rgba(210,225,255,0.5)'], [1, 'rgba(200,215,255,0)']], 64);
  FX.ring = (() => { const k = document.createElement('canvas'); k.width = k.height = 256; const g = k.getContext('2d');
    const gr = g.createRadialGradient(128, 128, 60, 128, 128, 128);
    gr.addColorStop(0, 'rgba(255,170,40,0)'); gr.addColorStop(0.7, 'rgba(255,190,70,0.7)'); gr.addColorStop(0.85, 'rgba(255,210,120,0.35)'); gr.addColorStop(1, 'rgba(255,200,90,0)');
    g.fillStyle = gr; g.fillRect(0, 0, 256, 256); const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t; })();
  FX.head = glowTex([[0, 'rgba(255,255,255,1)'], [0.2, 'rgba(235,242,255,0.9)'], [0.5, 'rgba(170,200,255,0.4)'], [1, 'rgba(140,180,255,0)']]);
  FX.pool = (() => { const k = document.createElement('canvas'); k.width = 512; k.height = 256; const g = k.getContext('2d');
    g.filter = 'blur(14px)';
    const lg = g.createLinearGradient(0, 0, 512, 0);
    lg.addColorStop(0, 'rgba(215,230,255,0.9)'); lg.addColorStop(0.45, 'rgba(190,212,255,0.4)'); lg.addColorStop(1, 'rgba(170,200,255,0)');
    g.fillStyle = lg; g.beginPath(); g.moveTo(0, 100); g.lineTo(512, 30); g.lineTo(512, 226); g.lineTo(0, 156); g.closePath(); g.fill();
    const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t; })();
  FX.floor = (() => { const k = document.createElement('canvas'); k.width = k.height = 1024; const g = k.getContext('2d');
    const gr = g.createRadialGradient(512, 512, 0, 512, 512, 512);
    gr.addColorStop(0, 'rgba(22,42,86,0.70)'); gr.addColorStop(0.18, 'rgba(14,28,62,0.76)'); gr.addColorStop(0.45, 'rgba(7,14,32,0.9)'); gr.addColorStop(0.75, 'rgba(3,6,14,1)'); gr.addColorStop(1, 'rgba(3,5,11,1)');
    g.fillStyle = gr; g.fillRect(0, 0, 1024, 1024); const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t; })();
  return FX;
}

function makeStage({ still = false } = {}) {
  ensureRenderer();
  const T = fxTextures();
  const scene = new THREE.Scene();
  scene.environment = envMap();
  scene.environmentIntensity = still ? 1.1 : 1.0;
  scene.background = still ? null : new THREE.Color(BGC);
  const world = new THREE.Group(); scene.add(world);
  const fx = new THREE.Group(); scene.add(fx);
  scene.add(new THREE.HemisphereLight(0x5a7cd0, 0x05070d, still ? 0.35 : 0.3));
  const key = new THREE.DirectionalLight(0xfff3e4, still ? 1.7 : 1.25);
  key.position.set(-3, 12, 9); key.target.position.set(1.5, 0, 0);
  key.castShadow = still; // 영상은 그림자 판 없이(어두운 바닥에선 거의 안 보이고, 영역 경계가 줄무늬로 비친다) — 접지감은 반사와 차 밑 블롭이 맡는다
  key.shadow.mapSize.set(2048, 2048);
  const sc = key.shadow.camera; sc.left = -14; sc.right = 14; sc.top = 10; sc.bottom = -10; sc.near = 1; sc.far = 40;
  key.shadow.bias = -0.0004; key.shadow.normalBias = 0.03;
  scene.add(key, key.target);
  const rimB = new THREE.DirectionalLight(0x3d7bff, still ? 4.5 : 2.6); rimB.position.set(-9, 4, -9); scene.add(rimB);
  const rimO = new THREE.DirectionalLight(0xff8a3a, still ? 4.0 : 2.0); rimO.position.set(10, 3.5, -9); scene.add(rimO);
  const kick = new THREE.DirectionalLight(0xbcd4ff, still ? 2.2 : 0.9); kick.position.set(12, 2.5, 4); scene.add(kick);
  const flashLight = new THREE.PointLight(0xffb060, 0, 14, 1.6); flashLight.position.set(0, 0.8, 0.5); scene.add(flashLight);

  if (still) {
    const shadowPlane = new THREE.Mesh(new THREE.PlaneGeometry(80, 40), new THREE.ShadowMaterial({ opacity: 0.5 }));
    shadowPlane.rotation.x = -Math.PI / 2; shadowPlane.position.y = 0.003; shadowPlane.receiveShadow = true; shadowPlane.renderOrder = 2;
    scene.add(shadowPlane);
  }
  let floor = null, haze = [], dust = null;
  if (!still) {
    floor = new THREE.Mesh(new THREE.PlaneGeometry(56, 56), new THREE.MeshBasicMaterial({ map: T.floor, transparent: true, toneMapped: true, depthWrite: false }));
    floor.rotation.x = -Math.PI / 2; floor.position.set(1.0, 0, 0); floor.renderOrder = 1;
    scene.add(floor);
    const r = rng(99);
    for (let i = 0; i < 9; i++) { // 낮게 깔린 안개 덩어리
      const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.haze, transparent: true, depthWrite: false, opacity: 0.12 + r() * 0.08 }));
      const base = new THREE.Vector3((r() - 0.5) * 22, 0.4 + r() * 1.6, (r() - 0.5) * 16);
      sp.scale.setScalar(7 + r() * 8); sp.renderOrder = 9; scene.add(sp);
      haze.push({ sp, base, ph: r() * 6, sp2: 0.2 + r() * 0.25 });
    }
    const n = 140, pos = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) { pos[i * 3] = (r() - 0.5) * 26; pos[i * 3 + 1] = r() * 7; pos[i * 3 + 2] = (r() - 0.5) * 20; }
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    dust = new THREE.Points(g, new THREE.PointsMaterial({ map: T.dust, size: 0.11, sizeAttenuation: true, transparent: true, opacity: 0.55, depthWrite: false, blending: THREE.AdditiveBlending, color: 0x9db8ff }));
    dust.frustumCulled = false; dust.userData.base = pos.slice(); scene.add(dust);
  }
  return { scene, world, fx, key, floor, haze, dust, flashLight, still };
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

// ---------- 차 ----------
const _q = new THREE.Quaternion(), _e = new THREE.Euler(), _v = new THREE.Vector3(), _one = new THREE.Vector3(1, 1, 1);
function poseMatrix(out, x, y, z, theta, pitch, roll) {
  _e.set(roll, theta, pitch, 'YZX'); // 월드 = Ry(theta) * Rz(pitch) * Rx(roll)
  _q.setFromEuler(_e);
  return out.compose(_v.set(x, y, z), _q, _one);
}
function polishCar(car) { // 홍보용: 클리어코트·유리 반사를 조금 더 진하게
  const seen = new Set();
  for (const p of Object.values(car.parts)) p.group.traverse((o) => {
    const m = o.material; if (!o.isMesh || !m || seen.has(m)) return; seen.add(m);
    if (m.isMeshPhysicalMaterial && m.clearcoat > 0.5 && m.vertexColors !== false && m.color.getHex() !== 0x0d1724) {
      m.metalness = 0.4; m.roughness = 0.26; m.clearcoat = 1; m.clearcoatRoughness = 0.07; m.envMap = envMap(); m.envMapIntensity = 0.85;
    } else if (m.isMeshPhysicalMaterial) { m.envMap = envMap(); m.envMapIntensity = 1.6; m.roughness = 0.07; }
    else if (m === car.M.tire) { m.roughness = 0.7; m.envMap = envMap(); m.envMapIntensity = 0.5; }
    else if (m === car.M.rim) { m.envMap = envMap(); m.envMapIntensity = 1.2; }
  });
}
function makeCarObj(hex) {
  const car = buildCar(hex);
  polishCar(car);
  for (const p of Object.values(car.parts)) p.group.visible = true;
  for (const side of ['lampL', 'lampR']) car.parts[side].halo.material.map = fxTextures().head;
  const pl = car.parts.body.pool; // 헤드램프가 비추는 바닥 빛 웅덩이: 더하기 합성, 차가운 흰색
  pl.material.map = fxTextures().pool; pl.material.blending = THREE.AdditiveBlending; pl.material.color.set(0xffffff); pl.material.opacity = 0; pl.material.needsUpdate = true;
  return car;
}

// ---------- 장면 ----------
let ST = null;
function disposeStage(st) { if (!st) return; st.stage.scene.traverse((o) => { if (o.geometry) o.geometry.dispose(); }); }

const D_APPROACH = 4.8;
function approach(tau) { const u = clamp(tau / TAU_I, 0, 1); return D_APPROACH * (1 - Math.pow(u, 1.35)); }
const DL = 3.0, KL = 2.4;
const dl = (s) => DL * (1 - Math.exp(-KL * Math.max(0, s)));
const cW = (s) => ss(0, 0.22, s);

// d-프레임: 이긴 차 'W' 는 -x 에서 +x 로, 진 차 'L' 은 +x 에서 -x 로 달려든다(월드와 같은 좌표).
function poseOf(role, tau) {
  const s = tau - TAU_I;
  const P = { x: 0, y: 0, z: 0, h: 0, pitch: 0, roll: 0, c: 0, spin: 0, hood: 0, scorch: 1 };
  if (role === 'W') {
    if (s < 0) P.x = -L - approach(tau);
    else {
      const dw = s < 0.22 ? dl(s) + 0.7 * cW(s) : kf([[0.22, dl(0.22) + 0.7], [0.5, 2.0], [0.95, 1.5], [1.55, 1.8], [2.3, 1.7], [3.5, 1.7]], s);
      P.x = -L + dw;
    }
    P.c = 0.07 * cW(s); P.scorch = 0.3;
    P.pitch = s < 0 ? -0.012 * ss(-0.4, 0, s) : kf([[0, 0], [0.08, -0.03], [0.4, 0.022], [0.9, -0.012], [1.6, 0.006], [2.4, 0]], s);
    P.y = s < 0 ? 0 : kf([[0, 0], [0.12, 0.03], [0.4, 0], [0.8, 0.015], [1.2, 0]], s);
    P.spin = -(P.x + L + D_APPROACH) / WHEEL_R;
    P.h = s < 0 ? 0 : kf([[0, 0], [0.4, 0.015], [1.2, -0.006], [2.2, 0]], s);
  } else {
    if (s < 0) P.x = L + approach(tau);
    else { P.x = L + dl(s); P.z = -0.7 * (1 - Math.exp(-2.2 * s)); }
    const k = 3.2;
    P.h = Math.PI + (s < 0 ? 0 : 1.05 * (1 - (1 + k * s) * Math.exp(-k * s)));
    P.c = cW(s); P.scorch = 1;
    P.pitch = s < 0 ? 0 : kf([[0, 0], [0.1, 0.09], [0.35, 0.05], [0.8, -0.04], [1.3, -0.02], [2.2, -0.025]], s);
    P.roll = s < 0 ? 0 : -kf([[0, 0], [0.12, -0.03], [0.5, 0.05], [1.2, 0.065], [2.2, 0.065]], s); // S=-1
    P.y = s < 0 ? 0 : kf([[0, 0], [0.12, 0.16], [0.4, 0], [0.62, 0.06], [0.85, 0], [1.2, -0.03], [2.2, -0.03]], s);
    P.hood = s < 0 ? 0 : kf([[0, 0], [0.06, 0.1], [0.25, 0.7], [0.5, 0.58], [0.9, 0.66], [1.5, 0.64]], s);
    P.spin = -((L + D_APPROACH) - P.x) / WHEEL_R;
  }
  return P;
}
function carMatrixOf(role, tau, out) {
  const P = poseOf(role, tau);
  poseMatrix(out, P.x, P.y, P.z, P.h, P.pitch, P.roll);
  return P;
}
const S = -1; // 진 차의 망가지는 쪽(로컬 z 부호) = 카메라 쪽 옆면

function buildFx(st, big) {
  const { stage } = st;
  const T = fxTextures();
  const rnd = rng(4242 + (st.winner === 'red' ? 17 : 0));
  st.fx = {};
  const flash = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.flash, transparent: true, depthWrite: false, depthTest: false, blending: THREE.AdditiveBlending }));
  flash.renderOrder = 20; stage.fx.add(flash); st.fx.flash = flash;
  const ring = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: T.ring, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
  ring.rotation.x = -Math.PI / 2; ring.renderOrder = 5; stage.fx.add(ring); st.fx.ring = ring;

  // 불꽃 (HDR 색 → 블룸으로 번짐)
  const NS = big ? 520 : 300;
  const sparks = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial({ toneMapped: false }), NS);
  sparks.frustumCulled = false; sparks.renderOrder = 6; sparks.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(NS * 3), 3);
  stage.fx.add(sparks);
  st.fx.sparks = [];
  for (let i = 0; i < NS; i++) {
    const sd = rnd() * 0.3;
    const sp = 3 + rnd() * 8;
    const ang = rnd() * Math.PI * 2, el = 0.15 + rnd() * 1.0;
    const dir = new THREE.Vector3(Math.cos(ang) * Math.cos(el) * (0.5 + rnd() * 0.8), Math.sin(el) * 0.9, Math.sin(ang) * Math.cos(el) * 1.3);
    const origin = new THREE.Vector3(0, 0.45 + rnd() * 0.35, (rnd() - 0.5) * 1.3);
    st.fx.sparks.push({ sd, life: 0.35 + rnd() * 0.85, path: makePath(origin, dir.multiplyScalar(sp), { restY: 0.02, e: 0.3, k: 4 }), w: (big ? 0.012 : 0.02) + rnd() * (big ? 0.014 : 0.02), hot: 1.6 + rnd() * 1.6 });
  }
  st.fx.sparkMesh = sparks;

  // 연기
  st.fx.smokes = [];
  for (let i = 0; i < 44; i++) {
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.smoke, transparent: true, depthWrite: false, opacity: 0 }));
    sp.renderOrder = 8; stage.fx.add(sp);
    st.fx.smokes.push({ sp, ts: 0.12 + i * 0.075, local: new THREE.Vector3(1.4 + (rnd() - 0.5) * 0.5, 0.85, (rnd() - 0.5) * 0.6), life: 2.0 + rnd() * 0.6, vy: 0.7 + rnd() * 0.6, drift: new THREE.Vector3((rnd() - 0.5) * 0.5 + 0.3, 0, (rnd() - 0.5) * 0.4), sz: 1.2 * (0.7 + rnd() * 0.5), ph: rnd() * 6 });
  }

  // 파편(진 차) — 부품 탄도
  st.fx.debris = [];
  const specs = [
    { name: 'bumperF', s: 0.07, v: [2.6, 3.0, 1.8], w: [1.5, 3.0, 5.5], rest: 0.17, thin: 'y' },
    { name: 'wheelFL', s: 0.1, v: [2.2, 2.6, 2.6], wheel: true, rest: WHEEL_R },
    { name: 'doorFL', s: 0.12, v: [1.4, 3.0, 2.4], w: [4, -6, 7], rest: 0.04, thin: 'z' },
    { name: 'mirrorL', s: 0.04, v: [1.0, 2.4, 2.8], w: [9, 5, 11], rest: 0.06, thin: 'y' },
    { name: 'lampL', s: 0.05, v: [2.4, 2.6, 1.8], w: [7, -5, 4], rest: 0.05, thin: 'y' },
  ];
  const qAt = (deb, s) => { const ang = deb.w.length() * s; return new THREE.Quaternion().setFromAxisAngle(deb.w.clone().normalize(), ang).multiply(deb.q0); };
  for (const sp of specs) {
    const tauSep = TAU_I + sp.s;
    const m = new THREE.Matrix4(); carMatrixOf('L', tauSep, m);
    const part = st.B.parts[sp.name];
    const wm = m.clone().multiply(part.anchor);
    const pos = new THREE.Vector3().setFromMatrixPosition(wm);
    const quat = new THREE.Quaternion().setFromRotationMatrix(wm);
    const v0 = new THREE.Vector3(sp.v[0], sp.v[1], sp.v[2]).add(new THREE.Vector3(1.2, 0, -0.3));
    const path = makePath(pos, v0, { restY: sp.rest, e: sp.wheel ? 0.3 : 0.36, k: sp.wheel ? 1.8 : 3.0, fr: 0.8 });
    const deb = { part, sp, tauSep, path, q0: quat, pos0: pos };
    if (!sp.wheel) {
      deb.w = new THREE.Vector3(...sp.w);
      const a = { x: new THREE.Vector3(1, 0, 0), y: new THREE.Vector3(0, 1, 0), z: new THREE.Vector3(0, 0, 1) }[sp.thin];
      const wq = qAt(deb, path.tLand);
      const wa = a.clone().applyQuaternion(wq);
      deb.qFlat = new THREE.Quaternion().setFromUnitVectors(wa.normalize(), new THREE.Vector3(0, wa.y >= 0 ? 1 : -1, 0)).multiply(wq);
      deb.qLand = wq;
    } else {
      const hv = new THREE.Vector3(v0.x, 0, v0.z).normalize();
      deb.phi = Math.atan2(-hv.z, hv.x);
      deb.tEnd = path.tRest;
    }
    st.fx.debris.push(deb);
  }
  st.fx.qAt = qAt;

  // 유리 파편 + 도장 조각
  const dummy = new THREE.Object3D(); st.fx.dummy = dummy;
  const mkChips = (n, geom, mat, spawn) => {
    const im = new THREE.InstancedMesh(geom, mat, n); im.frustumCulled = false; im.castShadow = true; stage.fx.add(im);
    const arr = []; for (let i = 0; i < n; i++) arr.push(spawn(i));
    return { im, arr };
  };
  const spawnChip = (kind) => () => {
    const tauS = TAU_I + 0.02 + rnd() * 0.16;
    const m = new THREE.Matrix4(); carMatrixOf('L', tauS, m);
    const local = kind === 'glass' ? new THREE.Vector3(0.2 + rnd() * 0.4, 1.35 + rnd() * 0.25, (rnd() - 0.5) * 1.0) : new THREE.Vector3(1.6 + rnd() * 0.6, 0.45 + rnd() * 0.4, (rnd() - 0.5) * 1.6);
    const pos = local.applyMatrix4(m);
    const v = new THREE.Vector3((1 + rnd() * 5) * (kind === 'glass' ? 0.9 : 1), 1.5 + rnd() * 4.5, (rnd() - 0.2) * 5);
    return { tauS, path: makePath(pos, v, { restY: 0.02, e: 0.32, k: 3.5 }), axis: new THREE.Vector3(rnd() - 0.5, rnd() - 0.5, rnd() - 0.5).normalize(), rate: 6 + rnd() * 12, sz: kind === 'glass' ? 0.06 + rnd() * 0.1 : 0.035 + rnd() * 0.06 };
  };
  const shardGeom = new THREE.BufferGeometry();
  shardGeom.setAttribute('position', new THREE.BufferAttribute(new Float32Array([0, 0, 0, 1, 0.1, 0, 0.35, 0.9, 0]), 3)); shardGeom.setIndex([0, 1, 2]); shardGeom.computeVertexNormals();
  const glassMat = new THREE.MeshPhysicalMaterial({ color: 0xa8d4ff, roughness: 0.05, metalness: 0.2, clearcoat: 1, side: THREE.DoubleSide, transparent: true, opacity: 0.9, envMapIntensity: 3, emissive: 0x1a3a66, emissiveIntensity: 0.6 });
  st.fx.chips = [];
  st.fx.chips.push(mkChips(big ? 120 : 60, shardGeom, glassMat, spawnChip('glass')));
  st.fx.chips.push(mkChips(big ? 70 : 40, new THREE.BoxGeometry(1, 0.4, 0.7), new THREE.MeshStandardMaterial({ color: st.B.M.paint.color, roughness: 0.35, metalness: 0.3, envMapIntensity: 1.5 }), spawnChip('paint')));
  st.cam = new THREE.PerspectiveCamera(30, W / H, 0.1, 300);
}
const _tmpV = new THREE.Vector3();
function debrisMatrix(st, deb, tau, out) {
  const s = tau - deb.tauSep;
  const p = deb.path.at(s, _tmpV.set(0, 0, 0));
  let q;
  if (!deb.sp.wheel) {
    const tl = deb.path.tLand;
    q = s <= tl ? st.fx.qAt(deb, s) : deb.qLand.clone().slerp(deb.qFlat, ss(0, 0.45, s - tl));
  } else {
    const tl = deb.path.tLand;
    const dist = Math.hypot(p.x - deb.pos0.x, p.z - deb.pos0.z);
    const spin = -dist / WHEEL_R;
    const tilt = (Math.PI / 2) * ss(deb.tEnd - 0.55, deb.tEnd, s);
    const qRoll = new THREE.Quaternion().setFromEuler(new THREE.Euler(tilt, deb.phi, 0, 'YXZ'));
    qRoll.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), spin));
    q = s < tl ? deb.q0.clone().slerp(qRoll, ss(0, tl, s)) : qRoll;
    p.y = WHEEL_R * Math.cos(tilt) + 0.13 * Math.sin(tilt) * (s >= tl ? 1 : 0) + (s < tl ? p.y - WHEEL_R : 0);
    if (s >= tl) p.y = Math.max(p.y, WHEEL_R * Math.cos(tilt) + 0.13 * Math.sin(tilt));
  }
  return out.compose(p, q, _one);
}

// ---------- 시나리오별 설정 ----------
// 카메라 키: [t(초), {tx,ty,tz: 시선 목표, az: +x 에서 +z 쪽으로 잰 방위각(도), el: 고도각(도), R: 거리, fov}]
function camKeysFor(mode, tI, tHold) {
  if (mode === 'slowmo') {
    const e = tHold;
    return [
      [0, { tx: 0.0, ty: 0.75, tz: -0.3, az: 84, el: 12, R: 11.5, fov: 34 }],
      [tI, { tx: 0.3, ty: 0.75, tz: -0.3, az: 90, el: 13, R: 10.8, fov: 34 }],
      [tI + 1.0, { tx: 1.5, ty: 0.8, tz: -0.4, az: 100, el: 14, R: 11.0, fov: 34 }],
      [e, { tx: 2.3, ty: 0.85, tz: -0.4, az: 108, el: 16, R: 11.5, fov: 34 }],
    ];
  }
  return [
    [0, { tx: 0.0, ty: 0.5, tz: 0.0, az: 40, el: 48, R: 62, fov: 30 }],
    [tI - 0.6, { tx: 0.1, ty: 0.5, tz: -0.1, az: 44, el: 47, R: 41, fov: 30 }],
    [tI, { tx: 0.3, ty: 0.5, tz: -0.3, az: 46, el: 46, R: 29.5, fov: 30 }],
    [tI + 0.9, { tx: 1.6, ty: 0.5, tz: -0.4, az: 48, el: 45, R: 31, fov: 30 }],
    [tI + 2.2, { tx: 2.5, ty: 0.5, tz: -0.5, az: 52, el: 44, R: 36, fov: 30 }],
    [tHold - 0.2, { tx: 2.6, ty: 0.5, tz: -0.5, az: 52, el: 44, R: 36.5, fov: 30 }],
  ];
}
const FIELDS = ['tx', 'ty', 'tz', 'az', 'el', 'R', 'fov'];
function camAt(keys, t) { const o = {}; for (const f of FIELDS) o[f] = kf(keys.map((k) => [k[0], k[1][f]]), t); return o; }

window.setupVariant = (variant) => {
  disposeStage(ST);
  const mode = variant === 'slowmo' ? 'slowmo' : 'main';
  const winner = variant === 'red_wins' ? 'red' : 'blue';
  const stage = makeStage();
  const A = makeCarObj(winner === 'red' ? RED : BLUE), B = makeCarObj(winner === 'red' ? BLUE : RED);
  for (const car of [A, B]) for (const p of Object.values(car.parts)) stage.world.add(p.group);
  addMirror(stage);
  const tau0 = mode === 'slowmo' ? TAU_I - 0.3 : 0;
  const tm = makeTimeMap(RATES[mode], tau0);
  const tImpact = tm.tOf(TAU_I);
  const holdFrom = mode === 'slowmo' ? 1e9 : 5.3;
  const tauEnd = mode === 'slowmo' ? 1e9 : tm.tauOf(holdFrom);
  ST = { variant, mode, winner, stage, A, B, tm, tImpact, holdFrom, tauEnd, keys: camKeysFor(mode, tImpact, mode === 'slowmo' ? 3.0 : holdFrom) };
  buildFx(ST, mode === 'slowmo');
  ensureComposer();
  window.__ST = ST;
  window.__info = { tImpact, tauEnd, tauAt: (t) => tm.tauOf(t), tEndSlow: mode === 'slowmo' ? tm.tOf(TAU_I + 0.55) : null };
  window.__ready = true;
};

// ---------- 프레임 ----------
function placeCar(car, role, tau, detached) {
  const m = new THREE.Matrix4();
  const P = carMatrixOf(role, tau, m);
  for (const p of Object.values(car.parts)) {
    p.extra.identity();
    if (p.hinge) p.extra.makeRotationZ(P.hood);
    if (p.wheel) p.spin.rotation.z = P.spin;
    const deb = detached && detached.get(p.name);
    if (deb && tau >= deb.tauSep) { debrisMatrix(ST, deb, tau, p.group.matrix); p.group.visible = true; if (p.seamMesh) p.seamMesh.visible = false; }
    else p.group.matrix.copy(m).multiply(p.anchor).multiply(p.extra);
  }
  car.setDamage(P.c, role === 'L' ? S : 1, P.scorch);
  return { P, m };
}

const BOX = [-L, L].flatMap((x) => [0, 1.7].flatMap((y) => [-0.95, 0.95].map((z) => new THREE.Vector3(x, y, z))));
function projectPx(v, cam) { const p = v.clone().project(cam); return [(p.x * 0.5 + 0.5) * W, (1 - (p.y * 0.5 + 0.5)) * H]; }
function boxPx(m, cam) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const c of BOX) { const [x, y] = projectPx(c.clone().applyMatrix4(m), cam); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  return { x0: Math.round(x0), y0: Math.round(y0), x1: Math.round(x1), y1: Math.round(y1) };
}

let LAST = null;
window.renderFrame = (t) => {
  const st = ST; const stage = st.stage;
  const tc = Math.min(t, st.holdFrom); // 최종 구도 정지: 시간에 따라 변하는 건 전부 tc 로
  const tau = Math.min(st.tm.tauOf(t), st.tauEnd);
  const s = tau - TAU_I;
  const detached = new Map(); for (const deb of st.fx.debris) detached.set(deb.part.name, deb);
  const WP = placeCar(st.A, 'W', tau);
  const LPo = placeCar(st.B, 'L', tau, detached);
  st.B.crackMesh.visible = s > 0.02;

  // 전조등: 이긴 차는 충돌 뒤 두 번 깜빡, 진 차는 깜빡이다 꺼진다
  const lampState = (car, role) => {
    let head = 1, pulse = 1;
    if (role === 'L') head = s < 0.1 ? 1 : (s < 0.9 ? (Math.sin(s * 70) > -0.3 ? 0.8 : 0.1) : 0.0);
    else {
      const ph = (s - 0.85) / 0.3; // 0.3초 주기 × 2번: 꺼졌다 켜질 때마다 번쩍
      if (ph >= 0 && ph < 2) { const f = ph % 1; head = f < 0.4 ? 0 : 1; pulse = f < 0.4 ? 0.8 : 1 + 1.6 * (1 - ss(0.4, 0.8, f)); }
    }
    for (const side of ['lampL', 'lampR']) {
      const p = car.parts[side]; p.halo.userData.noMirror = true; p.halo.material.opacity = 0.7 * head; p.halo.scale.setScalar(0.5 * pulse * (0.6 + 0.4 * head));
      p.led.material.color.setScalar(head > 0.05 ? 0.8 + 0.9 * head : 0.3); // >1: 블룸이 번지게
    }
    car.parts.bar.group.children[0].material.color.setScalar(head > 0.05 ? 0.8 + 0.8 * head : 0.3);
    car.parts.body.tailHalo.material.opacity = s > -0.12 ? 0.4 : 0.2; car.parts.body.tailHalo.scale.set(0.6, 0.2, 1);
    const pool = car.parts.body.pool; pool.visible = head > 0.05; pool.material.opacity = (st.mode === 'slowmo' ? 0.1 : 0.45) * head;
  };
  lampState(st.A, 'W'); lampState(st.B, 'L');
  syncMirrorTop(stage);

  // 섬광 / 충격파 / 충돌 지점 조명
  const sU = st.tm.tauOf(t) - TAU_I;
  const contactX = dl(Math.max(0, s)) + 0.7 * cW(Math.max(0, s));
  const f = st.fx.flash;
  const fa = sU < 0 ? 0 : (1 - ss(0.04, 0.3, sU)) * (0.25 + 0.75 * ss(0, 0.03, sU));
  f.position.set(contactX, 0.7, 0.9); f.material.opacity = 0.8 * fa; f.visible = fa > 0.01;
  f.scale.setScalar(lerp(1.0, st.mode === 'slowmo' ? 3.0 : 3.6, ss(0, 0.2, sU)));
  const rg = st.fx.ring; rg.position.set(contactX, 0.02, 0);
  const ra = sU < 0 ? 0 : 1 - ss(0.05, 0.7, sU); rg.material.opacity = ra; rg.visible = ra > 0.01;
  const rs = lerp(0.5, 12, ss(0, 0.7, sU)); rg.scale.set(rs, rs * 0.6, 1);
  stage.flashLight.position.set(contactX, 1.6, 1.4);
  stage.flashLight.intensity = sU < 0 ? 0 : 14 * Math.exp(-sU / 0.16) * ss(0, 0.012, sU);

  // 불꽃
  const dm = st.fx.dummy; const sm = st.fx.sparkMesh; const col = new THREE.Color();
  const sparkScale = st.mode === 'slowmo' ? 1 : 1.35;
  st.fx.sparks.forEach((sk, i) => {
    const a = sU - sk.sd;
    if (a < 0 || a > sk.life) { dm.scale.set(0, 0, 0); dm.position.set(0, -9, 0); dm.updateMatrix(); sm.setMatrixAt(i, dm.matrix); return; }
    const p = sk.path.at(a, _tmpV.set(0, 0, 0));
    const p1 = sk.path.at(a + 0.012, new THREE.Vector3());
    const dir = p1.clone().sub(p); const len = Math.max(dir.length(), 1e-4); dir.divideScalar(len);
    dm.position.set(p.x + contactX, p.y, p.z);
    dm.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir);
    const life = a / sk.life; const L0 = Math.min(0.6, 0.05 + len * 22) * (1 - life * 0.7);
    dm.scale.set(sk.w * sparkScale * (1 - life * 0.6), sk.w * sparkScale * (1 - life * 0.6), L0);
    dm.updateMatrix(); sm.setMatrixAt(i, dm.matrix);
    const h = sk.hot * (1 - 0.6 * ss(0, 0.8, life));
    col.setRGB(h, h * lerp(0.82, 0.32, ss(0, 0.7, life)), h * lerp(0.4, 0.02, ss(0, 0.4, life))); sm.setColorAt(i, col);
  });
  sm.instanceMatrix.needsUpdate = true; if (sm.instanceColor) sm.instanceColor.needsUpdate = true;

  // 연기 (정지 구간에서는 tau 가 멈추므로 같이 멈춘다)
  for (const sm2 of st.fx.smokes) {
    const age = tau - TAU_I - sm2.ts;
    if (age < 0 || age > sm2.life) { sm2.sp.visible = false; continue; }
    const m = new THREE.Matrix4(); carMatrixOf('L', TAU_I + Math.min(sm2.ts, st.tauEnd - TAU_I), m);
    const p0 = sm2.local.clone().applyMatrix4(m);
    const u = age / sm2.life;
    sm2.sp.position.set(p0.x + sm2.drift.x * age + Math.sin(age * 1.7 + sm2.ph) * 0.12, p0.y + sm2.vy * age * (1 - 0.3 * u), p0.z + sm2.drift.z * age);
    sm2.sp.scale.setScalar(sm2.sz * lerp(0.5, 2.1, Math.sqrt(u)));
    sm2.sp.material.opacity = 0.4 * ss(0, 0.12, u) * (1 - ss(0.35, 1, u));
    sm2.sp.visible = true;
  }

  // 유리/도장 조각
  for (const grp of st.fx.chips) {
    grp.arr.forEach((c, i) => {
      const a = tau - c.tauS;
      if (a < 0) { dm.scale.set(0, 0, 0); dm.position.set(0, -9, 0); }
      else {
        const aa = Math.min(a, st.tauEnd - c.tauS);
        const p = c.path.at(aa, _tmpV.set(0, 0, 0));
        dm.position.copy(p); dm.quaternion.setFromAxisAngle(c.axis, c.rate * Math.min(aa, c.path.tLand + 0.3)); dm.scale.setScalar(c.sz);
      }
      dm.updateMatrix(); grp.im.setMatrixAt(i, dm.matrix);
    });
    grp.im.instanceMatrix.needsUpdate = true;
  }

  // 안개·먼지: 천천히 떠다닌다
  for (const h of stage.haze) h.sp.position.set(h.base.x + Math.sin(tc * h.sp2 + h.ph) * 1.6, h.base.y, h.base.z + Math.cos(tc * h.sp2 * 0.8 + h.ph) * 1.2);
  { const base = stage.dust.userData.base, pos = stage.dust.geometry.attributes.position;
    for (let i = 0; i < pos.count; i++) pos.setXYZ(i, base[i * 3] + Math.sin(tc * 0.3 + i) * 0.35, base[i * 3 + 1] + Math.sin(tc * 0.4 + i * 1.7) * 0.25, base[i * 3 + 2] + Math.cos(tc * 0.25 + i * 0.7) * 0.3);
    pos.needsUpdate = true; }

  // 카메라
  const cp = camAt(st.keys, t);
  const tI = st.tImpact;
  const amp = (t < tI ? 0.0025 * ss(tI - 1.2, tI, t) : (st.mode === 'slowmo' ? 0.034 : 0.05) * Math.exp(-(t - tI) / (st.mode === 'slowmo' ? 0.9 : 0.33))) * cp.R;
  const sh = [amp * (Math.sin(t * 47.1) + 0.6 * Math.sin(t * 91.3 + 1)), amp * (Math.sin(t * 53.7 + 2) + 0.5 * Math.sin(t * 77 + 3)), amp * 0.6 * Math.sin(t * 61 + 5)];
  const el = cp.el * DEG, az = cp.az * DEG;
  const cam = st.cam;
  cam.fov = cp.fov - (st.mode === 'slowmo' ? 0 : 2.2 * Math.exp(-Math.max(0, t - tI) / 0.25) * (t >= tI ? 1 : 0));
  cam.setViewOffset(W, H, 0, ANCHOR_SHIFT, W, H);
  cam.updateProjectionMatrix();
  cam.position.set(cp.tx + cp.R * Math.cos(el) * Math.cos(az) + sh[0], cp.ty + cp.R * Math.sin(el) + sh[1], cp.tz + cp.R * Math.cos(el) * Math.sin(az) + sh[2]);
  cam.lookAt(cp.tx + sh[0] * 0.25, cp.ty + sh[1] * 0.25, cp.tz);
  stage.key.target.position.set(cp.tx, 0, 0); stage.key.position.set(cp.tx - 3, 12, 9);

  const flashScreen = sU < 0 ? 0 : 0.2 * Math.exp(-sU / 0.05) * (st.mode === 'slowmo' ? 0.6 : 1);
  finalPass.uniforms.uFlash.value = flashScreen;
  finalPass.uniforms.uSeed.value = (Math.round(t * 30) % 97) * 1.37;
  bloomPass.strength = 0.32 + 0.12 * Math.exp(-Math.max(0, sU) / 0.2) * (sU >= 0 ? 1 : 0);
  const off = window.__off || {}; // 디버그용: 요소 끄기
  if (stage.floor) stage.floor.visible = !off.floor;
  if (stage.mirror) stage.mirror.visible = !off.mirror;
  stage.scene.traverse((o) => { if (o.material && o.material.type === 'ShadowMaterial') o.visible = !off.shadow; });
  for (const h of stage.haze) h.sp.visible = !off.haze;
  bloomPass.enabled = !off.bloom;
  renderPass.scene = stage.scene; renderPass.camera = cam;
  composer.render();
  cam.updateMatrixWorld();
  LAST = { cam, WPm: WP.m.clone(), LPm: LPo.m.clone(), contactX };
  return renderer.domElement.toDataURL('image/png');
};

// 확인용: 마지막으로 그린 프레임에서 두 차의 박스·충돌 지점이 화면 몇 px 에 있는지
window.probe = () => ({
  W: boxPx(LAST.WPm, LAST.cam), L: boxPx(LAST.LPm, LAST.cam),
  contact: projectPx(new THREE.Vector3(LAST.contactX, 0.6, 0.0), LAST.cam).map(Math.round),
});

// ---------- 정지 그림 (배경 투명, 1080 폭, 3/4 앞모습) ----------
window.renderStill = (name) => {
  disposeStage(ST); ST = null;
  const stage = makeStage({ still: true });
  const blue = name.includes('blue');
  const car = makeCarObj(blue ? BLUE : RED);
  for (const p of Object.values(car.parts)) stage.world.add(p.group);
  const phi = 38 * DEG; // 코가 카메라 축에서 38° 비껴 있다. 파랑은 오른쪽, 빨강은 왼쪽을 본다.
  const m = new THREE.Matrix4();
  poseMatrix(m, 0, 0, 0, blue ? -(Math.PI / 2 - phi) : Math.PI + (Math.PI / 2 - phi), 0, 0);
  for (const p of Object.values(car.parts)) { p.extra.identity(); p.group.matrix.copy(m).multiply(p.anchor).multiply(p.extra); }
  car.setDamage(0, S, 1);
  const seen = new Set(); // 정지 그림은 어두운 배경에 올릴 것이라 차체를 더 밝게 받쳐 준다
  for (const p of Object.values(car.parts)) p.group.traverse((o) => { const mt = o.material; if (o.isMesh && mt && !seen.has(mt) && mt.isMeshPhysicalMaterial && mt.clearcoat > 0.5 && mt.color.getHex() !== 0x0d1724) { seen.add(mt); mt.metalness = 0.34; mt.envMapIntensity = 1.8; } });
  car.parts.body.blob.visible = false;
  car.parts.body.pool.visible = false;
  car.parts.body.tailHalo.visible = false;
  for (const side of ['lampL', 'lampR']) { car.parts[side].halo.material.opacity = 0.85; car.parts[side].halo.scale.setScalar(0.8); car.parts[side].led.material.color.setScalar(1.6); }
  car.parts.bar.group.children[0].material.color.setScalar(1.5);
  stage.key.target.position.set(0, 0, 0); stage.key.position.set(-3, 12, 9);
  const SW = 1080, SH = 680;
  renderer.setClearColor(0x000000, 0);
  renderer.setSize(SW, SH);
  const cam = new THREE.PerspectiveCamera(7.7, SW / SH, 0.1, 100);
  const el = 12 * DEG, R = 24.5;
  cam.position.set(0, 0.8 + R * Math.sin(el), R * Math.cos(el)); cam.lookAt(blue ? -0.12 : 0.12, 0.8, 0);
  renderer.render(stage.scene, cam);
  const url = renderer.domElement.toDataURL('image/png');
  cam.updateMatrixWorld();
  window.__stillBox = boxPx2(m, cam, SW, SH);
  disposeStage({ stage });
  renderer.setSize(W, H);
  return url;
};
function boxPx2(m, cam, w, h) {
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const c of BOX) { const p = c.clone().applyMatrix4(m).project(cam); const x = (p.x * 0.5 + 0.5) * w, y = (1 - (p.y * 0.5 + 0.5)) * h; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  return { x0: Math.round(x0), y0: Math.round(y0), x1: Math.round(x1), y1: Math.round(y1) };
}
window.__ready = true;
