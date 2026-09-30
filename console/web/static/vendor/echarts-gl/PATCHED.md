<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. PROPRIETARY AND CONFIDENTIAL.
  See the LICENSE file in the root of this repository for the full terms.
-->
# ECharts GL 2.1.0: one local change

`echarts-gl.min.js` is ECharts GL 2.1.0 (BSD-3-Clause, see LICENSE) with one function replaced. Upstream compiles its
post-processing size expressions (`expr(width * 1.0 / 2)`, `expr([width * 1.0 / 4, height / 4])`) with
`new Function`, which Drishti's content security policy forbids (no `unsafe-eval`). The replacement parses exactly
that grammar (`width`, `height` or `dpr`, then any `* n` or `/ n`, alone or in a two-element array) into the same
functions, without eval. `console/tests/test_assets_policy.py` checks the patch is present.
