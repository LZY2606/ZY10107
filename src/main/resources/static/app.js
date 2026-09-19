'use strict';

const state = {
  health: null,
  rules: [],
  cases: [],
  selectedCaseId: null,
  analysisByCase: {},
  batches: [],
  compositions: [],
  clientVersion: 0,
  browserA: { version: null, detail: null, log: '' },
  browserB: { version: null, detail: null, log: '' },
  stepIndex: 0,
};

async function api(path, options = {}) {
  const opts = { method: options.method || 'GET', headers: { ...(options.headers || {}) } };
  if (options.query) {
    const qs = new URLSearchParams(options.query).toString();
    path += '?' + qs;
  }
  if (options.body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(options.body);
  }
  const res = await fetch('/api' + path, opts);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
  if (!res.ok) {
    const err = new Error((data && data.message) || res.statusText);
    err.payload = data;
    err.status = res.status;
    throw err;
  }
  if (data && typeof data === 'object' && data.version !== undefined) {
    state.clientVersion = data.version;
  }
  return data;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2).toLowerCase(), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  for (const child of children) {
    if (child == null) continue;
    node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return node;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function short(fp, n = 10) {
  return fp ? String(fp).slice(0, n) : '–';
}

// ---------- deterministic force-directed layout ----------
function layout(molecule, width = 380, height = 280) {
  const atoms = molecule.atoms;
  const pos = {};
  atoms.forEach((a, i) => {
    if (typeof a.x === 'number' && typeof a.y === 'number') {
      pos[a.id] = { x: 100 + a.x * 40, y: 150 + a.y * 40, fixed: true };
    } else {
      const angle = (i / Math.max(1, atoms.length)) * Math.PI * 2;
      pos[a.id] = { x: width / 2 + Math.cos(angle) * 80, y: height / 2 + Math.sin(angle) * 80, fixed: false };
    }
  });
  const neighbors = {};
  atoms.forEach(a => neighbors[a.id] = []);
  molecule.bonds.forEach(b => {
    neighbors[b.a]?.push(b.b);
    neighbors[b.b]?.push(b.a);
  });
  for (let iter = 0; iter < 220; iter++) {
    const next = {};
    for (const id of Object.keys(pos)) next[id] = { ...pos[id] };
    for (const a of atoms) {
      if (pos[a.id].fixed) continue;
      let fx = 0, fy = 0;
      for (const b of atoms) {
        if (a.id === b.id) continue;
        const dx = pos[a.id].x - pos[b.id].x;
        const dy = pos[a.id].y - pos[b.id].y;
        const d2 = dx * dx + dy * dy + 0.01;
        fx += (dx / d2) * 9000;
        fy += (dy / d2) * 9000;
      }
      for (const nid of neighbors[a.id]) {
        const dx = pos[nid].x - pos[a.id].x;
        const dy = pos[nid].y - pos[a.id].y;
        const d = Math.sqrt(dx * dx + dy * dy) || 1;
        const target = 62;
        fx += (dx / d) * (d - target) * 0.08;
        fy += (dy / d) * (d - target) * 0.08;
      }
      next[a.id].x = Math.max(24, Math.min(width - 24, pos[a.id].x + fx * 0.02));
      next[a.id].y = Math.max(24, Math.min(height - 24, pos[a.id].y + fy * 0.02));
    }
    for (const id of Object.keys(pos)) pos[id] = next[id];
  }
  return pos;
}

// ---------- SVG molecule rendering ----------
function renderMolecule(molecule, highlight = {}) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 400 300');
  svg.setAttribute('class', 'mol-svg');
  const pos = layout(molecule);

  const bondClass = (id) => highlight.addedBonds?.some(b => id === b) ? 'bond-add'
      : highlight.deletedBonds?.some(b => id === b) ? 'bond-del' : '';

  for (const b of molecule.bonds) {
    const p1 = pos[b.a], p2 = pos[b.b];
    if (!p1 || !p2) continue;
    const cls = bondClass(`${b.a}=${b.order}=${b.b}`);
    const draw = (offset) => {
      const dx = p2.x - p1.x, dy = p2.y - p1.y;
      const len = Math.sqrt(dx * dx + dy * dy) || 1;
      const px = (-dy / len) * offset, py = (dx / len) * offset;
      svg.appendChild(svgLine(p1.x + px, p1.y + py, p2.x + px, p2.y + py, cls));
    };
    if (b.order >= 2) draw(-3);
    draw(b.order === 3 ? 3 : 0);
    if (b.order === 3) draw(0);
  }

  // stereo wedges for tetrahedral centers: stereoOrder[0] is the "up" slot
  for (const a of molecule.atoms) {
    if (a.stereo && a.stereo !== 'NONE' && a.stereoOrder && a.stereoOrder.length >= 3) {
      const center = pos[a.id];
      const to = pos[a.stereoOrder[0]];
      if (center && to) {
        const wedge = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
        const dx = to.x - center.x, dy = to.y - center.y;
        const len = Math.sqrt(dx * dx + dy * dy) || 1;
        const ux = dx / len, uy = dy / len, px = -uy, py = ux;
        const w = 6;
        const points = a.stereo === 'UP'
            ? `${center.x + px * 1.5},${center.y + py * 1.5} ${center.x - px * 1.5},${center.y - py * 1.5} ${center.x + ux * 26},${center.y + uy * 26}`
            : `${center.x + px * w + ux * 18},${center.y + py * w + uy * 18} ${center.x - px * w + ux * 18},${center.y - py * w + uy * 18} ${center.x - ux * 0},${center.y - uy * 0}`;
        wedge.setAttribute('points', points);
        wedge.setAttribute('fill', '#c792ea');
        wedge.setAttribute('opacity', '0.85');
        svg.appendChild(wedge);
      }
    }
  }

  for (const a of molecule.atoms) {
    const p = pos[a.id];
    const color = highlight.addedAtoms?.includes(a.id) ? '#ffd166'
        : highlight.keptAtoms?.includes(a.id) ? '#46c46a'
        : highlight.unparticipatedAtoms?.includes(a.id) ? '#9aa7c2' : '#e6ebf5';
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('cx', p.x); circle.setAttribute('cy', p.y); circle.setAttribute('r', 15);
    circle.setAttribute('fill', '#0f1420');
    circle.setAttribute('stroke', color);
    circle.setAttribute('stroke-width', highlight.stereoChangedAtoms?.includes(a.id) ? 3.5 : 2);
    if (highlight.deletedAtoms?.includes(a.id)) {
      circle.setAttribute('stroke', '#ff6b6b');
      circle.setAttribute('stroke-dasharray', '4 3');
    }
    g.appendChild(circle);
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    text.setAttribute('x', p.x); text.setAttribute('y', p.y + 4);
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('font-size', '11');
    text.setAttribute('fill', color);
    const label = a.isotope ? `${a.isotope}${a.element()}` : a.element;
    text.textContent = label + (a.charge ? (a.charge > 0 ? '+' : '−') + Math.abs(a.charge) : '');
    g.appendChild(text);
    const idText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    idText.setAttribute('x', p.x); idText.setAttribute('y', p.y - 20);
    idText.setAttribute('text-anchor', 'middle');
    idText.setAttribute('font-size', '8.5');
    idText.setAttribute('fill', '#7b89aa');
    idText.textContent = a.id.startsWith('new:') ? a.id.split(':').slice(-1)[0] : a.id;
    g.appendChild(idText);
    svg.appendChild(g);
  }
  return svg;
}

