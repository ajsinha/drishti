/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
/* The Query panel (docs/architecture/BI_DESIGN.md sections 3.4, 3.6 and 8.1): the selected visual's question as a model query (MQ, YAML) or as Drishti
   SQL (DSQL), side by side with one switch. Both are one question and compile to one plan on the server: Run as SQL sends the SQL to /bi/api/sql and says
   whether its plan is the one the visual's query ran (the plan hash, when the server gives one). The SQL text is written here from the model query (a
   printer, not a parser: the console never reads SQL for meaning); the server checks and runs it. Also B.mq.sql for Analyse's starter cells. */
(function () {
  'use strict';
  // the product's name from configuration (ui.product, on <body data-product>); its SQL is "<product> SQL"
  const PRODUCT = (document.body && document.body.dataset.product) || '', SQL_NAME = (PRODUCT + ' SQL').trim();
  const B = window.drishtiBi;
  if (!B || !B.mq) { return; }
  const h = B.h;

  const lit = (v, type) => {
    if (v == null) { return 'NULL'; }
    if (typeof v === 'number' || typeof v === 'boolean') { return String(v).toUpperCase() === 'TRUE' || String(v).toUpperCase() === 'FALSE' ? String(v).toUpperCase() : String(v); }
    const s = '\'' + String(v).replace(/'/g, '\'\'') + '\'';
    return type === 'date' && /^\d{4}-\d{2}-\d{2}$/.test(String(v)) ? 'DATE ' + s : s;
  };
  const PARTS = { year: 'YEAR', quarter: 'QUARTER', month: 'MONTH', day: 'DAY', week: 'WEEK' };
  /** A field as SQL: trade.desk, or a date part as a function (trade.tradeDate.month is MONTH(trade.tradeDate)). */
  const col = (f) => { const p = String(f).split('.'); return p.length === 3 && PARTS[p[2]] ? PARTS[p[2]] + '(' + p[0] + '.' + p[1] + ')' : String(f); };
  const ident = (n) => (/^[A-Za-z_][A-Za-z0-9_]*$/.test(n) ? n : '"' + String(n).replace(/"/g, '""') + '"');
  const measureRef = (n) => 'MEASURE(\'' + String(n).replace(/'/g, '\'\'') + '\')';
  const OPS = { eq: '=', ne: '<>', lt: '<', le: '<=', gt: '>', ge: '>=' };

  function cond(w, typeOf, measure) {
    const c = measure ? measureRef(w.measure) : col(w.field), t = typeOf(w.field);
    const vals = (w.values || []).map((v) => lit(v, t));
    switch (w.op) {
      case 'in': return c + ' IN (' + vals.join(', ') + ')';
      case 'not-in': return c + ' NOT IN (' + vals.join(', ') + ')';
      case 'between': return c + ' BETWEEN ' + lit((w.values || [])[0], t) + ' AND ' + lit((w.values || [])[1], t);
      case 'contains': return c + ' LIKE ' + lit('%' + w.value + '%');
      case 'starts-with': return c + ' LIKE ' + lit(w.value + '%');
      case 'is-null': return c + ' IS NULL';
      case 'not-null': return c + ' IS NOT NULL';
      case 'relative': return '/* ' + c + ' in the window ' + (w.window || w.value) + ' (worked out on the server) */ TRUE';
      case 'top': return '/* top ' + w.n + ' of ' + c + ' by ' + w.by + ' */ TRUE';
      default: return c + ' ' + (OPS[w.op] || '=') + ' ' + lit(w.value, t);
    }
  }

  /**
   * A DEL measure in its SQL form, for the SQL view, or null when it has none (a where=, a conditional, a time or window function, ratio, ...):
   * measure('x') is MEASURE('x'), sum/avg/min/max/count are SUM/AVG/MIN/MAX/COUNT, distinct(x) is COUNT(DISTINCT x), == is =, != is <>, and/or/not and
   * True/False/None are AND/OR/NOT and TRUE/FALSE/NULL. A token printer over the text; the server parses what it is sent.
   */
  function delToSql(expr) {
    const text = String(expr || '');
    if (/\bwhere\s*=|\bby\s*=|\bdesc\s*=|\bif\b|\belse\b|\bin\b|\bis\b|\*\*|\/\//.test(text)) { return null; }
    const FN = { sum: 'SUM', avg: 'AVG', min: 'MIN', max: 'MAX', count: 'COUNT', abs: 'ABS', round: 'ROUND', coalesce: 'COALESCE', upper: 'UPPER', lower: 'LOWER', measure: 'MEASURE' };
    const WORD = { and: 'AND', or: 'OR', not: 'NOT', True: 'TRUE', False: 'FALSE', None: 'NULL' };
    let out = '', ok = true;
    const re = /\s+|'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_.]*|\d[\d_]*(?:\.\d+)?(?:e[+-]?\d+)?|==|!=|<=|>=|[-+*/%(),<>]/g;
    let m, last = 0;
    while ((m = re.exec(text))) {
      if (m.index !== last) { ok = false; break; }
      last = re.lastIndex;
      const t = m[0], next = text.slice(last).match(/^\s*\(/);
      if (/^\s+$/.test(t)) { out += ' '; } else if (t[0] === '"') { out += '\'' + t.slice(1, -1).replace(/'/g, '\'\'') + '\''; } else if (/^[A-Za-z_]/.test(t) && next) {
        if (t === 'distinct') { out += 'COUNT(DISTINCT '; re.lastIndex = last + next[0].length; last = re.lastIndex; continue; }
        if (!FN[t]) { ok = false; break; }
        out += FN[t];
      } else if (WORD[t]) { out += WORD[t]; } else if (t === '==') { out += '='; } else if (t === '!=') { out += '<>'; } else if (/^\d/.test(t)) { out += t.replace(/_/g, ''); } else { out += t; }
    }
    return ok && last === text.length ? out.trim() : null;
  }
  B.mq.delToSql = delToSql;

  /**
   * Drishti SQL for a model query: SELECT fields and MEASURE('…'), FROM the fact the query reads with JOINs to the other aliases (the model's
   * relationships; no ON), WHERE, GROUP BY, HAVING, ORDER BY, LIMIT, and AS OF for a past date. `fields` is the model in the Fields shape (tables, measures).
   */
  B.mq.sql = function (mq, fields) {
    if (!mq || !mq.select) { return ''; }
    const tables = (fields && fields.tables) || [];
    const typeOf = (f) => { const p = String(f || '').split('.'); const t = tables.find((x) => x.name === p[0]); const c = t && t.columns.find((x) => x.name === p[1]); return c ? c.type : ''; };
    const used = [];
    const use = (a) => { if (a && tables.some((t) => t.name === a) && used.indexOf(a) < 0) { used.push(a); } };
    (mq.select.measures || []).forEach((m) => {
      const def = ((mq.define || {})[m]) || ((fields && fields.measures) || []).filter((x) => x.name === m).map((x) => x.expr)[0] || '';
      (String(def).match(/\b([A-Za-z_][A-Za-z0-9_]*)\./g) || []).forEach((x) => use(x.slice(0, -1)));
    });
    (mq.select.fields || []).forEach((f) => use(String(f).split('.')[0]));
    (mq.where || []).forEach((w) => use(String(w.field || '').split('.')[0]));
    if (!used.length) { const fact = tables.find((t) => t.kind === 'fact') || tables[0]; if (fact) { used.push(fact.name); } }
    used.sort((a, b) => ((tables.find((t) => t.name === a) || {}).kind === 'fact' ? 0 : 1) - ((tables.find((t) => t.name === b) || {}).kind === 'fact' ? 0 : 1));
    const notes = [];
    const sel = (mq.select.fields || []).map((f) => col(f) + (String(f).split('.').length === 3 ? ' AS ' + ident(String(f).split('.').slice(1).join('_')) : ''))
      .concat((mq.select.measures || []).map((m) => {
        if (!(mq.define && mq.define[m])) { return measureRef(m) + ' AS ' + ident(m); }
        const sql = delToSql(mq.define[m]);                         // a local measure in its SQL form, when it has one (SUM, MEASURE, arithmetic)
        if (sql) { return sql + ' AS ' + ident(m); }
        notes.push('-- ' + m + ' is this report\'s own measure, written in DEL: ' + mq.define[m]);
        return measureRef(m) + ' AS ' + ident(m);
      }));
    const lines = notes.concat(['SELECT ' + (sel.join(',\n       ') || '*')]);
    const asOf = mq.time && mq.time['as-of'] ? ' AS OF ' + lit(mq.time['as-of'], 'date') : mq.time && mq.time.live ? ' FOR LIVE' : '';
    lines.push('FROM   ' + (used[0] || 'model') + asOf);
    used.slice(1).forEach((a) => lines.push('JOIN   ' + a));
    if ((mq.where || []).length) { lines.push('WHERE  ' + mq.where.map((w) => cond(w, typeOf, false)).join('\n  AND  ')); }
    if ((mq.select.fields || []).length && (mq.select.measures || []).length) { lines.push('GROUP  BY ' + mq.select.fields.map(col).join(', ')); }
    if ((mq.having || []).length) { lines.push('HAVING ' + mq.having.map((w) => cond(w, typeOf, true)).join('\n  AND  ')); }
    if ((mq.sort || []).length) { lines.push('ORDER  BY ' + mq.sort.map((s) => ((mq.select.measures || []).indexOf(s.by) >= 0 ? ident(s.by) : col(s.by)) + (s.desc ? ' DESC' : '')).join(', ')); }
    if (mq.limit) { lines.push('LIMIT  ' + mq.limit); }
    return lines.join('\n');
  };

  /** Runs one DSQL statement on the server over a model: {columns, rows, rowCount, meta} as the reader. */
  B.sql = (sql, model, limit) => B.api('POST', '/sql', { sql, model, limit: limit || undefined });

  /**
   * The panel: a details element under the Fields pane (or any host) with MQ and SQL views of the current question.
   * @param host where it goes; opts {ref, current: () => mq, fields: () => Fields shape, on: (event, fn) to redraw}
   */
  B.queryPanel = function (host, opts) {
    let view = 'mq';
    const pre = h('pre', { class: 'bi-body mono', 'data-visual-query-text': '', tabindex: '0' });
    const views = h('div', { class: 'bi-qp-views', role: 'group', 'aria-label': 'Show the query as' });
    const btns = {};
    [['mq', 'Model query (MQ)'], ['sql', 'SQL']].forEach(([k, t]) => {
      const b = h('button', { type: 'button', class: 'bi-qp-view', 'data-qp-view': k, 'aria-pressed': k === view ? 'true' : 'false', text: t });
      b.addEventListener('click', () => { view = k; Object.keys(btns).forEach((x) => btns[x].setAttribute('aria-pressed', x === view ? 'true' : 'false')); draw(); });
      btns[k] = b;
      views.appendChild(b);
    });
    const run = h('button', { type: 'button', class: 'btn-pill btn-ghost bi-small', 'data-qp-run': '', hidden: true }, B.icon('play'), ' Run as SQL');
    const out = h('div', { class: 'bi-sql-meta', 'data-qp-out': '', 'aria-live': 'polite' });
    const box = h('details', { class: 'bi-md-query', 'data-visual-query': '' }, h('summary', { text: 'Query of the selected visual' }),
      h('p', { class: 'text-muted-d bi-small-note', text: 'One question, two ways to write it: the server compiles both to the same plan and answers them as you.' }), views, pre, run, out);
    host.appendChild(box);
    let shown = null;
    function draw() {
      const mq = opts.current();
      const sig = JSON.stringify(mq) + view;
      if (sig !== shown) { B.clear(out); shown = sig; }                     // the last run's answer stays while the question is the same
      run.hidden = view !== 'sql' || !mq;
      if (!mq) { pre.textContent = 'Choose a visual: its query (what the server is asked, as you) shows here.'; return; }
      pre.textContent = view === 'mq' ? B.mq.yaml(mq) : B.mq.sql(mq, opts.fields());
    }
    run.addEventListener('click', () => {
      const mq = opts.current();
      if (!mq) { return; }
      const body = { sql: B.mq.sql(mq, opts.fields()), model: opts.ref, limit: mq.limit || undefined };
      // with BI runs on the page (bi-runs.js), the SQL is asked with the inline wait: done in time it answers as before, longer it shows the run card here
      const asked = () => (B.runs && B.ask ? B.ask('/sql', body, 2) : B.sql(body.sql, body.model, body.limit).then((a) => ({ answer: a })));
      B.busy(run, () => asked().then((g) => {
        if (g.answer !== undefined) { return drawAnswer(g.answer); }
        const host = h('div', { class: 'bi-qp-run', 'data-qp-run-card': '' });
        B.clear(out).appendChild(host);
        B.runs.settle(g, { el: host, body: host }, { title: 'Run as SQL', reask: (w, extra) => B.ask('/sql', Object.assign({}, body, extra || {}), w) }).then((a) => { if (a) { drawAnswer(a); } });
        return null;
      }).catch((e) => { B.clear(out).appendChild(h('span', { class: 'bi-bad', 'data-qp-error': e.code || '', text: e.status === 404 ? 'This server does not run ' + SQL_NAME + ' yet.' : e.message })); }));
    });
    function drawAnswer(a) {
      const plan = (a.meta || {}).plan || (a.meta || {}).planHash || '';
      const mine = (opts.lastMeta && opts.lastMeta()) || {};
      const same = plan && (mine.plan || mine.planHash) ? plan === (mine.plan || mine.planHash) : null;
      B.clear(out).append(h('span', { 'data-qp-rows': String(a.rowCount != null ? a.rowCount : (a.rows || []).length), text: (a.rowCount != null ? a.rowCount : (a.rows || []).length) + ' rows from the server' + ((a.meta || {}).millis != null ? ' in ' + a.meta.millis + ' ms' : '') + '. ' }),
        plan ? h('span', { 'data-qp-plan': plan, text: 'Plan ' + String(plan).slice(0, 12) + (same === true ? ': the same plan as the visual\'s query.' : same === false ? ': a different plan from the visual\'s query.' : '.') }) : null);
    }
    // a visual's answer (its query) can arrive after it was selected, or after a field was dropped on it: 'query' redraws then
    if (opts.on) { opts.on('select', draw); opts.on('query', draw); opts.on('model', () => window.setTimeout(draw, 600)); }
    draw();
    return { draw, box };
  };
})();
