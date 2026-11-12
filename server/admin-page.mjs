// 계획 v10 2.5: 운영자 대시보드 한 파일짜리 HTML. 바깥 스크립트·글꼴·그림은 쓰지 않는다.
import { summarize } from './admin-view.mjs';

const CSS = `
:root {
  --bg: #ffffff;
  --fg: #14171c;
  --muted: #5b636e;
  --line: #dee2e8;
  --card: #f5f6f8;
  --track: #e8ebf0;
  --accent: #1f5fd0;
  --accent-soft: #dde7fa;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #101216;
    --fg: #e9edf3;
    --muted: #9aa3b0;
    --line: #2a2f38;
    --card: #181b21;
    --track: #242a33;
    --accent: #7aa7ff;
    --accent-soft: #22304a;
  }
}
* { box-sizing: border-box; }
[hidden] { display: none !important; }
html, body { margin: 0; padding: 0; }
body {
  background: var(--bg);
  color: var(--fg);
  font-family: system-ui, -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
  font-size: 14px;
  line-height: 1.45;
  padding: 16px;
  max-width: 1440px;
  margin: 0 auto;
}
h1 { font-size: 20px; margin: 0 0 12px; letter-spacing: -0.01em; }
h2 { font-size: 13px; font-weight: 600; color: var(--muted); margin: 24px 0 8px; }
.num { font-variant-numeric: tabular-nums; }
.head { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; }
.controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.group { display: flex; gap: 4px; }
.group button, .gate button {
  font: inherit;
  color: var(--fg);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}
.group button[aria-pressed="true"] {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.updated { color: var(--muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.band {
  margin: 12px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--accent);
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}
.gate { margin: 32px 0; display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-end; }
.gate label { font-size: 12px; color: var(--muted); display: block; }
.gate input {
  font: inherit;
  color: var(--fg);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 10px;
  width: 220px;
}
.gate button { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); font-weight: 600; }
.error { color: var(--accent); font-size: 13px; margin: 8px 0; }
.error::before { content: "· "; }
.cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.card-name { font-size: 11px; color: var(--muted); }
.card-value { font-size: 26px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -0.02em; }
.card-lead { border-color: var(--accent); }
.note { color: var(--muted); font-size: 12px; margin: 6px 0 0; }
.chart { position: relative; display: flex; align-items: flex-end; gap: 6px; height: 160px; border-bottom: 1px solid var(--line); padding: 0 2px 16px; }
.chart.many { gap: 2px; }
.col { position: relative; flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 2px; height: 100%; }
.col-value { font-size: 10px; color: var(--muted); font-variant-numeric: tabular-nums; }
.col-bar { flex: 1 1 auto; width: 100%; display: flex; flex-direction: column; justify-content: flex-end; }
.chart.many .col-bar { width: 100%; }
.seg-guide { background: var(--accent); flex: 0 0 auto; }
.seg-product { background: var(--accent-soft); border-top: 1px solid var(--accent); flex: 0 0 auto; }
.col-base { height: 2px; background: var(--line); flex: 0 0 auto; }
.col-day {
  position: absolute;
  bottom: -16px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 10px;
  color: var(--muted);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.col-day.at-start { left: 0; transform: none; }
.col-day.at-end { left: auto; right: 0; transform: none; }
.chart.many .col-day { font-size: 8px; }
.legend { display: flex; gap: 12px; margin: 8px 0 0; font-size: 11px; color: var(--muted); }
.legend span::before { content: ""; display: inline-block; width: 8px; height: 8px; margin-right: 4px; border-radius: 2px; vertical-align: middle; }
.legend .lg-guide::before { background: var(--accent); }
.legend .lg-product::before { background: var(--accent-soft); border: 1px solid var(--accent); }
.funnel-row { margin: 0 0 8px; }
.funnel-head { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.funnel-meta { color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; }
.funnel-track { height: 8px; background: var(--track); border-radius: 4px; margin-top: 4px; overflow: hidden; }
.funnel-fill { height: 100%; background: var(--accent); border-radius: 4px; max-width: 100%; }
.stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; }
.stat { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
.stat-name { display: block; font-size: 11px; color: var(--muted); }
.stat-value { font-size: 16px; font-variant-numeric: tabular-nums; }
.tbl { width: 100%; border-collapse: collapse; table-layout: fixed; font-size: 12px; }
.tbl th, .tbl td { text-align: left; padding: 5px 6px; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.tbl th { font-weight: 600; color: var(--muted); font-size: 11px; }
.tbl td.n, .tbl th.n { text-align: right; font-variant-numeric: tabular-nums; }
.kinds th:first-child, .kinds td:first-child { width: 34%; }
.kinds th:not(:first-child), .kinds td:not(:first-child) { width: 16.5%; }
.recent th:nth-child(1), .recent td:nth-child(1) { width: 20%; }
.recent th:nth-child(2), .recent td:nth-child(2) { width: 16%; }
.recent th:nth-child(3), .recent td:nth-child(3) { width: 24%; }
.recent th:nth-child(4), .recent td:nth-child(4) { width: 40%; }
.tbl td.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.tbl td.props { color: var(--muted); }
.wrap { max-height: 340px; overflow-y: auto; }
.empty { color: var(--muted); font-size: 12px; margin: 4px 0; }
@media (min-width: 1200px) {
  body { padding: 24px; }
  .cards { grid-template-columns: repeat(6, minmax(0, 1fr)); }
  .pair { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 24px; align-items: start; }
}
`;