function svgLine(x1, y1, x2, y2, cls) {
  const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  line.setAttribute('x1', x1); line.setAttribute('y1', y1);
  line.setAttribute('x2', x2); line.setAttribute('y2', y2);
  line.setAttribute('stroke', cls === 'bond-add' ? '#ffd166' : cls === 'bond-del' ? '#ff6b6b' : '#8fa3cc');
  line.setAttribute('stroke-width', 2);
  if (cls === 'bond-del') line.setAttribute('stroke-dasharray', '4 3');
  return line;
}

// ---------- rule / case lists ----------
function renderRules() {
  document.getElementById('rules-count').textContent = `（${state.rules.length}）`;
  const list = document.getElementById('rules-list');
  list.innerHTML = '';
  for (const rule of state.rules) {
    const card = el('div', { class: 'card' },
      el('div', {}, `${rule.name} `, el('b', {}, rule.version)),
      el('div', { class: 'meta' }, `ID: ${rule.ruleId}`),
      el('div', { class: 'meta' }, `指纹: ${short(rule.fingerprint, 16)}`),
      el('div', { class: 'meta' }, rule.def.description || '')
    );
    card.appendChild(el('button', {
      class: 'small', onclick: () => { document.getElementById('rule-json').value = JSON.stringify(rule.def, null, 2); }
    }, '查看 JSON'));
    list.appendChild(card);
  }
  populateRuleSelects();
}

