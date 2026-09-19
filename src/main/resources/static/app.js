'use strict';

const $ = (id) => document.getElementById(id);
const api = async (path, opts = {}) => {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw { status: res.status, body: data };
  return data;
};
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const short = (fp) => fp ? fp.slice(0, 14) + '…' : '—';

const SAMPLES = {
  ketoLhs: {
    atoms: [
      { id: 'a', element: 'C' },
      { id: 'b', element: 'O', charge: 0 },
      { id: 'c', element: 'C' },
      { id: 'h', element: 'H' }
    ],
    bonds: [
      { from: 'a', to: 'b', order: '2' },
      { from: 'a', to: 'c', order: '1' },
      { from: 'c', to: 'h', order: '1' }
    ]
  },
  ketoRhs: {
    atoms: [
      { id: 'a', element: 'C' },
      { id: 'b', element: 'O' },
      { id: 'c', element: 'C' },
      { id: 'h', element: 'H' }
    ],
    bonds: [
      { from: 'a', to: 'b', order: '1' },
      { from: 'b', to: 'h', order: '1' },
      { from: 'a', to: 'c', order: '2' }
    ]
  },
  enolInput: {
    atoms: [
      { id: 'm1', element: 'C' },
      { id: 'm2', element: 'O' },
      { id: 'm3', element: 'C' },
      { id: 'm4', element: 'H' },
      { id: 'm5', element: 'H' },
      { id: 'm6', element: 'C' }
    ],
    bonds: [
      { from: 'm1', to: 'm2', order: '2' },
      { from: 'm1', to: 'm3', order: '1' },
      { from: 'm3', to: 'm4', order: '1' },
      { from: 'm1', to: 'm5', order: '1' },
      { from: 'm3', to: 'm6', order: '1' }
    ]
  }
};

function showMsg(el, text, kind = '') {
  el.className = 'msg ' + kind;
  el.textContent = text;
}

document.querySelectorAll('.tab').forEach((t) => {
  t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((x) => x.classList.remove('active'));
    document.querySelectorAll('.panel').forEach((x) => x.classList.remove('active'));
    t.classList.add('active');
    $('tab-' + t.dataset.tab).classList.add('active');
    if (t.dataset.tab === 'rules') loadRules();
    if (t.dataset.tab === 'cases') { loadRulesIntoSelects(); loadCases(); }
    if (t.dataset.tab === 'compose') loadRulesIntoSelects();
    if (t.dataset.tab === 'migrate') loadRulesIntoSelects();
  });
});

$('seedBtn').addEventListener('click', () => {
  $('ruleId').value = 'rule-ketoenol';
  $('ruleName').value = '酮-烯醇互变';
  $('lhsJson').value = JSON.stringify(SAMPLES.ketoLhs, null, 2);
  $('rhsJson').value = JSON.stringify(SAMPLES.ketoRhs, null, 2);
  $('caseInput').value = JSON.stringify(SAMPLES.enolInput, null, 2);
  $('compInput').value = JSON.stringify(SAMPLES.enolInput, null, 2);
});

async function saveRule() {
  let lhs, rhs;
  try { lhs = JSON.parse($('lhsJson').value); rhs = JSON.parse($('rhsJson').value); }
  catch (e) { return showMsg($('ruleSaveMsg'), 'JSON 解析失败: ' + e.message, 'err'); }
  try {
    const state = await api('/api/rules', {
      method: 'POST',
      body: { id: $('ruleId').value.trim() || null, name: $('ruleName').value, lhs, rhs },
    });
    const kind = state.valid ? 'ok' : 'err';
    const text = (state.valid ? '规则有效，已保存为 ' : '规则无效，仍已登记草稿：')
      + `${state.id} @v${state.version}\n指纹 ${state.fingerprint}`
      + (state.valid ? '' : '\n原因:\n' + state.validationReasons.join('\n'));
    showMsg($('ruleSaveMsg'), text, kind);
    await loadRules();
    await loadRulesIntoSelects();
  } catch (e) {
    showMsg($('ruleSaveMsg'), '提交失败: ' + (e.body?.message || e), 'err');
  }
}
$('saveRule').addEventListener('click', saveRule);

