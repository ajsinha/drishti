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
/* Small helpers the workspace and item pages share, loaded after bi.js and bi-lib.js: the starter body of a new Python item (measure, visual or transform), and the
   known-people search on the workspace's "add a member" box (the directory the share dialog uses). No data passes through this file. */
(function () {
  'use strict';
  const B = window.drishtiBi;
  if (!B) { return; }

  const list = (text) => String(text || '').split(',').map((x) => x.trim()).filter(Boolean);
  const flow = (items) => '[' + items.join(', ') + ']';

  /** A valid, commented body for a new Python item: the manifest the server checks, a function of the interface's shape, and (for a measure) a test the Check runs. */
  B.pythonStarter = function (interfaceName, name, title, inputs) {
    const cols = list(inputs);
    const head = 'rachana: 1\npython: ' + name + '\ntitle: ' + JSON.stringify(title) + '\ndescription: Started from the New item form; change the source and press Check on the item page.\nversion: 1\n';
    if (interfaceName === 'transform') {
      const ds = cols.length ? cols : ['trades-basic'];
      const args = ds.map((d, i) => 'frame' + (i + 1)).join(', ');
      return head + 'interface: transform            # a table in, a table out; the other interfaces are measure and visual\n' +
        'inputs: ' + flow(ds) + '     # the datasets it reads, in the order its function receives their frames\npackages: [pandas]\ntarget: server                  # a Transform runs on the server and its result is kept for the reader\n' +
        'timeout: 20                     # seconds\nmemory: 512                     # megabytes\nparams: {}                      # the dataset that uses it may override these\n' +
        'source: |\n  def run(' + args + ', params):\n      """Each frame holds the rows its dataset answered for the person running this (their row rules and masks apply). Return one table."""\n      return frame1\n';
    }
    const use = cols.length ? cols : ['value'];
    if (interfaceName === 'visual') {
      return head + 'interface: visual\noutput: echarts                # the function returns an ECharts option (a dict), never HTML or script\ninputs: ' + flow(use) + '           # the columns the visual\'s well names\npackages: [numpy]\ntarget: both                    # server, browser or both\n' +
        'timeout: 10\nmemory: 256\nparams: { bins: 10 }\nsource: |\n  import numpy as np\n\n  def render(table, params):\n      """table: a pandas table of the rows the person may see, with the columns above. Return an ECharts option."""\n' +
        '      values = table["' + use[0] + '"].dropna().to_numpy(dtype=float)\n      counts, edges = np.histogram(values, bins=int(params.get("bins", 10))) if len(values) else (np.zeros(1), np.zeros(2))\n' +
        '      return {"xAxis": {"type": "category", "data": [f"{e:,.0f}" for e in edges[:-1]]}, "yAxis": {"type": "value"}, "series": [{"type": "bar", "data": [int(c) for c in counts]}]}\n';
    }
    return head + 'interface: measure\ninputs: ' + flow(use) + '           # the columns the function reads, as the table names them\npackages: [pandas]              # what the source imports (must be on the allow-list)\ntarget: both                    # server, browser or both\n' +
      'timeout: 10                     # seconds\nmemory: 256                     # megabytes\nparams: {}                      # defaults the function reads\n' +
      'tests:                          # Check runs these in the sandbox; a publish is refused while one fails\n  - name: the mean of three values\n    input: { columns: ' + flow([use[0]]) + ', rows: [[1.0], [2.0], [3.0]] }\n    expect: 2.0\n' +
      'source: |\n  def compute(table, params):\n      """One number for the rows of one group."""\n      values = table["' + use[0] + '"].dropna()\n      return float(values.mean()) if len(values) else 0.0\n';
  };

  // ---- known people on the workspace's "add a member" box -----------------------------------------------------------------------------------
  const user = document.querySelector('[data-member-user]'), box = document.getElementById('wMemberList');
  if (user && box && window.DrishtiPeople) {
    window.DrishtiPeople.attach(user, box, {
      kind: '', minQuery: 2,
      onPick: (e) => { if (e.type === 'role') { return; } user.value = e.name; user.setAttribute('data-picked', e.name); user.focus(); }
    });
    user.closest('label').style.position = 'relative';
  }
})();