function renderCases() {
  document.getElementById('cases-count').textContent = `（${state.cases.length}）`;
  const list = document.getElementById('cases-list');
  list.innerHTML = '';
  for (const c of state.cases) {
    const card = el('div', {
      class: 'card' + (state.selectedCaseId === c.caseId ? ' selected' : ''),
      onclick: () => selectCase(c.caseId)
    },
      el('div', {}, c.title),
      el('div', { class: 'meta' },
        el('span', { class: 'status-' + c.status }, c.status),
        ` · ${c.ruleVersion ? '规则 ' + c.ruleVersion : '未分析'} · 证书 ${c.certificates.length}`),
      el('div', { class: 'meta' }, `证据指纹: ${short(c.evidenceFingerprint, 14)}`)
    );
    list.appendChild(card);
  }
  populateBatchCases();
}

function populateRuleSelects() {
  for (const id of ['batch-rule', 'compose-first', 'compose-second']) {
    const sel = document.getElementById(id);
    const current = sel.value;
    sel.innerHTML = '';
    for (const r of state.rules) {
      sel.appendChild(el('option', { value: r.ruleId }, `${r.name} ${r.version}`));
    }
    if (current) sel.value = current;
  }
}

function populateBatchCases() {
  const box = document.getElementById('batch-case-pickers');
  box.innerHTML = '';
  for (const c of state.cases) {
    const label = el('label', {}, el('input', { type: 'checkbox', value: c.caseId }), ` ${c.title}`);
    box.appendChild(label);
  }
  const cs = document.getElementById('compose-case');
  cs.innerHTML = '';
  for (const c of state.cases) cs.appendChild(el('option', { value: c.caseId }, c.title));
}

async function selectCase(caseId) {
  state.selectedCaseId = caseId;
  renderCases();
  const detail = await api(`/cases/${caseId}`);
  state.analysisByCase[caseId] = detail.analysis || null;
  state.stepIndex = 0;
  renderWorkbench(detail);
}

// ---------- case workbench: stepwise transform ----------
function statusBadge(status) {
  return el('span', { class: 'status-' + status }, status);
}

