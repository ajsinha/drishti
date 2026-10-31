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
/* Rupaka phase 4 (docs/architecture/BI_DESIGN.md › Rūpaka: the original design and every phase as built, section 20.4, docs/guides/BI_GUIDE.md): on an item's page, the history of saved states with the side-by-side diff and
   restore, the review panel (request, diff, Check, impact, data preview, decisions), lineage, impact ("what breaks if I remove ..."), and the "publish anyway" a
   bi-admin gets after a refusal. bi.js announces the item it has drawn with the event bi:item and a refused action with bi:refused. Text only, never markup. */
(function () {
  'use strict';
  var B = window.drishtiBi, canvas = document.getElementById('biCanvas');
  if (!B || !canvas || canvas.getAttribute('data-bi-page') !== 'item') { return; }
  var h = B.h, q = B.q, clear = B.clear, icon = B.icon;
  var item = null, seq = 0;                    // seq: the item drawn last (bi:item), so an older answer never overwrites a newer one

  function path() { return '/items/' + encodeURIComponent(item.id).replace(/%3A/g, ':'); }
  function say(text, bad) { B.say(q('[data-msg]'), text, bad); }
  function fail(e) { say(e.message, true); }
  function stamp(iso) { return iso ? iso.replace('T', ' ').replace(/\.\d+Z?$|Z$/, '') : ''; }
  function link(it) { return h('a', { href: '/bi/i/' + encodeURIComponent(it.id).replace(/%3A/g, ':'), text: it.title }); }
  function badge(text, cls, ic) { return h('span', { class: 'bi-badge ' + (cls || '') }, ic ? icon(ic) : null, ' ' + text); }
  function endorse(it) {
    if (it.endorsement === 'certified') { return badge('certified', 'bi-certified', 'patch-check'); }
    if (it.endorsement === 'promoted') { return badge('promoted', 'bi-promoted', 'bookmark-star'); }
    return null;
  }
  function button(label, run, cls) { var b = h('button', { type: 'button', class: 'btn-pill ' + (cls || 'btn-ghost') }, label); b.addEventListener('click', function () { run(b); }); return b; }

  // -- the side-by-side diff ------------------------------------------------------------------------------------------------
  var MARK = { same: '', added: '+', removed: '−', changed: '≠' };
  function diffTable(d) {
    var rows = d.rows.map(function (r) {
      return h('tr', { class: 'bi-diff-' + r.op },
        h('td', { class: 'bi-diff-no', text: r.leftNo || '' }), h('td', { class: 'bi-diff-line mono', text: r.left == null ? '' : r.left }),
        h('td', { class: 'bi-diff-mark', 'aria-label': r.op, text: MARK[r.op] }),
        h('td', { class: 'bi-diff-no', text: r.rightNo || '' }), h('td', { class: 'bi-diff-line mono', text: r.right == null ? '' : r.right }));
    });
    return h('div', { class: 'tbl-wrap bi-diff-wrap', tabindex: '0', role: 'region', 'aria-label': 'Differences, ' + d.from.label + ' against ' + d.to.label },
      h('table', { class: 'bi-diff', 'data-plain': true },
        h('caption', { class: 'visually-hidden', text: d.from.label + ' on the left, ' + d.to.label + ' on the right' }),
        h('thead', {}, h('tr', {}, h('th', { scope: 'col', class: 'bi-diff-no', text: '#' }), h('th', { scope: 'col', text: d.from.label }), h('th', { scope: 'col', class: 'bi-diff-mark', text: '' }),
          h('th', { scope: 'col', class: 'bi-diff-no', text: '#' }), h('th', { scope: 'col', text: d.to.label }))),
        h('tbody', {}, rows)));
  }
  function diffSummary(d) {
    return d.identical ? 'No differences.' : [d.changed + ' changed', d.added + ' added', d.removed + ' removed'].join(', ') + ' line' + (d.changed + d.added + d.removed === 1 ? '' : 's') + '.';
  }

  // -- history ------------------------------------------------------------------------------------------------------------
  function history() {
    var sec = q('[data-sec-history]');
    if (item.shipped || !(item.can && item.can.edit) && !(item.status === 'proposed' && item.can && item.can.review)) { sec.hidden = true; return; }
    var mine = seq;
    B.api('GET', path() + '/history').then(function (d) {
      if (mine !== seq) { return; }                                   // the item was drawn again since: a newer answer is on its way
      sec.hidden = false;
      var tb = q('[data-history] tbody'), from = q('[data-diff-from]'), to = q('[data-diff-to]');
      clear(tb); clear(from); clear(to);
      d.revisions.forEach(function (r, k) {
        var ref = 'r' + r.seq, label = 'saved state ' + r.seq + (r.draft ? ' (the draft)' : '');
        from.appendChild(h('option', { value: ref, text: label })); to.appendChild(h('option', { value: ref, text: label }));
        var acts = [button('Compare with draft', function () { show(ref, 'draft'); }, 'btn-ghost')];
        if (d.canRestore && !r.draft) { acts.push(button('Restore as draft', function () { restore(r.seq); }, 'btn-ghost')); }
        tb.appendChild(h('tr', { 'data-seq': r.seq },
          h('th', { scope: 'row', text: '#' + r.seq + (r.draft ? ' (draft)' : '') }), h('td', { text: stamp(r.createdAt) }), h('td', { text: r.author }), h('td', { text: r.message || r.origin }),
          h('td', { text: r.publishedVersion ? 'published v' + r.publishedVersion : '' }), h('td', {}, acts)));
      });
      [['published', 'the published version'], ['draft', 'the draft']].forEach(function (p) { from.appendChild(h('option', { value: p[0], text: p[1] })); to.appendChild(h('option', { value: p[0], text: p[1] })); });
      from.value = d.revisions.length > 1 ? 'r' + d.revisions[1].seq : 'published'; to.value = 'draft';
      var ev = q('[data-events]'); clear(ev);
      d.events.forEach(function (e) { ev.appendChild(h('li', { text: stamp(e.at) + ' · ' + e.actor + ' · ' + e.action + (e.note ? ': ' + e.note : '') })); });
      q('[data-events-wrap]').hidden = !d.events.length;
    }).catch(function () { if (mine === seq) { sec.hidden = true; } });
  }
  function show(a, b) {
    B.api('GET', path() + '/diff?from=' + encodeURIComponent(a) + '&to=' + encodeURIComponent(b)).then(function (d) {
      var out = q('[data-diff-out]'); clear(out);
      out.appendChild(h('p', { class: 'bi-result-meta', role: 'status', text: d.from.label + ' against ' + d.to.label + ': ' + diffSummary(d) }));
      out.appendChild(diffTable(d));
      out.scrollIntoView({ block: 'nearest' });
    }).catch(fail);
  }
  function restore(n) {
    B.api('POST', path() + '/history/' + n + '/restore', {}).then(function (it) { say('Saved state ' + n + ' is the draft again (as a new saved state).'); document.dispatchEvent(new CustomEvent('bi:reload', { detail: it })); }).catch(fail);
  }

  // -- review ---------------------------------------------------------------------------------------------------------------
  function problems(check) {
    if (!check.problems.length) { return h('p', { class: 'bi-ok', text: 'Check passed: nothing to fix.' }); }
    return h('ul', { class: 'bi-problems' }, check.problems.map(function (p) {
      return h('li', { class: p.level === 'warning' ? 'bi-warn-line' : 'bi-bad-line' }, h('b', { text: (p.level || 'error') + ' ' + (p.code || '') + ' ' }), h('span', { class: 'mono', text: p.path || '' }), ' ' + p.message);
    }));
  }
  /** Names that stayed but now mean something else (another type, another path or expression), and the published reports that use them. */
  function alterBlock(box, im) {
    var alters = (im.change && im.change.alters) || [];
    if (!alters.length) { return; }
    box.appendChild(h('p', { class: 'bi-warn-line', 'data-alters': true, text: 'Changed type or meaning: ' + alters.map(function (a) { return a.name + ' (' + a.what + ': ' + a.from + ' → ' + a.to + ')'; }).join('; ') + '.' }));
    if (im.altered && im.altered.length) {
      box.appendChild(h('p', { class: 'text-muted-d', text: im.altered.length + ' published report' + (im.altered.length === 1 ? '' : 's') + ' use' + (im.altered.length === 1 ? 's' : '') + ' them and will show different figures (not refused):' }));
      box.appendChild(h('ul', { 'data-altered': true }, im.altered.map(function (x) {
        return h('li', {}, x.visible ? [h('a', { href: '/bi/i/' + encodeURIComponent(x.id).replace(/%3A/g, ':'), text: x.title }), ' — ', h('span', { class: 'mono', text: x.uses.join('; ') })] : '(a report you cannot see)');
      })));
    }
  }
  function impactBlock(im) {
    if (im.applies === false) { return h('p', { class: 'bi-empty', text: im.note }); }
    var box = h('div', { class: 'bi-impact' });
    var c = im.change || {};
    alterBlock(box, im);
    if (!c.breaking) {
      box.appendChild(h('p', { class: 'bi-ok', text: 'Nothing a published report uses is removed or renamed.' }));
      if (c.addedFields && c.addedFields.length) { box.appendChild(h('p', { class: 'text-muted-d', text: 'New: ' + c.addedFields.concat(c.addedMeasures || []).join(', ') + '.' })); }
      return box;
    }
    box.appendChild(h('p', { class: 'bi-bad-line', text: 'Removed or renamed: ' + (c.removedFields || []).concat(c.removedMeasures || [], c.removedHierarchies || []).join(', ') + '.' }));
    if (c.renames && c.renames.length) {
      box.appendChild(h('p', { 'data-renames': true }, h('b', { text: 'Recognised as renames (same path or expression): ' }),
        c.renames.map(function (r, i) { return h('span', { class: 'mono' }, (i ? '; ' : '') + r.from + ' → ' + r.to); })));
      if (im.renamable) { box.appendChild(h('p', { class: 'bi-ok', 'data-renamable': true, text: 'Every published report that loses a name loses only a renamed one: publishing with “update the reports” rewrites them to the new names in the same step.' })); }
    }
    if (!im.published.length) { box.appendChild(h('p', { class: 'bi-ok', text: 'No published report uses them.' })); }
    else {
      box.appendChild(h('p', { class: 'bi-bad-line', text: im.published.length + ' published report' + (im.published.length === 1 ? '' : 's') + ' would break' + (im.refused ? ': the publish is refused' + (im.canForce ? ' (you may force it)' : '') : '') + '.' }));
      box.appendChild(h('ul', {}, im.published.map(function (x) {
        return h('li', {}, x.visible ? [h('a', { href: '/bi/i/' + encodeURIComponent(x.id).replace(/%3A/g, ':'), text: x.title }), ' uses ', h('span', { class: 'mono', text: x.uses.join('; ') })] : '(a report you cannot see)');
      })));
    }
    if (im.drafts && im.drafts.length) { box.appendChild(h('p', { class: 'text-muted-d', text: im.drafts.length + ' draft report' + (im.drafts.length === 1 ? '' : 's') + ' also use' + (im.drafts.length === 1 ? 's' : '') + ' them.' })); }
    if (im.wouldRevokeCertification && im.wouldRevokeCertification.length) {
      box.appendChild(h('p', { class: 'bi-warn-line', text: 'Certification would be revoked for: ' + im.wouldRevokeCertification.join(', ') + '.' }));
    }
    return box;
  }
  function previewTable(p) {
    if (!p.available) { return h('p', { class: 'bi-empty', text: 'No data preview: ' + p.reason }); }
    var head = h('tr', {}, p.columns.map(function (c) { return h('th', { scope: 'col', text: c.name }); }));
    var body = p.rows.map(function (r) { return h('tr', {}, r.map(function (v, i) { return h('td', { class: p.columns[i].type === 'string' ? '' : 'num', text: v == null ? '–' : (typeof v === 'number' ? v.toLocaleString('en-US', { maximumFractionDigits: 2 }) : String(v)) }); })); });
    return h('div', {}, h('p', { class: 'bi-result-meta', text: p.label + ' · ' + p.rowCount + ' row' + (p.rowCount === 1 ? '' : 's') }),
      h('div', { class: 'tbl-wrap', tabindex: '0', role: 'region', 'aria-label': 'Data preview' }, h('table', { class: 'tbl bi-grid', 'data-plain': true }, h('thead', {}, head), h('tbody', {}, body))));
  }
  function review() {
    var sec = q('[data-sec-review]');
    if (item.status !== 'proposed' || item.shipped) { sec.hidden = true; return; }
    var mine = seq;
    B.api('GET', path() + '/review').then(function (r) {
      if (mine !== seq) { return; }                                   // the item was drawn again since: a newer answer is on its way
      sec.hidden = false;
      var body = q('[data-review-body]'); clear(body);
      var rq = r.request;
      body.appendChild(h('p', { class: 'bi-facts', 'data-review-request': true, text: rq ? 'Review asked for by ' + rq.actor + ' ' + stamp(rq.at) + (rq.note ? ': "' + rq.note + '"' : '') : 'Proposed by ' + r.proposedBy + '.' }));
      body.appendChild(h('h3', { text: 'What changed' }));
      body.appendChild(h('p', { class: 'bi-result-meta', text: r.diff.from.label + ' against ' + r.diff.to.label + ': ' + diffSummary(r.diff) }));
      body.appendChild(diffTable(r.diff));
      body.appendChild(h('h3', { text: 'Check' }));
      body.appendChild(problems(r.check));
      if (r.kind === 'dataset') {
        body.appendChild(h('h3', { text: 'What it does to reports' })); body.appendChild(impactBlock(r.impact));
        if (r.impact.renamable && r.mayDecide && item.can && item.can.approve) {            // every lost name is a recognised rename: approve and change the reports in one step
          body.appendChild(button('Approve and update the reports', function () { publishRenamed('approve'); }, 'btn-accent'));
        }
      }
      body.appendChild(h('h3', { text: 'Data preview' }));
      body.appendChild(previewTable(r.preview));
      body.appendChild(h('p', { class: 'text-muted-d', text: 'Write your comment in the note box above, then Approve and publish or Reject. The author cannot approve their own request.' }));
    }).catch(function (e) { if (mine !== seq) { return; } sec.hidden = true; if (e.status !== 403 && e.status !== 404) { fail(e); } });
  }

  // -- impact ---------------------------------------------------------------------------------------------------------------
  function impact() {
    var sec = q('[data-sec-impact]');
    // a dataset of the model layer is read through models: what a change does to them is the model's Contract (the model designer) and its version rule (DRS-9126)
    if (item.kind !== 'dataset' || item.shipped || !(item.can && item.can.edit) || /^rachana:\s*2/m.test(String(item.body || ''))) { sec.hidden = true; return; }
    sec.hidden = false;
    var out = q('[data-impact-draft]'); clear(out);
    if (!item.isPublished && !item.publishedVersion) { out.appendChild(h('p', { class: 'bi-empty', text: 'Not published yet: no report reads it, so nothing can break.' })); return; }
    var mine = seq;
    B.api('GET', path() + '/impact').then(function (im) {
      if (mine !== seq) { return; }
      out.appendChild(h('h3', { text: 'If you publish the draft' })); out.appendChild(impactBlock(im));
      if (im.renamable && item && item.can && (item.can.publish || item.can.approve)) {
        out.appendChild(button('Publish and update the reports', function () { publishRenamed(item.can.approve && item.status === 'proposed' ? 'approve' : 'publish'); }, 'btn-accent'));
      }
    }).catch(fail);
  }
  function wireImpact() {
    q('[data-impact-form]').addEventListener('submit', function (e) {
      e.preventDefault();
      var names = e.target.elements.remove.value.trim();
      if (!names) { return; }
      B.api('GET', path() + '/impact?remove=' + encodeURIComponent(names)).then(function (im) {
        var out = q('[data-impact-what]'); clear(out);
        out.appendChild(h('h3', { text: 'If ' + names + ' were removed' })); out.appendChild(impactBlock(im));
      }).catch(fail);
    });
  }

  // -- lineage --------------------------------------------------------------------------------------------------------------
  /** Past the connector: what it reads from (the table, topic, path or bucket its file declares). Administrators read the values; others see only that there is a source. */
  function sourceOf(s) {
    if (!s) { return null; }
    var keys = Object.keys(s.declared || {});
    if (!keys.length) { return h('div', { class: 'text-muted-d', 'data-source': true, text: 'reads through the ' + s.plugin + ' plugin' }); }
    return h('div', { class: 'text-muted-d', 'data-source': true, title: s.masked ? 'Masked for you: only an administrator reads where the connector reads from' : '' },
      'reads from (' + s.plugin + '): ', keys.map(function (k, i) { return h('span', {}, (i ? ' · ' : '') + k + ' ', h('span', { class: 'mono', text: s.declared[k] })); }));
  }
  /** The model layer's lineage (a model, a rachana 2 dataset, a report on a model), computed by the server from the items as they are now: each table's dataset at
   *  the version read and its chain to the root, then what reads it (with what each report uses). */
  function layerLineage(l, box) {
    var chainOf = function (chain) {
      return (chain || []).map(function (x) {
        var link = x.kind === 'pack-kind' ? h('span', {}, ' · kind ', h('span', { class: 'mono', text: x.name }), ' of pack ' + x.pack, ' · connector ', h('span', { class: 'mono', text: x.connector || 'whichever serves the kind' }))
          : h('span', {}, ' · ' + x.kind + ' ', h('span', { class: 'mono', text: x.name }));
        return x.source ? h('span', {}, link, sourceOf(x.source)) : link;      // past the connector: what it reads from
      });
    };
    if (l.model) { box.appendChild(h('p', { 'data-model': true }, 'Model ', h('span', { class: 'mono', text: l.model.name + ' ' + l.model.version }), l.model.ref ? ' (the report names ' + l.model.ref + ')' : '')); }
    if (l.dataset) { box.appendChild(h('p', { 'data-dataset': true }, 'Dataset ', h('span', { class: 'mono', text: l.dataset.name + ' ' + l.dataset.version }))); }
    box.appendChild(h('h3', { text: 'Comes from' }));
    box.appendChild(h('ul', { 'data-upstream': true }, (l.upstream || []).map(function (u) {
      return h('li', {}, h('b', { class: 'mono', text: u.table }), ' · dataset ', h('span', { class: 'mono', text: u.dataset + (u.version ? ' ' + u.version : '') }), chainOf(u.chain));
    })));
    (l.problems || []).forEach(function (p) { box.appendChild(h('p', { class: 'bi-bad-line', text: (p.code ? p.code + ' ' : '') + (p.message || '') })); });
    if (l.uses) {
      box.appendChild(h('h3', { text: 'Uses' }));
      box.appendChild(h('p', { 'data-uses': true }, [['Fields', l.uses.fields], ['Measures', l.uses.measures]].filter(function (x) { return x[1] && x[1].length; })
        .map(function (x) { return h('span', {}, h('b', { text: x[0] + ': ' }), h('span', { class: 'mono', text: x[1].join(', ') }), h('br')); })));
    }
    if (l.downstream) {
      box.appendChild(h('h3', { text: 'Read by' }));
      if (!l.downstream.length && !l.hidden) { box.appendChild(h('p', { class: 'bi-empty', text: 'Nothing reads it yet.' })); }
      box.appendChild(h('ul', { 'data-downstream': true }, l.downstream.map(function (r) {
        var u = r.uses ? r.uses.fields.concat(r.uses.measures) : [];
        return h('li', {}, link(r), ' ', h('small', { text: r.kind + ' · ' + (r.status || '') + (r.as ? ' · as ' + r.as + (r.reads ? ', reads ' + r.reads : '') : '') }),
          u.length ? h('span', { class: 'text-muted-d' }, ' uses ', h('span', { class: 'mono', text: u.join(', ') })) : null);
      })));
      if (l.hidden) { box.appendChild(h('p', { class: 'text-muted-d', text: l.hidden + ' more item' + (l.hidden === 1 ? '' : 's') + ' you cannot open.' })); }
    }
  }
  function lineage() {
    var sec = q('[data-sec-lineage]'), box = q('[data-lineage]');
    var mine = seq;
    B.api('GET', path() + '/lineage').then(function (l) {
      if (mine !== seq) { return; }                                   // the item was drawn again since: a newer answer is on its way
      clear(box);
      if (l.layer) { layerLineage(l, box); sec.hidden = false; return; }
      if (l.note) { box.appendChild(h('p', { class: 'bi-empty', text: l.note })); }
      if (l.upstream && l.upstream.length) {
        box.appendChild(h('h3', { text: 'Comes from' }));
        box.appendChild(h('ul', { 'data-upstream': true }, l.upstream.map(function (t) {
          return h('li', {}, h('b', { text: t.table }), ' · kind ', h('span', { class: 'mono', text: t.kind }), t.packs.length ? ' of pack ' + t.packs.join(', ') : ' (no loaded pack has it)',
            ' · connector ', h('span', { class: 'mono', text: t.connector || 'whichever serves the kind' }), ' · ' + t.mode + (t.each ? ' · one row per element of ' + t.each : ''), sourceOf(t.source));
        })));
      }
      if (l.dataset) {
        box.appendChild(h('h3', { text: 'Reads dataset' }));
        box.appendChild(h('ul', { 'data-dataset': true }, h('li', {}, link(l.dataset), ' ', endorse(l.dataset), ' ', h('small', { text: l.dataset.status }))));
      }
      if (l.uses) {
        box.appendChild(h('h3', { text: 'Uses' }));
        box.appendChild(h('p', { 'data-uses': true }, [['Fields', l.uses.fields], ['Measures', l.uses.measures], ['Hierarchies', l.uses.hierarchies]].filter(function (x) { return x[1].length; })
          .map(function (x) { return h('span', {}, h('b', { text: x[0] + ': ' }), h('span', { class: 'mono', text: x[1].join(', ') }), h('br')); })));
      }
      if (l.downstream) {
        box.appendChild(h('h3', { text: 'Read by' }));
        if (!l.downstream.length && !l.hidden) { box.appendChild(h('p', { class: 'bi-empty', text: 'No report reads it yet.' })); }
        box.appendChild(h('ul', { 'data-downstream': true }, l.downstream.map(function (r) {
          return h('li', {}, link(r), ' ', endorse(r), ' ', h('small', { text: r.status }), r.uses && r.uses.fields ? h('span', { class: 'text-muted-d' }, ' uses ', h('span', { class: 'mono', text: r.uses.fields.concat(r.uses.measures).join(', ') })) : null);
        })));
        if (l.hidden) { box.appendChild(h('p', { class: 'text-muted-d', text: l.hidden + ' more report' + (l.hidden === 1 ? '' : 's') + ' you cannot open.' })); }
      }
      sec.hidden = false;
    }).catch(function () { if (mine === seq) { sec.hidden = true; } });
  }

  // -- a refusal over a dataset change, and the warnings of a publish ---------------------------------------------------------
  function warnings(it) {
    var box = q('[data-warnings]'); clear(box);
    var w = (it && it.warnings || []).slice();
    // what a migration (2.2: rachana 1 to the model layer, the old expression syntax) could not convert: the item kept its text, and says why here
    (it && it.migrationProblems || []).forEach(function (p) { w.push('Not migrated (' + p.step + (p.where ? ', ' + p.where : '') + '): ' + p.problem); });
    box.hidden = !w.length;
    if (w.length) { box.appendChild(h('h2', { text: 'Heads up' })); box.appendChild(h('ul', {}, w.map(function (x) { return h('li', { 'data-warning': /^Not migrated/.test(x) ? 'migration' : '', text: x }); }))); }
  }
  /** Publishes the dataset and rewrites the reports that use its renamed fields and measures to the new names, in one step. */
  function publishRenamed(sub) {
    var note = q('.bi-note');
    B.api('POST', path() + '/' + sub, { note: note ? note.value : '', applyRenames: true }).then(function (n) {
      say('Published, and the reports that used the old names now use the new ones.'); document.dispatchEvent(new CustomEvent('bi:reload', { detail: n }));
    }).catch(fail);
  }
  function force(sub) {
    var note = q('.bi-note');
    B.api('POST', path() + '/' + sub, { note: note ? note.value : '', force: true }).then(function (n) {
      say('Published over the warning (forced).'); document.dispatchEvent(new CustomEvent('bi:reload', { detail: n }));
    }).catch(fail);
  }
  document.addEventListener('bi:refused', function (ev) {
    var d = ev.detail, box = q('[data-force]'); clear(box);
    if (d.code !== 'DRS-9040') { box.hidden = true; return; }
    say('');                                                  // the callout below says it once
    box.hidden = false;
    box.appendChild(h('p', { class: 'bi-bad-line', role: 'alert', text: d.message }));
    if (item && item.can && item.can.force) { box.appendChild(button('Publish anyway (bi-admin)', function () { force(d.sub); }, 'btn-ghost bi-danger')); }
    impact();
  });

  document.addEventListener('bi:item', function (ev) {
    item = ev.detail;
    seq++;                                       // each loader draws only the answer to the newest item: a slow older one is dropped
    item.isPublished = item.publishedVersion > 0;
    var f = q('[data-force]'); if (f) { f.hidden = true; clear(f); }
    warnings(ev.detail);
    history(); review(); impact(); lineage();
  });
  wireImpact();
  q('[data-diff-form]').addEventListener('submit', function (e) { e.preventDefault(); show(q('[data-diff-from]').value, q('[data-diff-to]').value); });
})();
