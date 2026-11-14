// 전기 SUV/해치백 스타일 차 모델 — three.js 기본 도형과 로프트(단면 보간)만으로 만든다.
// 좌표: x 앞, y 위, z 옆(차 오른쪽 +z). 길이 4.5m(반 길이 L=2.25). 바닥(바퀴 아래)이 y=0.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';

export const L = 2.25;
export const WHEEL_R = 0.37;
const WHEEL_X = [1.38, -1.38];
const WHEEL_Z = 0.84;

const sp = (a, n) => Math.sign(a) * Math.pow(Math.abs(a), 2 / n);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
const ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };

// ---------- 결정적 값 잡음 ----------
function hash3(i, j, k) {
  let h = (i * 374761393 + j * 668265263 + k * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967295;
}
export function noise3(x, y, z) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const xf = x - xi, yf = y - yi, zf = z - zi;
  const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
  const l = (a, b, t) => a + (b - a) * t;
  const c = (dx, dy, dz) => hash3(xi + dx, yi + dy, zi + dz);
  return l(
    l(l(c(0, 0, 0), c(1, 0, 0), u), l(c(0, 1, 0), c(1, 1, 0), u), v),
    l(l(c(0, 0, 1), c(1, 0, 1), u), l(c(0, 1, 1), c(1, 1, 1), u), v), w) * 2 - 1;
}