function renderWorkbench(detail) {
  const box = document.getElementById('case-workbench');
  box.innerHTML = '';
  const c = detail.caseRecord;
  const analysis = detail.analysis;

  const head = el('div', {},
    el('h2', {}, c.title + ' '),
    el('span', { class: 'tag status-' + c.status }, c.status),
    el('div', { class: 'kv' },
      el('div', {}, '案例 ID：', el('b', {}, c.caseId)),
      el('div', {}, '证据指纹：', el('b', {}, c.evidenceFingerprint)),
      el('div', {}, '已锁定证书：', el('b', {}, String(c.certificates.length))),
      c.parentCaseId ? el('div', {}, '迁移自：', el('b', {}, c.parentCaseId)) : null
    )
  );
  box.appendChild(head);

  const controls = el('div', { class: 'row' });
  const select = el('select');
  for (const r of state.rules) select.appendChild(el('option', { value: r.ruleId }, `${r.name} ${r.version}`));
  if (c.ruleId) select.value = c.ruleId;
  controls.appendChild(el('label', {}, '规则 ', select));
  controls.appendChild(el('button', {
    onclick: async () => {
      try {
        const d = await api(`/cases/${c.caseId}/analyze`, { method: 'POST', query: { ruleId: select.value }, headers: { 'If-Match': state.clientVersion } });
        state.analysisByCase[c.caseId] = d.analysis;
        state.stepIndex = 0;
        renderWorkbench(d);
      } catch (e) { alert('分析失败：' + e.message + (e.payload ? '\n' + JSON.stringify(e.payload, null, 2) : '')); }
    }
  }, '运行分析'));
  controls.appendChild(el('button', {
    onclick: async () => {
      try {
        const d = await api(`/cases/${c.caseId}/revalidate`, { method: 'POST', query: { ruleId: select.value }, headers: { 'If-Match': state.clientVersion } });
        state.analysisByCase[c.caseId] = d.analysis;
        renderWorkbench(d);
      } catch (e) { alert('重新验证失败：' + e.message); }
    }
  }, '重新验证（规则变更后）'));
  controls.appendChild(el('button', {
    onclick: async () => {
      const newRuleId = prompt('输入目标新规则 ID：\n' + state.rules.map(r => r.ruleId + ' = ' + r.name + ' ' + r.version).join('\n'));
      if (!newRuleId) return;
      try {
        const report = await api(`/cases/${c.caseId}/migrate`, { method: 'POST', query: { newRuleId }, headers: { 'If-Match': state.clientVersion } });
        await refreshAll();
        alert(`迁移完成：${report.conclusion}\n新案例 ${report.newCaseId}`);
      } catch (e) { alert('迁移失败：' + e.message); }
    }
  }, '迁移到新规则版本'));
  box.appendChild(controls);

  if (c.certificates.length) {
    const certs = el('div', {}, el('h3', {}, '映射证书'));
    for (const cert of c.certificates) {
      certs.appendChild(el('div', { class: 'kv' },
        el('div', {}, '证书：', el('b', {}, cert.certificateId), ' 候选指纹 ', el('b', {}, short(cert.candidateFingerprint, 14))),
        el('div', {}, '规则版本 ', el('b', {}, cert.ruleVersion), ' 链哈希 ', el('b', {}, short(cert.chainHash, 14)))));
    }
    if (c.status !== 'CONFIRMED') {
      certs.appendChild(el('button', {
        class: 'primary',
        onclick: async () => {
          const d = await api(`/cases/${c.caseId}/confirm`, { method: 'POST', headers: { 'If-Match': state.clientVersion } });
          renderWorkbench(d);
        }
      }, '确认案例'));
    } else {
      certs.appendChild(el('p', { class: 'hint' }, '案例已确认。规则版本再变化时不会重写该结论；需要迁移请使用“迁移到新规则版本”，会生成一个新案例并保留旧案例。'));
    }
    box.appendChild(certs);
  }

  if (!analysis) {
    box.appendChild(el('p', { class: 'hint' }, '选择规则并运行分析。'));
    const evidenceWrap = el('div', {});
    evidenceWrap.appendChild(el('h3', {}, '原始证据'));
    evidenceWrap.appendChild(renderMolecule(c.evidence));
    box.appendChild(evidenceWrap);
    return;
  }

  const resultHead = el('div', {},
    el('h3', {}, '分析结果 ', statusBadge(analysis.status),
      el('span', { class: 'count' }, ` 枚举映射 ${analysis.enumeratedMappings}/${analysis.searchLimit}，候选 ${analysis.candidates.length}`)),
  );
  if (analysis.issues?.length) {
    resultHead.appendChild(el('pre', { class: 'result' }, analysis.issues.map(i => `[${i.code}] ${i.message}`).join('\n')));
  }
  box.appendChild(resultHead);
  renderCandidates(box, c, analysis);
}

