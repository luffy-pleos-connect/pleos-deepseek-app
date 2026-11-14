// 사용법: node render.mjs <all|mine_wins|theirs_wins|draw|stills|preview> [옵션]
//   --frames-only  mp4 를 만들지 않고 프레임 PNG 만 뽑는다
//   --out <dir>    mp4/PNG 출력 위치 (기본: ../../app/app/src/main/res/{raw,drawable-nodpi})
// 시스템 Chrome 을 CDP 로 조종해 scene.js 의 renderFrame(변형, 시간) 을 프레임마다 호출하고
// ffmpeg 로 묶는다. 시간은 직접 넘기므로 결과는 항상 같다(결정적).
import { spawn, execFileSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const RES = path.resolve(here, '../../app/app/src/main/res');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FFMPEG = '/opt/homebrew/bin/ffmpeg';
const FRAMES_DIR = path.join(here, 'frames'); // .gitignore 대상
const W = 1600, H = 900, FPS = 30, DURATION = 6.0;
const NFRAMES = Math.round(FPS * DURATION);
const VARIANTS = ['mine_wins', 'theirs_wins', 'draw'];

const args = process.argv.slice(2);
const target = args.find((a) => !a.startsWith('--')) ?? 'all';
const flag = (n) => args.includes(`--${n}`);
const opt = (n, d) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : d; };
const outDir = opt('out', null);
const frameList = opt('frames', null); // 예: 15,36,90 — 지정한 프레임만 PNG 로

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function portFree(p) {
  return new Promise((res) => {
    const s = net.createServer();
    s.once('error', () => res(false));
    s.once('listening', () => s.close(() => res(true)));
    s.listen(p, '127.0.0.1');
  });
}
async function pickPort() {
  for (let p = 10120; p <= 10199; p++) {
    let busy = false;
    try { execFileSync('lsof', ['-i', `:${p}`], { stdio: 'pipe' }); busy = true; } catch { /* 비어 있음 */ }
    if (!busy && (await portFree(p))) return p;
  }
  throw new Error('10120~10199 에 빈 포트가 없다');
}

function serve() {
  const mime = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json' };
  const server = http.createServer((req, res) => {
    const u = decodeURIComponent(req.url.split('?')[0]);
    const f = path.join(here, u === '/' ? 'scene.html' : u);
    if (!f.startsWith(here) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': mime[path.extname(f)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    fs.createReadStream(f).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r(server)));
}

async function launchChrome(port) {
  const userDir = fs.mkdtempSync(path.join(os.tmpdir(), 'crash3d-chrome-'));
  const proc = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${userDir}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    `--window-size=${W},${H}`, '--hide-scrollbars', '--mute-audio',
    '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
    'about:blank',
  ], { stdio: 'ignore' });
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (r.ok) return { proc, userDir };
    } catch { /* 아직 */ }
    await sleep(100);
  }
  proc.kill();
  throw new Error('Chrome CDP 가 안 뜬다');
}

async function connect(port) {
  const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const page = list.find((t) => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0; const pending = new Map(); const logs = [];
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const { r, j } = pending.get(m.id); pending.delete(m.id);
      m.error ? j(new Error(JSON.stringify(m.error))) : r(m.result);
    } else if (m.method === 'Runtime.exceptionThrown') {
      logs.push('EXC ' + JSON.stringify(m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text));
    } else if (m.method === 'Runtime.consoleAPICalled') {
      logs.push(`console.${m.params.type} ` + m.params.args.map((a) => a.value ?? a.description).join(' '));
    }
  };
  const send = (method, params = {}) => new Promise((r, j) => { const i = ++id; pending.set(i, { r, j }); ws.send(JSON.stringify({ id: i, method, params })); });
  return { send, logs, close: () => ws.close() };
}

async function evalJs(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error('평가 실패: ' + JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text) + '\n' + cdp.logs.join('\n'));
  return r.result.value;
}