// ---------- 조각선형 + 평활 프로필 ----------
function pl(pts, x) {
  if (x <= pts[0][0]) return pts[0][1];
  for (let i = 1; i < pts.length; i++) {
    if (x <= pts[i][0]) {
      const t = (x - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
      return pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t;
    }
  }
  return pts[pts.length - 1][1];
}
function sm(pts, x, w = 0.12) {
  const ws = [1, 2, 3, 2, 1];
  let s = 0, a = 0;
  for (let k = -2; k <= 2; k++) { s += ws[k + 2] * pl(pts, x + k * w); a += ws[k + 2]; }
  return s / a;
}
const endF = (x, R) => {
  const t = (Math.abs(x) - (L - R)) / R;
  if (t <= 0) return 1;
  return Math.pow(Math.max(0, 1 - t * t * t), 1 / 3);
};

const SHELL_TOP = [[-2.25, 0.80], [-2.15, 1.02], [-1.4, 1.06], [0.5, 1.02], [0.9, 0.99], [1.5, 0.90], [2.0, 0.80], [2.25, 0.68]];
const SHELL_BOT = [[-2.25, 0.40], [-2.0, 0.27], [2.0, 0.27], [2.25, 0.38]];
const SHELL_N = 4.2;
function shellDims(x, bonnetSink = 0) {
  let top = sm(SHELL_TOP, x, 0.1);
  const bot = sm(SHELL_BOT, x, 0.1);
  top -= bonnetSink * ss(0.7, 0.95, x) * (1 - ss(2.05, 2.2, x));
  return { yc: (top + bot) / 2, hh: (top - bot) / 2, hw: 0.95 * endF(x, 0.35), top, bot };
}
function shellSideZ(x, y) {
  const d = shellDims(x);
  const yh = clamp((y - d.yc) / d.hh, -0.999, 0.999);
  return d.hw * Math.pow(1 - Math.pow(Math.abs(yh), SHELL_N), 1 / SHELL_N);
}

const CAB_TOP = [[-1.95, 0.97], [-1.75, 1.2], [-1.55, 1.55], [-1.35, 1.64], [-0.6, 1.67], [-0.1, 1.64], [0.2, 1.4], [0.55, 1.12], [0.8, 0.97]];
const CAB_BOT = 0.95;
const CAB_N = 3.0;
const CAB_HW = 0.84;
function cabDims(x) {
  const top = Math.max(sm(CAB_TOP, x, 0.06), CAB_BOT + 0.02);
  return { yc: (top + CAB_BOT) / 2, hh: (top - CAB_BOT) / 2, hw: CAB_HW * endF(x * 1.0, 0.0001) };
}
function cabSideZ(x, y) {
  const d = cabDims(x);
  const yh = clamp((y - d.yc) / d.hh, -0.999, 0.999);
  return d.hw * Math.pow(1 - Math.pow(Math.abs(yh), CAB_N), 1 / CAB_N);
}
function cabTopY(x, z) {
  const d = cabDims(x);
  const zh = clamp(z / d.hw, -0.999, 0.999);
  return d.yc + d.hh * Math.pow(1 - Math.pow(Math.abs(zh), CAB_N), 1 / CAB_N);
}

// ---------- 지오메트리 도우미 ----------
function build(pos, idx, col) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(pos), 3));
  g.setIndex(idx);
  g.setAttribute('color', new THREE.BufferAttribute(col ? new Float32Array(col) : new Float32Array(pos.length).fill(1), 3));
  g.computeVertexNormals();
  return g;
}
// 단면(링)을 x 방향으로 이어 붙인 닫힌 로프트. ring(x,k) -> [z,y]
function loft(xs, M, ring, opts = {}) {
  const k0 = opts.k0 ?? 0, k1 = opts.k1 ?? M; // 링 일부만(열린 띠)
  const closed = k0 === 0 && k1 === M;
  const nk = closed ? M : k1 - k0 + 1;
  const pos = [];
  for (const x of xs) for (let j = 0; j < nk; j++) { const [z, y] = ring(x, k0 + j); pos.push(x, y, z); }
  const idx = [];
  const at = (i, j) => i * nk + (closed ? j % M : j);
  for (let i = 0; i < xs.length - 1; i++) {
    for (let j = 0; j < (closed ? nk : nk - 1); j++) {
      const a = at(i, j), b = at(i, j + 1), c = at(i + 1, j), d = at(i + 1, j + 1);
      idx.push(a, c, b, b, c, d);
    }
  }
  return build(pos, idx);
}
// (u,v) 격자 -> fn(u,v)=[x,y,z]
function grid(nu, nv, fn) {
  const pos = [];
  for (let i = 0; i <= nu; i++) for (let j = 0; j <= nv; j++) pos.push(...fn(i / nu, j / nv));
  const idx = [];
  for (let i = 0; i < nu; i++) for (let j = 0; j < nv; j++) {
    const a = i * (nv + 1) + j, b = a + 1, c = a + nv + 1, d = c + 1;
    idx.push(a, b, c, b, d, c);
  }
  const g = build(pos, idx);
  const uv = [];
  for (let i = 0; i <= nu; i++) for (let j = 0; j <= nv; j++) uv.push(i / nu, j / nv);
  g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(uv), 2));
  return g;
}
function shellRing(bonnetSink = 0) {
  const M = 48;
  return (x, k) => {
    const d = shellDims(x, bonnetSink);
    const th = (k / M) * Math.PI * 2;
    return [d.hw * sp(Math.cos(th), SHELL_N), d.yc + d.hh * sp(Math.sin(th), SHELL_N)];
  };
}
const linspace = (a, b, n) => Array.from({ length: n + 1 }, (_, i) => a + ((b - a) * i) / n);
function translateGeom(g, v) { g.translate(-v.x, -v.y, -v.z); return g; }