function renderCandidates(box, caseRecord, analysis) {
  if (!analysis.candidates.length) {
    box.appendChild(el('p', { class: 'hint' }, '该状态下没有候选产物（NO_MATCH / INVALID_RULE / INVALID_INPUT 与有候选是不同状态，不会统一返回空列表）。'));
    return;
  }
  analysis.candidates.forEach((cand, index) => {
    const locked = caseRecord.certificates.some(cert => cert.candidateFingerprint === cand.fingerprint);
    const card = el('div', { class: 'candidate' + (index === 0 ? ' default' : '') });
    card.appendChild(el('div', {},
      el('b', {}, index === 0 ? '默认候选（规范排序首位）' : `候选 ${index + 1}`),
      el('span', { class: 'count' }, ` 映射数量 ${cand.mappingCount}（对称等价映射已归并）`),
      locked ? el('span', { class: 'tag keep' }, '已锁定') : null));
    card.appendChild(el('div', { class: 'kv' }, '结构指纹 ', el('b', {}, cand.fingerprint.slice(0, 24)), '…'));

    const legend = el('div', { class: 'legend' },
      el('span', {}, el('i', { class: 'dot', style: 'background:#46c46a' }), '保留'),
      el('span', {}, el('i', { class: 'dot', style: 'background:#ffd166' }), '新增'),
      el('span', {}, el('i', { class: 'dot', style: 'background:#ff6b6b' }), '删除'),
      el('span', {}, el('i', { class: 'dot', style: 'background:#c792ea' }), '立体变化'));
    card.appendChild(legend);

    const stageHost = el('div', {});
    card.appendChild(stageHost);
    const stages = buildStages(caseRecord.evidence, cand);
    const draw = () => {
      stageHost.innerHTML = '';
      const stage = stages[state.stepIndex] || stages[0];
      stageHost.appendChild(el('div', { class: 'kv' }, el('b', {}, stage.title), ' — ', stage.desc));
      const molWrap = el('div', { class: 'grid-two' });
      molWrap.appendChild(renderMolecule(stage.molecule, stage.highlight));
      const side = el('div', { class: 'result compact' });
      side.textContent = stage.notes.join('\n');
      molWrap.appendChild(side);
      stageHost.appendChild(molWrap);
    };
    const nav = el('div', { class: 'steps' },
      el('button', { onclick: () => { state.stepIndex = Math.max(0, state.stepIndex - 1); draw(); } }, '‹ 上一步'),
      el('span', { class: 'count' }, ''),
      el('button', { onclick: () => { state.stepIndex = Math.min(stages.length - 1, state.stepIndex + 1); draw(); } }, '下一步 ›'));
    card.appendChild(nav);
    draw();

    const meta = [];
    if (Object.keys(cand.elementDelta).length) meta.push(`元素差: ${JSON.stringify(cand.elementDelta)}`);
    else meta.push('元素守恒: 是');
    meta.push(`电荷差: ${cand.chargeDelta}`);
    meta.push(`未参与原子: ${cand.unparticipatedAtoms.length ? cand.unparticipatedAtoms.join(', ') : '无'}`);
    if (cand.issues.length) meta.push(...cand.issues.map(i => `[${i.code}] ${i.message}`));
    card.appendChild(el('pre', { class: 'result compact' }, meta.join('\n')));

    if (!locked) {
      card.appendChild(el('button', {
        onclick: async () => {
          try {
            const d = await api(`/cases/${caseRecord.caseId}/lock`, {
              method: 'POST', query: { candidateFingerprint: cand.fingerprint },
              headers: { 'If-Match': state.clientVersion }
            });
            state.analysisByCase[caseRecord.caseId] = d.analysis;
            renderWorkbench(d);
          } catch (e) {
            alert('锁定失败：' + e.message + (e.status === 409 ? '\n\n冲突内容：' + JSON.stringify(e.payload, null, 2) : ''));
          }
        }
      }, '锁定该映射后重新验证'));
    }
    box.appendChild(card);
  });
}

