// 계획 v10 2.5: /stats 응답을 대시보드 화면에 쓸 값으로 바꾸는 순수 함수.
// 페이지가 이 함수의 소스를 그대로 실어 보내므로 바깥 변수를 쓰지 않는다.
export function summarize(stats) {
  var source = stats !== null && typeof stats === 'object' ? stats : {};
  var totals = source.totals !== null && typeof source.totals === 'object' ? source.totals : {};
  var count = function (key) {
    var value = Number(totals[key]);
    return Number.isFinite(value) ? value : 0;
  };
  var pick = function (bucket, key) {
    var value = Number(bucket[key]);
    return Number.isFinite(value) ? value : 0;
  };

  var dayRows = Array.isArray(source.days) ? source.days : [];
  var daily = dayRows.map(function (row) {
    var safe = row !== null && typeof row === 'object' ? row : {};
    var raw = typeof safe.day === 'string' ? safe.day : '';
    var day = raw.length === 8 ? raw.slice(4, 6) + '/' + raw.slice(6, 8) : raw;
    var counts = safe.counts !== null && typeof safe.counts === 'object' ? safe.counts : {};
    return {
      day: day,
      guide: pick(counts, 'fix_tap'),
      product: pick(counts, 'product_tap'),
      opens: pick(counts, 'app_open'),
    };
  });
  var lastDay = dayRows.length > 0 ? dayRows[dayRows.length - 1] : null;
  var todayDevices = lastDay && typeof lastDay === 'object' ? Number(lastDay.devices) : 0;
  if (!Number.isFinite(todayDevices)) todayDevices = 0;

  var guide = count('fix_tap');
  var product = count('product_tap');
  var cards = {
    devices: todayDevices,
    inspections: count('inspection_done'),
    problems: count('problem_found'),
    guide: guide,
    product: product,
    leads: guide + product,
  };

  var funnel = [
    { key: 'problem_found', label: '문제 발견' },
    { key: 'fix_tap', label: '안내 누름' },
    { key: 'navi_opened', label: '내비 열림' },
    { key: 'route_started', label: '길안내 시작' },
    { key: 'destination_arrived', label: '도착' },
    { key: 'problem_resolved', label: '문제 해소' },
  ].map(function (step) {
    return { key: step.key, label: step.label, count: count(step.key) };
  });

  var kindMap = {};
  Object.keys(totals).forEach(function (key) {
    var cut = key.indexOf('|');
    if (cut < 0) return;
    var name = key.slice(0, cut);
    var kind = key.slice(cut + 1);
    if (kind.length === 0) return;
    if (name !== 'problem_found' && name !== 'fix_tap' && name !== 'product_tap' && name !== 'problem_resolved') return;
    if (!Object.prototype.hasOwnProperty.call(kindMap, kind)) {
      kindMap[kind] = { kind: kind, found: 0, guide: 0, product: 0, resolved: 0 };
    }
    var row = kindMap[kind];
    if (name === 'problem_found') row.found += count(key);
    else if (name === 'fix_tap') row.guide += count(key);
    else if (name === 'product_tap') row.product += count(key);
    else row.resolved += count(key);
  });
  var kinds = Object.keys(kindMap).sort().map(function (kind) {
    return kindMap[kind];
  });

  var byCountThenName = function (nameKey) {
    return function (a, b) {
      if (b.count !== a.count) return b.count - a.count;
      return a[nameKey] < b[nameKey] ? -1 : a[nameKey] > b[nameKey] ? 1 : 0;
    };
  };
  var actions = [];
  var screens = [];
  var intents = [];
  Object.keys(totals).forEach(function (key) {
    var value = count(key);
    var cut = key.indexOf('|');
    if (cut < 0) {
      if (key.indexOf('voice_ms_') === 0) return;
      if (key.length === 0) return;
      actions.push({ name: key, count: value });
      return;
    }
    var name = key.slice(0, cut);
    var kind = key.slice(cut + 1);
    if (kind.length === 0) return;
    if (name === 'screen_view') { screens.push({ screen: kind, count: value }); return; }
    if (name === 'voice_ask' && kind !== 'online' && kind !== 'offline') intents.push({ intent: kind, count: value });
  });
  actions.sort(byCountThenName('name'));
  screens.sort(byCountThenName('screen'));
  intents.sort(byCountThenName('intent'));

  var asks = count('voice_ask');
  var online = count('voice_ask|online');
  var offline = count('voice_ask|offline');
  var msSum = count('voice_ms_sum');
  var msCount = count('voice_ms_n');
  var voice = {
    asks: asks,
    online: online,
    offline: offline,
    onlinePercent: asks > 0 ? Math.round((online * 100) / asks) : 0,
    averageMs: msCount > 0 ? Math.round(msSum / msCount) : 0,
  };
  var battle = {
    total: count('battle_done'),
    win: count('battle_done|win'),
    draw: count('battle_done|draw'),
    lose: count('battle_done|lose'),
  };

  return {
    scope: source.scope === 'demo' ? 'demo' : 'real',
    cards: cards,
    daily: daily,
    funnel: funnel,
    kinds: kinds,
    actions: actions,
    screens: screens,
    intents: intents,
    voice: voice,
    battle: battle,
    recent: Array.isArray(source.recent) ? source.recent : [],
  };
}