// ---------- 재질 ----------
export function makeMaterials(paintHex) {
  const paint = new THREE.MeshPhysicalMaterial({ color: paintHex, metalness: 0.28, roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.06, vertexColors: true });
  const dark = new THREE.MeshStandardMaterial({ color: 0x23262d, roughness: 0.55, metalness: 0.2 });
  const darkVC = new THREE.MeshStandardMaterial({ color: 0x2a2d35, roughness: 0.6, metalness: 0.2, vertexColors: true });
  const glass = new THREE.MeshPhysicalMaterial({ color: 0x0d1724, metalness: 0.0, roughness: 0.06, clearcoat: 1, clearcoatRoughness: 0.02, envMapIntensity: 1.4, side: THREE.DoubleSide, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2 });
  const tire = new THREE.MeshStandardMaterial({ color: 0x15171b, roughness: 0.85, metalness: 0 });
  const rim = new THREE.MeshStandardMaterial({ color: 0xcfd3da, roughness: 0.28, metalness: 0.95 });
  const rimDark = new THREE.MeshStandardMaterial({ color: 0x30333b, roughness: 0.6, metalness: 0.4 });
  const seam = new THREE.MeshBasicMaterial({ color: 0x14161b, side: THREE.DoubleSide });
  const lamp = new THREE.MeshBasicMaterial({ color: 0xffffff, toneMapped: false });
  const tail = new THREE.MeshBasicMaterial({ color: 0xff3b2e, toneMapped: false });
  const liner = new THREE.MeshStandardMaterial({ color: 0x25272c, roughness: 0.7, side: THREE.BackSide });
  const paintDS = paint.clone(); paintDS.side = THREE.DoubleSide;
  return { paint, paintDS, dark, darkVC, glass, tire, rim, rimDark, seam, lamp, tail, liner };
}

function textures() {
  // 앞유리 균열 무늬 (투명 바탕 + 흰 금)
  const cv = document.createElement('canvas'); cv.width = 512; cv.height = 512;
  const c = cv.getContext('2d');
  c.strokeStyle = 'rgba(235,245,255,0.95)'; c.lineCap = 'round';
  let s = 12345; const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  const cx = 150, cy = 330;
  for (let i = 0; i < 20; i++) {
    const a = (i / 20) * Math.PI * 2 + rnd() * 0.2; let x = cx, y = cy, r = 0; c.lineWidth = 2.2; c.beginPath(); c.moveTo(x, y);
    const len = 160 + rnd() * 340;
    while (r < len) { r += 24 + rnd() * 16; const a2 = a + (rnd() - 0.5) * 0.35; x = cx + Math.cos(a2) * r; y = cy + Math.sin(a2) * r; c.lineTo(x, y); if (rnd() < 0.18) { c.moveTo(x, y); c.lineTo(x + Math.cos(a2 + 1) * 40, y + Math.sin(a2 + 1) * 40); c.moveTo(x, y); } }
    c.stroke();
  }
  for (const R of [40, 80, 130, 190]) {
    c.lineWidth = 1.4; c.beginPath();
    for (let i = 0; i <= 16; i++) { const a = (i / 16) * Math.PI * 2; const rr = R * (0.85 + 0.3 * rnd()); const px = cx + Math.cos(a) * rr, py = cy + Math.sin(a) * rr; i ? c.lineTo(px, py) : c.moveTo(px, py); }
    c.stroke();
  }
  c.fillStyle = 'rgba(210,225,240,0.28)'; c.beginPath(); c.arc(cx, cy, 34, 0, 7); c.fill();
  const crack = new THREE.CanvasTexture(cv); crack.colorSpace = THREE.SRGBColorSpace;

  const glow = (stops, size = 128) => {
    const k = document.createElement('canvas'); k.width = k.height = size; const g = k.getContext('2d');
    const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
    for (const [o, col] of stops) gr.addColorStop(o, col);
    g.fillStyle = gr; g.fillRect(0, 0, size, size);
    const t = new THREE.CanvasTexture(k); t.colorSpace = THREE.SRGBColorSpace; return t;
  };
  const headGlow = glow([[0, 'rgba(255,255,255,1)'], [0.18, 'rgba(255,244,190,0.95)'], [0.45, 'rgba(255,214,90,0.5)'], [1, 'rgba(255,190,60,0)']]);
  const tailGlow = glow([[0, 'rgba(255,255,255,0.9)'], [0.2, 'rgba(255,90,70,0.8)'], [0.5, 'rgba(255,60,40,0.35)'], [1, 'rgba(255,40,30,0)']]);
  const blob = glow([[0, 'rgba(15,20,30,0.55)'], [0.55, 'rgba(15,20,30,0.28)'], [1, 'rgba(15,20,30,0)']], 256);

  const pk = document.createElement('canvas'); pk.width = 512; pk.height = 256; const g = pk.getContext('2d');
  g.filter = 'blur(10px)';
  const lg = g.createLinearGradient(0, 0, 512, 0);
  lg.addColorStop(0, 'rgba(255,232,140,0.85)'); lg.addColorStop(0.5, 'rgba(255,236,160,0.42)'); lg.addColorStop(1, 'rgba(255,240,180,0)');
  g.fillStyle = lg; g.beginPath(); g.moveTo(0, 105); g.lineTo(512, 20); g.lineTo(512, 236); g.lineTo(0, 151); g.closePath(); g.fill();
  const pool = new THREE.CanvasTexture(pk); pool.colorSpace = THREE.SRGBColorSpace;
  return { crack, headGlow, tailGlow, blob, pool };
}
let TEX = null;
export const getTextures = () => (TEX ??= textures());