function buildStages(evidence, cand) {
  const stages = [];
  stages.push({
    title: '1. 匹配前', desc: '原始证据结构', molecule: evidence, highlight: {},
    notes: ['待匹配子图嵌入后，未参与匹配的原子保持原样。']
  });
  stages.push({
    title: '2. 匹配与删除', desc: '绿色=保留原子，红色虚线=被规则删除',
    molecule: cand.product,
    highlight: {
      keptAtoms: cand.keptAtoms,
      deletedAtoms: cand.deletedAtoms,
      unparticipatedAtoms: cand.unparticipatedAtoms,
      deletedBonds: cand.deletedBonds
    },
    notes: [
      '保留: ' + cand.keptAtoms.join(', '),
      '删除: ' + cand.deletedAtoms.join(', '),
      '删除键: ' + cand.deletedBonds.join(', ')
    ]
  });
  stages.push({
    title: '3. 新增与立体', desc: '黄色=新增原子/键，紫色描边=立体变化',
    molecule: cand.product,
    highlight: {
      keptAtoms: cand.keptAtoms,
      addedAtoms: cand.addedAtoms,
      addedBonds: cand.addedBonds,
      stereoChangedAtoms: cand.stereoChangedAtoms,
      unparticipatedAtoms: cand.unparticipatedAtoms
    },
    notes: [
      '新增: ' + cand.addedAtoms.join(', '),
      '新增键: ' + cand.addedBonds.join(', '),
      '立体变化: ' + (cand.stereoChangedAtoms.join(', ') || '无')
    ]
  });
  stages.push({
    title: '4. 产物', desc: '规范指纹 ' + cand.fingerprint.slice(0, 24) + '…',
    molecule: cand.product, highlight: {},
    notes: [
      '归并映射数量: ' + cand.mappingCount,
      '规范串: ' + cand.canonical
    ]
  });
  return stages;
}

// ---------- batch ----------
async function runBatch() {
  const ruleId = document.getElementById('batch-rule').value;
  const caseIds = [...document.querySelectorAll('#batch-case-pickers input:checked')].map(i => i.value);
  if (!caseIds.length) { alert('至少选择一个案例'); return; }
  try {
    const record = await api('/batch', {
      method: 'POST', body: { ruleId, caseIds }, headers: { 'If-Match': state.clientVersion }
    });
    renderBatch(record);
  } catch (e) { alert('批量失败：' + e.message); }
}

function renderBatch(record) {
  const box = document.getElementById('batch-results');
  box.innerHTML = '';
  const table = el('table');
  table.appendChild(el('thead', {}, el('tr', {},
    el('th', {}, '案例'), el('th', {}, '状态'), el('th', {}, '候选数'),
    el('th', {}, '映射枚举'), el('th', {}, '默认产物指纹'))));
  const tbody = el('tbody');
  record.caseIds.forEach((caseId, i) => {
    const result = record.results[i];
    tbody.appendChild(el('tr', {},
      el('td', {}, caseId),
      el('td', {}, el('span', { class: 'status-' + result.status }, result.status)),
      el('td', {}, String(result.candidates.length)),
      el('td', {}, `${result.enumeratedMappings}${result.limitReached ? ' (触顶)' : ''}`),
      el('td', {}, result.candidates[0] ? short(result.candidates[0].fingerprint, 14) : '–')));
  });
  table.appendChild(tbody);
  box.appendChild(el('h3', {}, `批量 ${record.batchId}`));
  box.appendChild(el('div', { class: 'kv' }, '默认展示：', el('b', {}, record.defaultCaseId || '无'), ' / ', short(record.defaultFingerprint, 14),
    '（由指纹排序决定，不受遍历顺序影响）'));
  box.appendChild(table);
}

// ---------- composition ----------
async function runCompose(persist) {
  const body = {
    caseId: document.getElementById('compose-case').value,
    firstRuleId: document.getElementById('compose-first').value,
    secondRuleId: document.getElementById('compose-second').value
  };
  try {
    const record = await api('/compose', {
      method: 'POST', query: { persist }, body,
      headers: persist ? { 'If-Match': state.clientVersion } : {}
    });
    renderCompose(record);
    if (persist) await refreshCompositions();
  } catch (e) { alert('组合失败：' + e.message); }
}