const CLIENT = `
var KEY = 'moca.admin.token';
var REFRESH_MS = 30000;
var state = { token: '', scope: 'real', days: 7, busy: false };
var timer = 0;

function byId(id) { return document.getElementById(id); }
function el(tag, cls, value) {
  var node = document.createElement(tag);
  if (cls) node.className = cls;
  if (value !== undefined && value !== null) node.textContent = String(value);
  return node;
}
function pad2(n) { return n < 10 ? '0' + n : String(n); }
function hhmmss(date) { return pad2(date.getHours()) + ':' + pad2(date.getMinutes()) + ':' + pad2(date.getSeconds()); }
function intText(value) {
  var n = Number(value);
  if (!Number.isFinite(n)) n = 0;
  return String(n);
}
function readStored() {
  try { return window.sessionStorage.getItem(KEY) || ''; } catch (error) { return ''; }
}
function writeStored(value) {
  try {
    if (value) window.sessionStorage.setItem(KEY, value);
    else window.sessionStorage.removeItem(KEY);
  } catch (error) { return; }
}
function setError(message) {
  var box = byId('errorBox');
  if (message) { box.textContent = message; box.hidden = false; }
  else { box.textContent = ''; box.hidden = true; }
}
function setPressed(id, pressed) { byId(id).setAttribute('aria-pressed', pressed ? 'true' : 'false'); }
function showGate(message) {
  byId('gate').hidden = false;
  byId('dash').hidden = true;
  byId('controls').hidden = true;
  byId('demoBand').hidden = true;
  setError(message || '');
  byId('tokenInput').focus();
}
function showDash() {
  byId('gate').hidden = true;
  byId('dash').hidden = false;
  byId('controls').hidden = false;
  byId('demoBand').hidden = state.scope !== 'demo';
  setError('');
}

function setCard(id, value) {
  var card = byId(id);
  var target = card.querySelector('.card-value');
  if (target) target.textContent = intText(value);
}

function drawCards(cards) {
  setCard('cardDevices', cards.devices);
  setCard('cardInspections', cards.inspections);
  setCard('cardProblems', cards.problems);
  setCard('cardGuide', cards.guide);
  setCard('cardProduct', cards.product);
  setCard('cardLeads', cards.leads);
}

function drawDaily(daily) {
  var box = byId('dailyChart');
  box.replaceChildren();
  box.className = daily.length > 14 ? 'chart many' : 'chart';
  if (daily.length === 0) {
    box.className = 'chart-empty';
    box.appendChild(el('p', 'empty', '아직 기록이 없어요'));
    return;
  }
  var max = 0;
  daily.forEach(function (row) {
    var total = Number(row.guide) + Number(row.product);
    if (Number.isFinite(total) && total > max) max = total;
  });
  var inner = 120;
  var labelEvery = daily.length > 20 ? 7 : daily.length > 10 ? 3 : 1;
  var lastLabel = -999;
  daily.forEach(function (row, index) {
    var guide = Number(row.guide) || 0;
    var product = Number(row.product) || 0;
    var total = guide + product;
    var col = el('div', 'col');
    col.title = row.day + ' · 안내 ' + guide + ' · 상품 ' + product + ' · 열기 ' + (Number(row.opens) || 0);
    if (daily.length <= 20) col.appendChild(el('span', 'col-value', total > 0 ? String(total) : ''));
    var bar = el('div', 'col-bar');
    var guideHeight = max > 0 ? Math.round((guide / max) * inner) : 0;
    var productHeight = max > 0 ? Math.round((product / max) * inner) : 0;
    if (guideHeight > 0) {
      var top = el('div', 'seg-guide');
      top.style.height = guideHeight + 'px';
      top.title = '안내 누름 ' + guide;
      bar.appendChild(top);
    }
    if (productHeight > 0) {
      var bottom = el('div', 'seg-product');
      bottom.style.height = productHeight + 'px';
      bottom.title = '상품 누름 ' + product;
      bar.appendChild(bottom);
    }
    bar.appendChild(el('div', 'col-base'));
    col.appendChild(bar);
    var gap = labelEvery === 1 ? 1 : Math.ceil(labelEvery / 2);
    var isLast = index === daily.length - 1;
    var showLabel = isLast ? index - lastLabel >= gap : index % labelEvery === 0;
    if (showLabel) lastLabel = index;
    if (showLabel) {
      var dayLabel = el('span', 'col-day', row.day);
      if (index === 0) dayLabel.className = 'col-day at-start';
      else if (isLast) dayLabel.className = 'col-day at-end';
      col.appendChild(dayLabel);
    }
    box.appendChild(col);
  });
}

function drawFunnel(funnel) {
  var box = byId('funnel');
  box.replaceChildren();
  var first = funnel.length > 0 ? Number(funnel[0].count) || 0 : 0;
  funnel.forEach(function (step) {
    var count = Number(step.count) || 0;
    var ratio = first > 0 ? Math.round((count * 100) / first) : null;
    var row = el('div', 'funnel-row');
    var head = el('div', 'funnel-head');
    head.appendChild(el('span', 'funnel-name', step.label));
    head.appendChild(el('span', 'funnel-meta', ratio === null ? String(count) : count + ' · ' + ratio + '%'));
    row.appendChild(head);
    var track = el('div', 'funnel-track');
    var fill = el('div', 'funnel-fill');
    fill.style.width = (ratio === null ? 0 : ratio) + '%';
    track.appendChild(fill);
    row.appendChild(track);
    box.appendChild(row);
  });
}

function drawKinds(kinds) {
  var box = byId('kindTable');
  box.replaceChildren();
  if (kinds.length === 0) {
    box.appendChild(el('p', 'empty', '아직 기록이 없어요'));
    return;
  }
  var table = el('table', 'tbl kinds');
  var head = el('thead');
  var headRow = el('tr');
  headRow.appendChild(el('th', null, '종류'));
  headRow.appendChild(el('th', 'n', '발견'));
  headRow.appendChild(el('th', 'n', '안내 누름'));
  headRow.appendChild(el('th', 'n', '상품 누름'));
  headRow.appendChild(el('th', 'n', '해소'));
  head.appendChild(headRow);
  table.appendChild(head);
  var body = el('tbody');
  kinds.forEach(function (row) {
    var tr = el('tr');
    tr.appendChild(el('td', null, row.kind));
    tr.appendChild(el('td', 'n', intText(row.found)));
    tr.appendChild(el('td', 'n', intText(row.guide)));
    tr.appendChild(el('td', 'n', intText(row.product)));
    tr.appendChild(el('td', 'n', intText(row.resolved)));
    body.appendChild(tr);
  });
  table.appendChild(body);
  box.appendChild(table);
}

function statRow(name, value) {
  var box = el('div', 'stat');
  box.appendChild(el('span', 'stat-name', name));
  box.appendChild(el('span', 'stat-value', value));
  return box;
}

function drawVoice(voice) {
  var box = byId('voiceStats');
  box.replaceChildren();
  box.appendChild(statRow('질문 수', intText(voice.asks)));
  box.appendChild(statRow('서버 답 비율', intText(voice.onlinePercent) + '%'));
  box.appendChild(statRow('평균 답 시간', (Number(voice.averageMs) / 1000).toFixed(1) + '초'));
}

function drawBattle(battle) {
  var box = byId('battleStats');
  box.replaceChildren();
  box.appendChild(statRow('대전 수', intText(battle.total)));
  box.appendChild(statRow('승·무·패', intText(battle.win) + ' · ' + intText(battle.draw) + ' · ' + intText(battle.lose)));
}

function propsText(props) {
  if (!props || typeof props !== 'object') return '';
  return Object.keys(props).map(function (key) {
    return key + '=' + String(props[key]);
  }).join(', ');
}

function drawRecent(recent) {
  var box = byId('recentTable');
  box.replaceChildren();
  if (recent.length === 0) {
    box.appendChild(el('p', 'empty', '아직 기록이 없어요'));
    return;
  }
  var table = el('table', 'tbl recent');
  var head = el('thead');
  var headRow = el('tr');
  headRow.appendChild(el('th', null, '시각'));
  headRow.appendChild(el('th', null, '기기'));
  headRow.appendChild(el('th', null, '이름'));
  headRow.appendChild(el('th', null, '속성'));
  head.appendChild(headRow);
  table.appendChild(head);
  var body = el('tbody');
  recent.slice(0, 200).forEach(function (row) {
    var safe = row && typeof row === 'object' ? row : {};
    var at = Number(safe.t);
    var when = Number.isFinite(at) && at > 0 ? new Date(at) : new Date();
    var tr = el('tr');
    tr.appendChild(el('td', 'mono', hhmmss(when)));
    tr.appendChild(el('td', 'mono', String(safe.d || '').slice(0, 6)));
    tr.appendChild(el('td', null, String(safe.n || '')));
    tr.appendChild(el('td', 'props', propsText(safe.p)));
    body.appendChild(tr);
  });
  table.appendChild(body);
  box.appendChild(table);
}

function draw() {
  var view = summarize(window.__mocaStats || {});
  drawCards(view.cards);
  drawDaily(view.daily);
  drawFunnel(view.funnel);
  drawKinds(view.kinds);
  drawVoice(view.voice);
  drawBattle(view.battle);
  drawRecent(view.recent);
  byId('demoBand').hidden = view.scope !== 'demo';
  byId('updatedAt').textContent = hhmmss(new Date()) + ' 에 읽음';
}

function stopTimer() {
  if (timer) { window.clearInterval(timer); timer = 0; }
}
function startTimer() {
  stopTimer();
  timer = window.setInterval(function () {
    if (document.hidden) return;
    load();
  }, REFRESH_MS);
}

function load() {
  if (!state.token) return;
  if (document.hidden) return;
  if (state.busy) return;
  state.busy = true;
  fetch('stats?days=' + state.days + '&scope=' + state.scope, {
    headers: { authorization: 'Bearer ' + state.token },
    cache: 'no-store',
  })
    .then(function (res) {
      if (res.status === 401) {
        state.token = '';
        writeStored('');
        stopTimer();
        showGate('비밀번호가 맞지 않아요');
        return null;
      }
      if (!res.ok) {
        setError('읽지 못했어요 (상태 ' + res.status + ')');
        return null;
      }
      return res.json();
    })
    .then(function (data) {
      if (!data) return;
      window.__mocaStats = data;
      draw();
      setError('');
    })
    .catch(function () {
      setError('서버에 닿지 못했어요');
    })
    .then(function () {
      state.busy = false;
    });
}

function setScope(scope) {
  state.scope = scope === 'demo' ? 'demo' : 'real';
  setPressed('scopeReal', state.scope === 'real');
  setPressed('scopeDemo', state.scope === 'demo');
  byId('demoBand').hidden = state.scope !== 'demo';
  load();
}
function setDays(days) {
  state.days = days;
  setPressed('range1', days === 1);
  setPressed('range7', days === 7);
  setPressed('range30', days === 30);
  load();
}
function submitToken() {
  var field = byId('tokenInput');
  var value = String(field.value || '').trim();
  if (!value) { setError('비밀번호를 넣어 주세요'); return; }
  state.token = value;
  writeStored(value);
  field.value = '';
  showDash();
  startTimer();
  load();
}

byId('gate').addEventListener('submit', function (event) {
  event.preventDefault();
  submitToken();
});
byId('tokenInput').addEventListener('keydown', function (event) {
  if (event.key === 'Enter') { event.preventDefault(); submitToken(); }
});
byId('scopeReal').addEventListener('click', function () { setScope('real'); });
byId('scopeDemo').addEventListener('click', function () { setScope('demo'); });
byId('range1').addEventListener('click', function () { setDays(1); });
byId('range7').addEventListener('click', function () { setDays(7); });
byId('range30').addEventListener('click', function () { setDays(30); });
document.addEventListener('visibilitychange', function () {
  if (!document.hidden && state.token) load();
});

(function boot() {
  var hash = String(window.location.hash || '');
  var matched = /^#token=(.+)$/.exec(hash);
  var fromHash = '';
  if (matched) {
    try { fromHash = decodeURIComponent(matched[1]); } catch (error) { fromHash = matched[1]; }
    // 주소창에 남은 비밀번호를 지운다. 지우지 못해도 화면은 그대로 쓴다.
    try {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    } catch (error) {
      // 주소를 못 바꿔도 아래에서 같은 값으로 진행한다
    }
  }
  var token = fromHash || readStored();
  if (!token) { showGate(''); return; }
  state.token = token;
  writeStored(token);
  showDash();
  startTimer();
  load();
})();
`;