const writePng = (file, dataUrl) => fs.writeFileSync(file, Buffer.from(dataUrl.split(',')[1], 'base64'));

function encode(variant, dir, outFile, crf) {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  execFileSync(FFMPEG, [
    '-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', path.join(dir, 'f%04d.png'),
    '-an', '-c:v', 'libx264', '-profile:v', 'main', '-pix_fmt', 'yuv420p', '-crf', String(crf),
    '-preset', 'slow', '-movflags', '+faststart', '-r', String(FPS), outFile,
  ]);
  return fs.statSync(outFile).size;
}

async function main() {
  const server = await serve();
  const sport = server.address().port;
  const cport = await pickPort();
  console.log(`서버 ${sport}, Chrome CDP ${cport}`);
  const { proc, userDir } = await launchChrome(cport);
  const cdp = await connect(cport);
  try {
    await cdp.send('Runtime.enable');
    await cdp.send('Page.enable');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${sport}/scene.html` });
    for (let i = 0; ; i++) {
      if (i > 300) throw new Error('scene 로딩 시간 초과\n' + cdp.logs.join('\n'));
      const ok = await evalJs(cdp, 'window.__ready === true').catch(() => false);
      if (ok) break;
      if (await evalJs(cdp, 'typeof window.__error === "string" ? window.__error : ""').catch(() => '')) {
        throw new Error('scene 오류: ' + (await evalJs(cdp, 'window.__error')));
      }
      await sleep(100);
    }
    console.log('scene 준비됨', cdp.logs.slice(-3).join(' | '));

    const doStills = target === 'stills' || target === 'all';
    const variants = target === 'all' ? VARIANTS : VARIANTS.includes(target) ? [target] : [];

    for (const v of variants) {
      const dir = path.join(FRAMES_DIR, v);
      fs.rmSync(dir, { recursive: true, force: true });
      fs.mkdirSync(dir, { recursive: true });
      await evalJs(cdp, `window.setupVariant(${JSON.stringify(v)})`);
      const only = frameList ? new Set(frameList.split(',').map(Number)) : null;
      const t0 = Date.now();
      for (let f = 0; f < NFRAMES; f++) {
        if (only && !only.has(f)) continue;
        const url = await evalJs(cdp, `window.renderFrame(${(f / FPS).toFixed(6)})`);
        writePng(path.join(dir, `f${String(f).padStart(4, '0')}.png`), url);
      }
      console.log(`${v}: 프레임 ${only ? only.size : NFRAMES}장, ${((Date.now() - t0) / 1000).toFixed(1)}s`);
      if (!only && !flag('frames-only')) {
        const out = path.join(outDir ?? path.join(RES, 'raw'), `battle_crash_${v}.mp4`);
        // 3MB 이하가 될 때까지 crf 를 올린다
        for (const crf of [18, 20, 22, 24, 26, 28]) {
          const size = encode(v, dir, out, crf);
          console.log(`  crf ${crf} -> ${(size / 1024).toFixed(0)} KB`);
          if (size <= 3 * 1024 * 1024) break;
        }
      }
    }

    if (doStills) {
      const dir = outDir ?? path.join(RES, 'drawable-nodpi');
      fs.mkdirSync(dir, { recursive: true });
      for (const name of ['mine', 'mine_wrecked', 'theirs', 'theirs_wrecked']) {
        const url = await evalJs(cdp, `window.renderStill(${JSON.stringify(name)})`);
        const file = path.join(dir, `battle_car_${name}.png`);
        writePng(file, url);
        console.log(`정지 그림 ${name}: ${(fs.statSync(file).size / 1024).toFixed(0)} KB`);
      }
    }
    if (cdp.logs.length) console.log('--- 브라우저 로그 ---\n' + cdp.logs.slice(-15).join('\n'));
  } finally {
    cdp.close();
    proc.kill();
    await new Promise((r) => { proc.exitCode !== null ? r() : proc.once('exit', r); });
    server.close();
    fs.rmSync(userDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