async function loadRules() {
  const rules = await api('/api/rules');
  const box = $('ruleList');
  if (!rules.length) { box.innerHTML = '<p class="small">尚无规则。点击“载入示例”快速开始。</p>'; return; }
  box.innerHTML = '';
  for (const r of rules) {
    const div = document.createElement('div');
    div.className = 'item';
    div.innerHTML = `
      <div class="row1"><span>${esc(r.name || r.id)} <span class="small">${esc(r.id)}</span></span>
        <span class="pill ${r.valid ? 'valid' : 'invalid'}">v${r.version} ${r.valid ? 'VALID' : 'INVALID'}</span></div>
      <div class="row2">指纹 ${short(r.fingerprint)}</div>`;
    div.addEventListener('click', () => showRuleVersions(r.id));
    box.appendChild(div);
  }
}

async function showRuleVersions(id) {
  const versions = await api(`/api/rules/${encodeURIComponent(id)}/versions`);
  const box = $('ruleList');
  const detail = document.createElement('div');
  detail.className = 'item';
  detail.innerHTML = '<div class="row2">' + versions.map((v) =>
    `<div>v${v.version} · ${v.valid ? '有效' : '无效'} · ${short(v.fingerprint)}
      ${v.valid ? '' : '<br><span class="invalid">' + esc(v.validationReasons.join('; ')) + '</span>'}</div>`
  ).join('') + '</div>';
  box.prepend(detail);
}

let RULE_CACHE = [];
async function loadRulesIntoSelects() {
  RULE_CACHE = await api('/api/rules');
  const opts = RULE_CACHE.map((r) =>
    `<option value="${esc(r.id)}">${esc(r.name || r.id)} (v${r.version})</option>`).join('');
  for (const sel of [$('caseRule'), $('compRule1'), $('compRule2'), $('migRule')]) {
    if (sel) sel.innerHTML = opts;
  }
  const first = RULE_CACHE[0];
  if (first) {
    $('migVersion').innerHTML = '';
    const vs = await api(`/api/rules/${encodeURIComponent(first.id)}/versions`);
    $('migVersion').innerHTML = vs.map((v) => `<option value="${v.version}">v${v.version}</option>`).join('');
  }
}
$('migRule')?.addEventListener('change', async () => {
  const id = $('migRule').value;
  const vs = await api(`/api/rules/${encodeURIComponent(id)}/versions`);
  $('migVersion').innerHTML = vs.map((v) => `<option value="${v.version}">v${v.version}</option>`).join('');
});

let CURRENT_CASE = null;
let CURRENT_CANDIDATE = 0;

async function runCase() {
  let input;
  try { input = JSON.parse($('caseInput').value); }
  catch (e) { return showMsg($('runMsg'), '输入 JSON 解析失败: ' + e.message, 'err'); }
  const body = {
    ruleId: $('caseRule').value,
    matchLimit: Number($('matchLimit').value) || 1000,
    input,
  };
  try {
    const { state, run } = await api('/api/cases', { method: 'POST', body });
    showMsg($('runMsg'), `状态 ${run.status}：${run.message}\n原始映射 ${run.rawMappingCount} · 规范候选 ${run.candidateCount}${run.capped ? '（已触顶）' : ''}`,
      run.status === 'OK' ? 'ok' : 'warn');
    await loadCases();
    openCase(state.id);
  } catch (e) {
    showMsg($('runMsg'), '运行失败: ' + (e.body?.message || e), 'err');
  }
}
$('runCase').addEventListener('click', runCase);

async function loadCases() {
  const cases = await api('/api/cases');
  const box = $('caseList');
  if (!cases.length) { box.innerHTML = '<p class="small">尚无案例。</p>'; return; }
  box.innerHTML = '';
  for (const c of cases) {
    const div = document.createElement('div');
    div.className = 'item';
    const locked = c.status === 'CONFIRMED';
    div.innerHTML = `
      <div class="row1"><span>${esc(c.id)}</span><span class="pill ${esc(c.status)}">${esc(c.status)}</span></div>
      <div class="row2">${esc(c.ruleVersionRef)} · 候选 ${c.candidates?.length || 0} · 输入 ${short(c.inputFingerprint)}
      ${locked ? ' · 🔒 已锁定 #' + c.lockedCandidateIndex : ''}</div>`;
    div.addEventListener('click', () => openCase(c.id));
    box.appendChild(div);
  }
}