function renderCompose(record) {
  const box = document.getElementById('compose-out');
  box.innerHTML = '';
  const ok = record.status === 'OK';
  box.appendChild(el('h3', {}, '组合结果 ', el('span', { class: ok ? 'status-OK' : 'status-INVALID_RULE' }, record.status)));
  if (record.failedReason) box.appendChild(el('p', { class: 'result' }, record.failedReason));
  if (!ok) {
    box.appendChild(el('p', { class: 'hint' }, '组合失败时没有写入任何组合结果（无半个结果）。'));
  }
  const grid = el('div', { class: 'grid-two' });
  if (record.intermediate && record.intermediate.candidates[0]) {
    const a = el('div', {}, el('div', { class: 'kv' }, '中间产物（规则 A）'),
      renderMolecule(record.intermediate.candidates[0].product));
    grid.appendChild(a);
  }
  if (record.finalResult && record.finalResult.candidates[0]) {
    const b = el('div', {}, el('div', { class: 'kv' }, '最终产物（规则 B）'),
      renderMolecule(record.finalResult.candidates[0].product));
    grid.appendChild(b);
  }
  box.appendChild(grid);
}

async function refreshCompositions() {
  state.compositions = await api('/compositions');
  const box = document.getElementById('compose-history');
  box.innerHTML = '';
  for (const comp of state.compositions) {
    box.appendChild(el('div', { class: 'card' },
      el('div', {}, el('span', { class: 'status-' + (comp.status === 'OK' ? 'OK' : 'INVALID_RULE') }, comp.status),
        ' ', comp.compositionId),
      el('div', { class: 'meta' },
        `${comp.firstRuleId} → ${comp.secondRuleId}` + (comp.failedReason ? '：' + comp.failedReason : ''))));
  }
}

// ---------- conflict demo ----------
async function setupConflict() {
  await refreshAll();
  const acetone = state.cases.find(c => c.caseId === 'case-acetone') || state.cases[0];
  const rule = state.rules.find(r => r.name === 'KETO_ENOL' && r.version === '1.0') || state.rules[0];
  await api(`/cases/${acetone.caseId}/analyze`, {
    method: 'POST', query: { ruleId: rule.ruleId }, headers: { 'If-Match': state.clientVersion }
  });
  const detail = await api(`/cases/${acetone.caseId}`);
  state.browserA = { version: state.clientVersion, detail, log: '已基于当前版本加载并分析。' };
  state.browserB = { version: state.clientVersion, detail: JSON.parse(JSON.stringify(detail)), log: '乙在同一版本打开页面（快照）。' };
  paintBrowser('A');
  paintBrowser('B');
}

function paintBrowser(which) {
  const b = which === 'A' ? state.browserA : state.browserB;
  document.getElementById(`browser-${which.toLowerCase()}-version`).textContent = b.version ?? '–';
  const d = b.detail;
  if (!d) return;
  const cand = d.analysis?.candidates || [];
  document.getElementById(`browser-${which.toLowerCase()}-view`).textContent =
    `${d.caseRecord.title}\n状态: ${d.caseRecord.status}\n候选: ${cand.map((c, i) => `#${i + 1} fp=${short(c.fingerprint, 10)} 映射数=${c.mappingCount}`).join('\n') || '无'}`;
  document.getElementById(`browser-${which.toLowerCase()}-log`).textContent = b.log;
}

async function browserLock(which) {
  const b = which === 'A' ? state.browserA : state.browserB;
  const cands = b.detail.analysis.candidates;
  const pick = which === 'A' ? 0 : Math.min(1, cands.length - 1);
  const cand = cands[pick];
  try {
    const updated = await api(`/cases/${b.detail.caseRecord.caseId}/lock`, {
      method: 'POST', query: { candidateFingerprint: cand.fingerprint },
      headers: { 'If-Match': b.version }
    });
    b.detail = updated;
    b.log = `提交成功：锁定候选 ${short(cand.fingerprint, 10)}。`;
    paintBrowser(which);
  } catch (e) {
    b.log = `提交被拒（${e.status}）：${e.message}\n\n服务器当前内容：\n` + JSON.stringify(e.payload, null, 2);
    paintBrowser(which);
  }
}

async function conflictMerge() {
  const serverDetail = await api(`/cases/${state.browserB.detail.caseRecord.caseId}`);
  state.browserB.detail = serverDetail;
  state.browserB.version = state.clientVersion;
  state.browserB.log = '已重新加载：看到甲锁定的候选（冲突内容），现在可以在最新版本基础上重新合并提交。';
  paintBrowser('B');
}