// ---------- 차 만들기 ----------
// 반환: { parts, setDamage(c, flags), spinWheels(a), ... }
// part = { name, group, anchor(Matrix4), extra(Matrix4), deform:[{geom, base, origin}] , radius, thin }
export function buildCar(paintHex) {
  const M = makeMaterials(paintHex);
  const T = getTextures();
  const parts = {};
  const deformables = [];
  const addPart = (name, anchorVec, opts = {}) => {
    const group = new THREE.Group();
    group.matrixAutoUpdate = false;
    group.name = name;
    const p = { name, group, anchor: new THREE.Matrix4().makeTranslation(anchorVec.x, anchorVec.y, anchorVec.z), extra: new THREE.Matrix4(), origin: anchorVec.clone(), ...opts };
    parts[name] = p;
    return p;
  };
  const mesh = (part, geom, mat, { shadow = true, deform = false } = {}) => {
    if (!geom.attributes.color) geom.setAttribute('color', new THREE.BufferAttribute(new Float32Array(geom.attributes.position.count * 3).fill(1), 3));
    const m = new THREE.Mesh(geom, mat);
    m.castShadow = shadow; m.receiveShadow = false;
    part.group.add(m);
    if (deform) {
      deformables.push({ geom, base: geom.attributes.position.array.slice(), baseCol: geom.attributes.color ? geom.attributes.color.array.slice() : null, origin: part.origin, kind: deform });
    }
    return m;
  };
  const O = new THREE.Vector3(0, 0, 0);

  // 차체 껍데기 (밑·옆 어두운 클래딩, 앞쪽은 어두운 내부)
  const body = addPart('body', O);
  const xsShell = linspace(-L, L, 130);
  const shellGeom = loft(xsShell, 48, shellRing(0.035));
  { const pos = shellGeom.attributes.position, col = shellGeom.attributes.color;
    for (let i = 0; i < pos.count; i++) {
      const x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i);
      let k = 1;
      if (y < 0.42) k = 0.2;
      if (x > 2.0) k = 0.18;
      if (x > 0.9 && y > 0.8 && Math.abs(z) < 0.75) k = 0.22;
      col.setXYZ(i, k, k, k);
    } }
  mesh(body, shellGeom, M.paint, { deform: 'shell' });
  // 캐빈 (칠한 지붕·기둥)
  const cabGeom = loft(linspace(-1.95, 0.8, 90), 48, (x, k) => {
    const d = cabDims(x); const th = (k / 48) * Math.PI * 2;
    return [d.hw * sp(Math.cos(th), CAB_N), d.yc + d.hh * sp(Math.sin(th), CAB_N)];
  });
  mesh(body, cabGeom, M.paint, { deform: 'cabin' });

  // 창 — 캐빈 표면 위에 얹는 띠
  const windowGeom = (corners, side) => grid(10, 8, (u, v) => {
    const [bf, tf, tr, br] = corners; // [x,y] 앞아래, 앞위, 뒤위, 뒤아래
    const xa = bf[0] + (tf[0] - bf[0]) * v, xb = br[0] + (tr[0] - br[0]) * v;
    const ya = bf[1] + (tf[1] - bf[1]) * v, yb = br[1] + (tr[1] - br[1]) * v;
    const x = xa + (xb - xa) * u, y = ya + (yb - ya) * u;
    return [x, y, side * (cabSideZ(x, y) + 0.008)];
  });
  const glassMeshes = [];
  for (const side of [1, -1]) {
    mesh(body, windowGeom([[0.5, 1.04], [-0.02, 1.53], [-0.56, 1.53], [-0.56, 1.04]], side), M.glass, { shadow: false });
    mesh(body, windowGeom([[-0.7, 1.04], [-0.7, 1.53], [-1.34, 1.53], [-1.58, 1.04]], side), M.glass, { shadow: false });
  }
  const wsGeom = grid(12, 12, (u, v) => {
    const x = 0.62 - u * 0.66; const z = (v - 0.5) * 1.18; return [x, cabTopY(x, z) + 0.01, z];
  });
  const ws = mesh(body, wsGeom, M.glass, { shadow: false, deform: 'glass' });
  const crackMat = new THREE.MeshBasicMaterial({ map: T.crack, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -4, polygonOffsetUnits: -4, side: THREE.DoubleSide });
  const crackMesh = new THREE.Mesh(wsGeom, crackMat); crackMesh.visible = false; body.group.add(crackMesh);
  const rgGeom = grid(8, 10, (u, v) => {
    const x = -1.5 - u * 0.3; const z = (v - 0.5) * 0.98; return [x, cabTopY(x, z) + 0.01, z];
  });
  mesh(body, rgGeom, M.glass, { shadow: false, deform: false });

  // 휠 아치 (차체 표면 따라가는 어두운 원판)
  for (const side of [1, -1]) for (const wx of WHEEL_X) {
    const disc = (R0, R1, mat, off) => grid(28, 3, (u, v) => {
      const a = u * Math.PI * 2, r = R0 + (R1 - R0) * v;
      const x = wx + Math.cos(a) * r, y = WHEEL_R + Math.sin(a) * r;
      return [x, y, side * (shellSideZ(x, y) + off)];
    });
    mesh(body, disc(0, 0.47, M.dark, 0.012), M.dark, { shadow: false });
    mesh(body, disc(0.47, 0.52, M.dark, 0.02), M.darkVC.clone(), { shadow: false });
  }
  // 뒤 범퍼 + 후미등
  const rb = new RoundedBoxGeometry(0.34, 0.3, 1.78, 4, 0.1); rb.translate(-2.17, 0.46, 0);
  mesh(body, rb, M.paintDS.clone(), { });
  const tailBar = new RoundedBoxGeometry(0.04, 0.05, 1.5, 2, 0.015); tailBar.translate(-2.27, 0.86, 0);
  mesh(body, tailBar, M.tail, { shadow: false });
  const tailHalo = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.tailGlow, transparent: true, depthWrite: false, opacity: 0.9 }));
  tailHalo.renderOrder = 7; tailHalo.scale.set(1.4, 0.55, 1); tailHalo.position.set(-2.35, 0.86, 0); body.group.add(tailHalo);
  body.tailHalo = tailHalo;
  // 지붕 레일 느낌의 얇은 몰딩
  // 접지 그림자 블롭
  const blob = new THREE.Mesh(new THREE.PlaneGeometry(6.2, 3.2), new THREE.MeshBasicMaterial({ map: T.blob, transparent: true, depthWrite: false, opacity: 0.85 }));
  blob.rotation.x = -Math.PI / 2; blob.position.set(0, 0.012, 0); blob.userData.noMirror = true; blob.userData.blob = true; blob.renderOrder = 3;
  body.group.add(blob);
  body.blob = blob;
  // 전조등 불빛 웅덩이
  const pool = new THREE.Mesh(new THREE.PlaneGeometry(7, 3.4), new THREE.MeshBasicMaterial({ map: T.pool, transparent: true, depthWrite: false }));
  pool.rotation.x = -Math.PI / 2; pool.position.set(2.2 + 3.4, 0.016, 0); pool.userData.noMirror = true; pool.renderOrder = 4;
  pool.visible = false;
  body.group.add(pool);
  body.pool = pool;

  // 보닛 (경첩 = 뒤쪽 가장자리)
  const hingeX = 0.88;
  const hood = addPart('hood', new THREE.Vector3(hingeX, 1.0, 0), { hinge: true });
  const hoodRing = (x, k) => {
    const d = shellDims(x, 0); const th = (k / 48) * Math.PI * 2;
    const z = d.hw * sp(Math.cos(th), SHELL_N), y = d.yc + d.hh * sp(Math.sin(th), SHELL_N);
    const dy = y - d.yc, len = Math.hypot(z, dy) || 1;
    return [z + (z / len) * 0.012, y + (dy / len) * 0.012];
  };
  const hoodGeom = loft(linspace(hingeX, 2.1, 50), 48, hoodRing, { k0: 4, k1: 20 });
  translateGeom(hoodGeom, hood.origin);
  mesh(hood, hoodGeom, M.paintDS, { deform: 'hood' });
  mesh(hood, hoodGeom, M.liner, {});

  // 문 4개 (차체 표면에 얹은 띠 + 어두운 이음매)
  const doorDefs = { FR: [-0.12, 0.86, 1], RR: [-1.36, -0.2, 1], FL: [-0.12, 0.86, -1], RL: [-1.36, -0.2, -1] };
  for (const [nm, [x0, x1, side]] of Object.entries(doorDefs)) {
    const cx = (x0 + x1) / 2, cy = 0.74;
    const part = addPart('door' + nm, new THREE.Vector3(cx, cy, side * 0.9), { thin: 'z', radius: 0.35 });
    const mk = (pad, off) => {
      const g = grid(10, 8, (u, v) => {
        const x = x0 - pad + (x1 - x0 + 2 * pad) * u;
        const y = 0.5 - pad + (0.98 - 0.5 + 2 * pad) * v;
        return [x, y, side * (shellSideZ(x, y) + off)];
      });
      return translateGeom(g, part.origin);
    };
    part.seamMesh = mesh(part, mk(0.012, 0.011), M.seam, { shadow: false });
    mesh(part, mk(0, 0.016), M.paintDS, { shadow: false });
    const handle = new RoundedBoxGeometry(0.18, 0.03, 0.03, 2, 0.01); handle.translate(cx + (x1 - x0) * 0.3 - cx, 0.9 - cy, side * (shellSideZ(cx, 0.9) + 0.03) - side * 0.9);
    mesh(part, handle, M.dark, { shadow: false });
  }
  // 사이드미러
  for (const side of [1, -1]) {
    const part = addPart('mirror' + (side > 0 ? 'R' : 'L'), new THREE.Vector3(0.55, 1.1, side * 0.96), { radius: 0.12 });
    const g = new RoundedBoxGeometry(0.1, 0.12, 0.2, 3, 0.04); g.translate(0, 0, side * 0.08);
    mesh(part, g, M.paintDS, {});
    const stalk = new THREE.BoxGeometry(0.05, 0.04, 0.1); stalk.translate(0, -0.03, side * -0.02);
    mesh(part, stalk, M.dark, {});
  }

  // 앞 범퍼 (변형/탈락)
  const bumpF = addPart('bumperF', new THREE.Vector3(2.04, 0.46, 0), { radius: 0.3, thin: 'x' });
  const bumpGeom = loft(linspace(1.8, 2.3, 28), 48, (x, k) => {
    const th = (k / 48) * Math.PI * 2;
    const hw = Math.max(0.09, 0.98 * endF(Math.min(x * 0.978, L), 0.35));
    return [hw * sp(Math.cos(th), 3.4), 0.46 + 0.17 * sp(Math.sin(th), 3.4)];
  });
  // 앞끝(x=L) 단면이 닫히도록 폭을 줄였으므로 열린 구멍 없음. 색: 아래쪽 어둡게(에어댐)
  { const col = []; const pos = bumpGeom.attributes.position;
    for (let i = 0; i < pos.count; i++) { const y = pos.getY(i); const d = y < 0.38 ? 0.16 : 1; col.push(d, d, d); }
    bumpGeom.setAttribute('color', new THREE.BufferAttribute(new Float32Array(col), 3)); }
  translateGeom(bumpGeom, bumpF.origin);
  mesh(bumpF, bumpGeom, M.paint, { deform: 'bumper' });

  // 전조등 + 라이트바 (앞쪽)
  const headLamps = {};
  for (const side of [1, -1]) {
    const part = addPart('lamp' + (side > 0 ? 'R' : 'L'), new THREE.Vector3(2.1, 0.7, side * 0.68), { radius: 0.15 });
    const housing = new RoundedBoxGeometry(0.16, 0.11, 0.44, 3, 0.04);
    housing.rotateY(side * -0.5);
    mesh(part, housing, M.dark.clone(), { deform: 'lamp' });
    const led = new RoundedBoxGeometry(0.17, 0.03, 0.34, 2, 0.012);
    led.rotateY(side * -0.5); led.translate(0.012, 0.0, 0);
    const lm = new THREE.Mesh(led, M.lamp.clone()); part.group.add(lm);
    deformables.push({ geom: led, base: led.attributes.position.array.slice(), baseCol: null, origin: part.origin, kind: 'lamp' });
    const halo = new THREE.Sprite(new THREE.SpriteMaterial({ map: T.headGlow, transparent: true, depthWrite: false, opacity: 0.95 }));
    halo.renderOrder = 7; halo.scale.set(0.95, 0.95, 1); halo.position.set(0.15, 0.02, 0); part.group.add(halo);
    part.halo = halo; part.led = lm; headLamps[side] = part;
  }
  const barPart = addPart('bar', new THREE.Vector3(2.235, 0.69, 0), {});
  const barGeom = new RoundedBoxGeometry(0.035, 0.03, 0.9, 2, 0.012); 
  mesh(barPart, barGeom, M.lamp.clone(), { deform: 'lamp', shadow: false });

  // 바퀴 4개 (타이어+림+바큇살). 앞 near 바퀴(side=-1)는 탈락 후보.
  const wheelParts = {};
  for (const side of [1, -1]) for (const [i, wx] of WHEEL_X.entries()) {
    const nm = 'wheel' + (i === 0 ? 'F' : 'R') + (side > 0 ? 'R' : 'L');
    const part = addPart(nm, new THREE.Vector3(wx, WHEEL_R, side * WHEEL_Z), { radius: WHEEL_R, wheel: true });
    const tire = new THREE.LatheGeometry([[0.2, -0.13], [0.33, -0.13], [0.362, -0.09], [0.37, 0], [0.362, 0.09], [0.33, 0.13], [0.2, 0.13]].map(([r, y]) => new THREE.Vector2(r, y)), 40);
    tire.rotateX(Math.PI / 2);
    mesh(part, tire, M.tire, {});
    const spin = new THREE.Group(); part.group.add(spin); part.spin = spin;
    for (const sz of [1, -1]) {
      const disc = new THREE.CylinderGeometry(0.235, 0.235, 0.012, 36); disc.rotateX(Math.PI / 2); disc.translate(0, 0, sz * 0.118);
      const dm = new THREE.Mesh(disc, M.rimDark); spin.add(dm);
      for (let s = 0; s < 5; s++) {
        const sg = new THREE.BoxGeometry(0.23, 0.05, 0.016); sg.translate(0.13, 0, 0); sg.rotateZ((s / 5) * Math.PI * 2); sg.translate(0, 0, sz * 0.126);
        spin.add(new THREE.Mesh(sg, M.rim));
      }
      const hub = new THREE.CylinderGeometry(0.06, 0.06, 0.02, 20); hub.rotateX(Math.PI / 2); hub.translate(0, 0, sz * 0.13);
      spin.add(new THREE.Mesh(hub, M.rim));
    }
    wheelParts[nm] = part;
  }

  for (const p of Object.values(parts)) p.group.traverse((o) => { if (o.isMesh) o.frustumCulled = false; });

  // --- 변형 ---
  let lastKey = '';
  const out = [0, 0, 0, 0];
  function setDamage(c, bias, scorch = 1) {
    const key = c.toFixed(4) + '|' + bias;
    if (key === lastKey) return; lastKey = key;
    for (const d of deformables) {
      const pos = d.geom.attributes.position; const col = d.geom.attributes.color;
      const hasCol = d.kind === 'shell' || d.kind === 'hood' || d.kind === 'bumper' || d.kind === 'cabin';
      for (let i = 0; i < pos.count; i++) {
        const x = d.base[i * 3] + d.origin.x, y = d.base[i * 3 + 1] + d.origin.y, z = d.base[i * 3 + 2] + d.origin.z;
        crumple(x, y, z, c, bias, out);
        pos.setXYZ(i, x + out[0] - d.origin.x, y + out[1] - d.origin.y, z + out[2] - d.origin.z);
        if (col && hasCol) {
          let k = d.baseCol[i * 3];
          k *= 1 - 0.7 * scorch * out[3];
          col.setXYZ(i, k, k, k);
        }
      }
      pos.needsUpdate = true; if (col) col.needsUpdate = true;
      d.geom.computeVertexNormals();
      d.geom.computeBoundingSphere();
    }
  }
  return { parts, M, setDamage, headLamps, wheelParts, crackMesh, windshield: ws };
}

