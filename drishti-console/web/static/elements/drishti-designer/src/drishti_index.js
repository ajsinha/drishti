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
/* drishti-designer: the designer kit every Drishti designer and editor is built from (the product owner, 2026-11-07: "duplicated code
   will make the system unmaintainable"). The layout canvas over a layout model (grid or flow; place, move, resize with snap,
   multi-select, align, distribute, z-order, groups, copy and paste, a phone's order, the keys, undo and redo on one command stack),
   the palette, the inspector (a drishti-inputs Form from a schema), the outline (a tree, drag to re-order or re-parent), the YAML pane
   (drishti-inputs' CodeEditor kept in step with the model both ways, the selected item highlighted, completion and lint from a JSON
   Schema) and the designer shell of EDITORS.md (three panes, Visual / YAML / Both, the header with versions, Validate, Preview,
   History, Impact, Propose for review). Depends on drishti-core and drishti-inputs only. */
export { CommandStack, SnapshotHistory } from './drishti_commands.js';
export { Selection } from './drishti_selection.js';
export { GRID, MAX_H, DEFAULT_MIN_SPAN, clamp, clampSpan, clampHeight, hideRefused, sizeText, rowsFromPixels, snap, clampRect, overlaps, settle, bounds,
  ALIGN_EDGES, alignRects, distributeRects } from './drishti_layout-rules.js';
export { LayoutModel } from './drishti_layout-model.js';
export { KEY_SCHEMES, designerBindings, designerKeys, keyHelp } from './drishti_keymap.js';
export { trackPointer, DragHub } from './drishti_pointer.js';
export { flowHit, showFlowDrop, clearFlowDrop } from './drishti_layout-flow.js';
export { LayoutCanvas } from './drishti_layout-canvas.js';
export { searchPalette, paletteGroups, Palette } from './drishti_palette.js';
export { commonValues, sharedSchema, Inspector } from './drishti_inspector.js';
export { flattenTree, findNode, isWithin, moveNode, Outline } from './drishti_outline.js';
export { unquote, parse as parseYamlTree, get as yamlGet, plain as yamlPlain, chain as yamlChain, context as yamlContext } from './drishti_yaml-read.js';
export { yamlScalar, dumpYaml, yamlRanges, pathAtLine } from './drishti_yaml-write.js';
export { RACHANA_FUNCTION_ARGS, RACHANA_FUNCTION_HELP, YamlSchema, yamlPlace, yamlCompletions, yamlCheck, yamlHelp } from './drishti_yaml-assist.js';
export { readYaml, YamlPane } from './drishti_yaml-pane.js';
export { SHELL_ACTIONS, SHELL_MODES, SHELL_LAYOUTS, SHELL_FKEYS, statusWords, DesignerShell } from './drishti_shell.js';
export { DESIGNER_ELEMENTS, defineDesignerElements } from './drishti_designer-element.js';
import { defineDesignerElements } from './drishti_designer-element.js';
import { parse, get, plain, chain, context, unquote } from './drishti_yaml-read.js';

/**
 * Starts drishti-designer in a page (the bundle calls it when it loads): defines its elements, and keeps the console's name of the YAML
 * reader (window.Drishti.yaml) pointing here.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) {
  if (!root) { return; }
  const D = root.Drishti = root.Drishti || {};
  D.yaml = { parse, context, chain, get, plain, unquote };
  if (root.document && root.customElements) { defineDesignerElements(root.customElements, root); }
}