// ---------- health / recovery ----------
async function refreshHealth() {
  state.health = await api('/health');
  const pill = document.getElementById('health-pill');
  pill.textContent = state.health.readOnly ? '存储只读（恢复模式）' : '存储正常';
  pill.className = 'pill' + (state.health.readOnly ? ' bad' : '');
  document.getElementById('version-pill').textContent = '事件版本 ' + state.health.version
      + ' · ' + short(state.health.headHash, 10);
  const info = document.getElementById('recovery-info');
  if (info) {
    info.textContent = JSON.stringify({
      status: state.health.status,
      version: state.health.version,
      headHash: state.health.headHash,
      readOnly: state.health.readOnly,
      recoveryError: state.health.recoveryError,
      quarantinedFrames: state.health.quarantinedFrames
    }, null, 2);
  }
}

async function refreshAll() {
  await refreshHealth();
  state.rules = await api('/rules');
  state.cases = await api('/cases');
  state.batches = await api('/batches');
  renderRules();
  renderCases();
  await refreshCompositions();
  if (state.selectedCaseId) {
    const d = await api(`/cases/${state.selectedCaseId}`);
    state.analysisByCase[state.selectedCaseId] = d.analysis || null;
    renderWorkbench(d);
  }
}

// ---------- init ----------
function sampleRuleJson() {
  return JSON.stringify({
    name: 'MY_RULE', version: '0.1',
    pattern: { atoms: [{ id: 'a', element: 'C', isotope: 0, charge: 0, stereo: 'NONE' }], bonds: [] },
    deleteAtoms: [], addAtoms: [], deleteBonds: [], addBonds: []
  }, null, 2);
}

window.addEventListener('DOMContentLoaded', async () => {
  document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(x => x.classList.remove('active'));
    t.classList.add('active');
    document.getElementById('tab-' + t.dataset.tab).classList.add('active');
  }));
  document.getElementById('rule-json').value = sampleRuleJson();
  document.getElementById('case-json').value = JSON.stringify({
    atoms: [{ id: 'c1', element: 'C', isotope: 0, charge: 0, stereo: 'NONE' }], bonds: []
  }, null, 2);

  document.getElementById('refresh-btn').onclick = refreshAll;
  document.getElementById('export-btn').onclick = () => window.location = '/api/export';
  document.getElementById('validate-rule-btn').onclick = async () => {
    try {
      const def = JSON.parse(document.getElementById('rule-json').value);
      const out = await api('/rules/validate', { method: 'POST', body: def });
      document.getElementById('rule-validate-out').textContent = JSON.stringify(out, null, 2);
    } catch (e) { document.getElementById('rule-validate-out').textContent = '解析失败：' + e.message; }
  };
  document.getElementById('save-rule-btn').onclick = async () => {
    try {
      const def = JSON.parse(document.getElementById('rule-json').value);
      const ruleId = document.getElementById('rule-id').value || undefined;
      const saved = await api('/rules', { method: 'POST', body: def, query: ruleId ? { ruleId } : undefined });
      await refreshAll();
      alert('规则已入库：' + saved.ruleId);
    } catch (e) { alert('入库失败：' + e.message); }
  };
  document.getElementById('save-case-btn').onclick = async () => {
    try {
      const evidence = JSON.parse(document.getElementById('case-json').value);
      const title = document.getElementById('case-title').value || '网页导入案例';
      const saved = await api('/cases', { method: 'POST', body: evidence, query: { title } });
      await refreshAll();
      selectCase(saved.caseId);
    } catch (e) { alert('证据接收失败：' + e.message); }
  };
  document.getElementById('batch-run-btn').onclick = runBatch;
  document.getElementById('compose-dryrun-btn').onclick = () => runCompose(false);
  document.getElementById('compose-save-btn').onclick = () => runCompose(true);
  document.getElementById('conflict-setup-btn').onclick = setupConflict;
  document.getElementById('browser-a-lock').onclick = () => browserLock('A');
  document.getElementById('browser-b-lock').onclick = () => browserLock('B');
  document.getElementById('conflict-merge-btn').onclick = conflictMerge;

  await refreshAll();
});