// 앞부분 찌그러짐. out = [dx,dy,dz,그을림(0~1)]
export function crumple(x, y, z, c, bias, out) {
  out[0] = out[1] = out[2] = out[3] = 0;
  if (c <= 0) return;
  const s = clamp((x - 0.1) / (L - 0.1), 0, 1);
  if (s <= 0) return;
  const s2 = s * s;
  const side = 0.5 + 0.5 * clamp((z * bias) / 0.95, -1, 1); // 충돌이 쏠린 쪽 모서리
  const n1 = noise3(x * 2.6, y * 2.6, z * 2.6), n2 = noise3(x * 6 + 4, y * 6, z * 6), n3 = noise3(x * 1.4 + 9, y * 1.4, z * 1.4);
  const push = c * (1.05 * Math.pow(s, 1.5) * (0.72 + 0.55 * side) + 0.1 * s * n1 + 0.04 * s * n2);
  out[0] = -push;
  const top = clamp((y - 0.45) / 0.6, 0, 1);
  out[1] = -c * (0.2 * s2 * top + 0.06 * s * Math.abs(n3)) + c * 0.06 * s * n2 + c * 0.05 * Math.pow(s, 3) * (1 - top);
  out[2] = c * (0.14 * s * n3 + 0.04 * s * n2) - z * c * 0.12 * s2 + (-bias) * c * 0.06 * s * (z > 0 ? 1 : 0.3);
  out[3] = c * ss(0.35, 0.95, s) * (0.55 + 0.45 * Math.abs(n1));
}