async function openCase(id) {
  CURRENT_CASE = await api(`/api/cases/${encodeURIComponent(id)}`);
  try { window.__LAST_INPUT__ = await api(`/api/cases/${encodeURIComponent(id)}/input`); }
  catch { window.__LAST_INPUT__ = null; }
  CURRENT_CANDIDATE = CURRENT_CASE.lockedCandidateIndex ?? 0;
  $('caseDetail').classList.remove('hidden');
  $('detailTitle').textContent = `${CURRENT_CASE.id} · ${CURRENT_CASE.ruleVersionRef}`;
  const st = $('detailStatus');
  st.className = 'pill ' + CURRENT_CASE.status;
  st.textContent = CURRENT_CASE.status;
  renderCandidate();
}

function renderCandidate() {
  const c = CURRENT_CASE;
  const cands = c.candidates || [];
  if (!cands.length) {
    $('stepInfo').textContent = '无候选（参见状态与未决原因）';
    $('svgInput').innerHTML = ''; $('svgProduct').innerHTML = '';
    $('candidateMeta').innerHTML = ''; $('violationBox').innerHTML = '';
  } else {
    if (CURRENT_CANDIDATE >= cands.length) CURRENT_CANDIDATE = 0;
    const cand = cands[CURRENT_CANDIDATE];
    $('stepInfo').textContent = `候选 ${CURRENT_CANDIDATE + 1} / ${cands.length}（合并等价映射 ×${cand.multiplicity}）`;
    drawGraph($('svgInput'), originalInput(), {
      mapping: cand.mapping, retained: null, highlightMatched: true,
    });
    drawGraph($('svgProduct'), cand.product, {
      retained: new Set(Object.values(cand.mapping)),
      deleted: new Set(),
      added: new Set(cand.product.atoms.map((a) => a.id).filter((x) => String(x).startsWith('t'))),
      stereo: new Set((cand.stereoChanges || []).map((s) => s.split(' ')[1])),
    });
    $('candidateMeta').innerHTML = `
      <div class="kv">映射: <b>${esc(JSON.stringify(cand.mapping))}</b></div>
      <div class="kv">产物指纹: <b>${esc(cand.productFingerprint)}</b></div>
      <div class="kv">输入指纹: <b>${esc(cand.inputFingerprint)}</b></div>
      <div class="kv">轨道签名: <b>${esc(cand.orbitSignature)}</b></div>
      <div class="kv">等价映射数: <b>${cand.multiplicity}</b> · 派生于 <b>${esc(cand.derivedFromRuleVersion)}</b></div>
      <div class="kv">新增键: ${esc((cand.addedBonds || []).join('；') || '无')} · 删除键: ${esc((cand.removedBonds || []).join('；') || '无')}</div>
      <div class="kv">立体变化: ${esc((cand.stereoChanges || []).join('；') || '无')}</div>`;
    const vb = $('violationBox');
    vb.innerHTML = (cand.violations || []).map((v) =>
      `<div class="violation ${v.severity}"><b>${esc(v.severity)}</b> ${esc(v.code)} — ${esc(v.message)}</div>`
    ).join('') || '<div class="small">该候选通过元素守恒 / 电荷差 / 未参与结构检查。</div>';
  }
  renderLockState();
  renderUnresolved();
  $('lockVersion').value = c.version;
}

function originalInput() {
  // The server stores raw input as evidence; the case list doesn't embed it,
  // but products plus mapping are enough. We reconstruct input via /api only if
  // needed; here the UI seeded its own input. Fall back to product for layout.
  return window.__LAST_INPUT__ || JSON.parse($('caseInput').value);
}