const BODY = `
<header class="head">
  <h1>MOCA 운영</h1>
  <div class="controls" id="controls" hidden>
    <div class="group" role="group" aria-label="범위">
      <button type="button" id="scopeReal" aria-pressed="true">실제</button>
      <button type="button" id="scopeDemo" aria-pressed="false">데모</button>
    </div>
    <div class="group" role="group" aria-label="기간">
      <button type="button" id="range1" aria-pressed="false">오늘</button>
      <button type="button" id="range7" aria-pressed="true">7일</button>
      <button type="button" id="range30" aria-pressed="false">30일</button>
    </div>
    <span class="updated" id="updatedAt"></span>
  </div>
</header>

<form class="gate" id="gate">
  <div>
    <label for="tokenInput">비밀번호</label>
    <input id="tokenInput" type="password" autocomplete="current-password" spellcheck="false">
  </div>
  <button type="submit" id="tokenSubmit">들어가기</button>
</form>
<p class="error" id="errorBox" role="alert" hidden></p>

<div class="band" id="demoBand" hidden>데모 기록입니다. 실제 사용자의 기록이 아닙니다</div>

<main id="dash" hidden>
  <h2>요약</h2>
  <section class="cards">
    <div class="card" id="cardDevices"><span class="card-name">오늘 쓴 기기</span><span class="card-value num">0</span></div>
    <div class="card" id="cardInspections"><span class="card-name">점검</span><span class="card-value num">0</span></div>
    <div class="card" id="cardProblems"><span class="card-name">문제 발견</span><span class="card-value num">0</span></div>
    <div class="card" id="cardGuide"><span class="card-name">안내 누름</span><span class="card-value num">0</span></div>
    <div class="card" id="cardProduct"><span class="card-name">상품 누름</span><span class="card-value num">0</span></div>
    <div class="card card-lead" id="cardLeads"><span class="card-name">이어 준 건</span><span class="card-value num">0</span></div>
  </section>
  <p class="note">이어 준 건 = 안내 누름 + 상품 누름</p>

  <h2>날짜별</h2>
  <div id="dailyChart" class="chart"></div>
  <div class="legend"><span class="lg-guide">안내 누름</span><span class="lg-product">상품 누름</span></div>

  <div class="pair">
    <section>
      <h2>단계별</h2>
      <div id="funnel"></div>
    </section>
    <section>
      <h2>문제 종류별</h2>
      <div id="kindTable"></div>
    </section>
  </div>

  <h2>음성·대전</h2>
  <div id="voiceStats" class="stats"></div>
  <div id="battleStats" class="stats" style="margin-top: 8px"></div>

  <h2>최근 기록</h2>
  <div id="recentTable" class="wrap"></div>
</main>
`;

export function adminPage() {
  return [
    '<!doctype html>',
    '<html lang="ko">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>MOCA 운영</title>',
    `<style>${CSS}</style>`,
    '</head>',
    '<body>',
    BODY,
    '<script>',
    summarize.toString(),
    CLIENT,
    '</script>',
    '</body>',
    '</html>',
  ].join('\n');
}