function renderLockState() {
  const c = CURRENT_CASE;
  const locked = c.status === 'CONFIRMED';
  $('lockBtn').disabled = locked || !(c.candidates || []).length;
  $('unlockBtn').disabled = !locked;
  const cb = $('certBox');
  if (locked && c.certificate) {
    const cert = c.certificate;
    cb.classList.remove('hidden');
    cb.innerHTML = `<b>映射证书</b>
      <div class="kv">证书哈希: <b>${esc(cert.certificateHash)}</b></div>
      <div class="kv">规则版本: <b>${esc(cert.ruleVersionRef)}</b> · 规则指纹 <b>${short(cert.ruleFingerprint)}</b></div>
      <div class="kv">原始证据: <b>${esc(cert.inputEvidenceHash)}</b></div>
      <div class="kv">产物指纹: <b>${esc(cert.productFingerprint)}</b> · 候选 #${cert.candidateIndex} · 等价×${cert.multiplicity}</div>
      <div class="kv">锁定时间: <b>${new Date(cert.lockedAt).toLocaleString()}</b></div>`;
  } else {
    cb.classList.add('hidden');
    cb.innerHTML = '';
  }
}

function renderUnresolved() {
  const box = $('unresolvedBox');
  const reasons = CURRENT_CASE.unresolvedReasons || [];
  if (!reasons.length) { box.innerHTML = ''; return; }
  box.innerHTML = `<details open><summary>未决原因（${reasons.length}）</summary>
    ${reasons.map((r) => `<div class="violation ${r.startsWith('CANDIDATE') ? 'WARN' : 'ERROR'}">${esc(r)}</div>`).join('')}
  </details>`;
}

$('stepPrev').addEventListener('click', () => {
  if (!CURRENT_CASE) return;
  CURRENT_CANDIDATE = (CURRENT_CANDIDATE - 1 + (CURRENT_CASE.candidates?.length || 1))
    % (CURRENT_CASE.candidates?.length || 1);
  renderCandidate();
});
$('stepNext').addEventListener('click', () => {
  if (!CURRENT_CASE) return;
  CURRENT_CANDIDATE = (CURRENT_CANDIDATE + 1) % (CURRENT_CASE.candidates?.length || 1);
  renderCandidate();
});

$('lockBtn').addEventListener('click', async () => {
  try {
    CURRENT_CASE = await api(`/api/cases/${CURRENT_CASE.id}/lock`, {
      method: 'POST',
      body: { caseVersion: Number($('lockVersion').value), candidateIndex: CURRENT_CANDIDATE },
    });
    $('conflictBox').classList.add('hidden');
    CURRENT_CANDIDATE = CURRENT_CASE.lockedCandidateIndex;
    renderCandidate();
    await loadCases();
  } catch (e) {
    if (e.status === 409) {
      await showConflict(e.body);
    } else {
      alert('重新验证失败: ' + (e.body?.message || e));
    }
  }
});

$('unlockBtn').addEventListener('click', async () => {
  try {
    CURRENT_CASE = await api(`/api/cases/${CURRENT_CASE.id}/unlock`, {
      method: 'POST', body: { caseVersion: CURRENT_CASE.version },
    });
    renderCandidate(); await loadCases();
  } catch (e) { if (e.status === 409) await showConflict(e.body); else alert(e.body?.message || e); }
});

async function showConflict(body) {
  // The late browser sees the winner's content and can choose to re-merge.
  const box = $('conflictBox');
  box.classList.remove('hidden');
  const mine = Number($('lockVersion').value);
  box.innerHTML = `<b>冲突：该案例已被另一浏览器更新</b>
    <div class="small">你基于版本 v${mine}，服务器当前为 v${body?.context?.actual ?? '?'}。</div>
    <div class="small">${esc(body?.message || '')}</div>
    <button class="ghost" id="remergeBtn">拉取最新内容并重新合并</button>`;
  $('remergeBtn').addEventListener('click', async () => {
    await openCase(CURRENT_CASE.id);
    box.classList.add('hidden');
  });
}

/** Deterministic circular layout + bond/atom highlighting. Pure DOM, no network. */
function drawGraph(container, graph, opts) {
  container.innerHTML = '';
  if (!graph || !graph.atoms?.length) { container.innerHTML = '<p class="small">（空图）</p>'; return; }
  const n = graph.atoms.length;
  const R = 110, cx = 160, cy = 150;
  const pos = {};
  graph.atoms.forEach((a, i) => {
    const ang = -Math.PI / 2 + (2 * Math.PI * i) / n;
    pos[a.id] = [cx + R * Math.cos(ang), cy + R * Math.sin(ang)];
  });

  const matched = opts.highlightMatched && opts.mapping
    ? new Set(Object.values(opts.mapping)) : new Set();
  const stereoIds = opts.stereo || new Set();

  const svgNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('viewBox', `0 0 ${cx * 2} ${cy * 2}`);
  svg.setAttribute('width', '100%');
  svg.setAttribute('height', 320);

  const bondColor = (b) => {
    if (opts.added && (opts.added.has(b.from) || opts.added.has(b.to))) return 'var(--add)';
    return 'var(--muted)';
  };
  for (const b of graph.bonds || []) {
    const [x1, y1] = pos[b.from] || [0, 0];
    const [x2, y2] = pos[b.to] || [0, 0];
    const line = document.createElementNS(svgNS, 'line');
    line.setAttribute('x1', x1); line.setAttribute('y1', y1);
    line.setAttribute('x2', x2); line.setAttribute('y2', y2);
    line.setAttribute('stroke', bondColor(b));
    line.setAttribute('stroke-width', b.order === '2' ? 4 : b.order === '3' ? 6 : 2.5);
    if (matched.has(b.from) && matched.has(b.to)) line.setAttribute('stroke', 'var(--kept)');
    svg.appendChild(line);
    const mid = document.createElementNS(svgNS, 'text');
    mid.setAttribute('x', (x1 + x2) / 2); mid.setAttribute('y', (y1 + y2) / 2 - 4);
    mid.setAttribute('fill', 'var(--muted)'); mid.setAttribute('font-size', 10);
    mid.setAttribute('text-anchor', 'middle');
    mid.textContent = b.order + (b.stereo || '');
    svg.appendChild(mid);
  }

  for (const a of graph.atoms) {
    const [x, y] = pos[a.id];
    const g = document.createElementNS(svgNS, 'g');
    const circle = document.createElementNS(svgNS, 'circle');
    circle.setAttribute('cx', x); circle.setAttribute('cy', y); circle.setAttribute('r', 17);
    let fill = 'var(--panel)';
    if (opts.retained && opts.retained.has(a.id)) fill = 'var(--kept)';
    if (opts.added && opts.added.has(a.id)) fill = 'var(--add)';
    if (matched.has(a.id)) fill = 'var(--kept)';
    circle.setAttribute('fill', fill);
    circle.setAttribute('stroke', stereoIds.has(a.id) ? 'var(--stereo)' : 'var(--line)');
    circle.setAttribute('stroke-width', stereoIds.has(a.id) ? 3 : 1.5);
    g.appendChild(circle);
    const label = document.createElementNS(svgNS, 'text');
    label.setAttribute('x', x); label.setAttribute('y', y + 4);
    label.setAttribute('text-anchor', 'middle');
    label.setAttribute('font-size', 12);
    label.setAttribute('fill', '#0a0f16');
    label.textContent = (a.isotope ? a.isotope : '') + a.element
      + (a.charge ? (a.charge > 0 ? '+' + a.charge : a.charge) : '');
    g.appendChild(label);
    const idlabel = document.createElementNS(svgNS, 'text');
    idlabel.setAttribute('x', x); idlabel.setAttribute('y', y + 32);
    idlabel.setAttribute('text-anchor', 'middle');
    idlabel.setAttribute('font-size', 9); idlabel.setAttribute('fill', 'var(--muted)');
    idlabel.textContent = a.id + (a.stereo ? ' ' + a.stereo : '');
    g.appendChild(idlabel);
    svg.appendChild(g);
  }
  container.appendChild(svg);
}

$('batchRun').addEventListener('click', async () => {
  let items;
  try {
    const parsed = JSON.parse($('batchJson').value);
    items = Array.isArray(parsed) ? parsed : parsed.items;
  } catch (e) { return $('batchSummary').textContent = 'JSON 解析失败: ' + e.message; }
  try {
    const res = await api('/api/batch', { method: 'POST', body: { items } });
    $('batchSummary').className = 'msg ok';
    $('batchSummary').textContent = `共 ${res.total}：成功 ${res.ok}，无匹配 ${res.noMatch}，`
      + `触顶 ${res.limitReached}，规则无效 ${res.invalidRule}，输入无效 ${res.invalidInput}`;
    const box = $('batchResults');
    box.innerHTML = '';
    res.results.forEach((r, i) => {
      const div = document.createElement('div');
      div.className = 'item';
      const cands = r.state.candidates || [];
      div.innerHTML = `<div class="row1"><span>#${i + 1} ${esc(r.state.id)}</span>
        <span class="pill ${esc(r.state.status)}">${esc(r.state.status)}</span></div>
        <div class="row2">原始映射 ${r.run.rawMappingCount} · 候选 ${r.run.candidateCount}
        ${cands.map((c) => `<br>　#${c.index} ${short(c.productFingerprint)} ×${c.multiplicity}`).join('')}</div>`;
      box.appendChild(div);
    });
  } catch (e) {
    $('batchSummary').className = 'msg err';
    $('batchSummary').textContent = '批量失败: ' + (e.body?.message || e);
  }
});

$('compRun').addEventListener('click', async () => {
  let input;
  try { input = JSON.parse($('compInput').value); }
  catch (e) { return showMsg($('compMsg'), '输入 JSON 解析失败: ' + e.message, 'err'); }
  try {
    const res = await api('/api/compose', {
      method: 'POST',
      body: {
        rule1Id: $('compRule1').value, rule2Id: $('compRule2').value,
        matchLimit: Number($('compLimit').value) || 1000, input,
      },
    });
    const ok = res.state.succeeded;
    showMsg($('compMsg'),
      `组合状态 ${res.state.status}：${res.state.reason || ''}\n`
      + (res.first ? `第一步: ${res.first.status} 候选 ${res.first.candidateCount}\n` : '')
      + (res.second ? `第二步: ${res.second.status} 候选 ${res.second.candidateCount}` : ''),
      ok ? 'ok' : 'err');
    await loadComposeHistory();
  } catch (e) {
    showMsg($('compMsg'), '组合失败（未产生半个结果）: ' + (e.body?.message || e), 'err');
  }
});

async function loadComposeHistory() {
  const list = await api('/api/compose/history');
  const box = $('compHistory');
  box.innerHTML = list.slice().reverse().map((c) =>
    `<div class="item"><div class="row1"><span>${esc(c.firstRuleVersionRef)} → ${esc(c.secondRuleVersionRef)}</span>
      <span class="pill ${c.succeeded ? 'OK' : 'INVALID_RULE'}">${esc(c.status)}</span></div>
      <div class="row2">${esc(c.reason || '')}${c.tempIdsReferenced?.length ? '<br>' + esc(c.tempIdsReferenced.join('; ')) : ''}</div></div>`
  ).join('') || '<p class="small">尚无组合记录。</p>';
}

$('migRun').addEventListener('click', async () => {
  const ruleId = $('migRule').value, version = Number($('migVersion').value);
  try {
    const report = await api(`/api/migrate/rules/${encodeURIComponent(ruleId)}/to/${version}`, { method: 'POST' });
    const box = $('migResults');
    if (!report.cases.length) { box.innerHTML = '<p class="small">没有使用该规则的案例。</p>'; return; }
    box.innerHTML = report.cases.map((cm) => `
      <div class="item">
        <div class="row1"><span>${esc(cm.caseId)}</span>
          <span class="pill ${cm.conclusionChanged ? 'LIMIT_REACHED' : 'OK'}">
          ${cm.oldStatus} → ${cm.newStatus}</span></div>
        <div class="row2">${cm.conclusionChanged ? '结论发生变化' : '结论一致'}</div>
        ${cm.changes.length ? `<div class="violation WARN">${cm.changes.map(esc).join('<br>')}</div>` : ''}
      </div>`).join('');
  } catch (e) {
    $('migResults').innerHTML = `<div class="msg err">迁移报告失败: ${esc(e.body?.message || e)}</div>`;
  }
});

// Seed the batch textarea with a reusable template whenever input changes.
function init() {
  loadRules();
  loadRulesIntoSelects();
  loadCases();
  loadComposeHistory();
  $('batchJson').value = JSON.stringify({
    items: [
      { name: '示例', ruleId: 'rule-ketoenol', input: SAMPLES.enolInput }
    ]
  }, null, 2);
}
init();
