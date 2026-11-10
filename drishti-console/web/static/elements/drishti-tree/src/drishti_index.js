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
/* drishti-tree: hierarchies in four modes of one component (g6, the product owner's choice: the tree grid, the default, on drishti-grid's
   tree data; the org chart; the sunburst and the icicle), one engine under them (HierarchyModel: open state, search with breadcrumbs,
   measures with non-additive ones (◆) asked of the host and never summed, the edit mode's proposal for four eyes), the layouts and
   partitions as pure functions, export, keys and ARIA. Works without a Drishti server; a server gives the non-additive figures and
   Explain through hooks. In a page the bundle defines <drishti-tree>. */
import { defineTreeElement } from './drishti_tree-element.js';

export { PATH_SEP, HierarchyModel } from './drishti_tree-model.js';
export { orgLayout, partition, arcPath, statusTone } from './drishti_tree-layout.js';
export { svg, nodeColour, drawOrg, drawPartition, partitionTable } from './drishti_tree-draw.js';
export { gridRows, TreeGrid } from './drishti_tree-grid.js';
export { loadOpenChildren } from './drishti_tree-hooks.js';
export { sideContent, statusParts } from './drishti_tree-side.js';
export { TREE_EXPORTS, svgText, pngOf, dataText, keyMove, exportTree } from './drishti_tree-io.js';
export { TREE_MODES, TREE_EVENTS, Tree } from './drishti_tree-view.js';
export { PANEL_MODES, readTableTree, isChain, TreePanel, treeTables } from './drishti_tree-panel.js';
export { flattenTree, findNode, isWithin, moveNode, nodesSource } from './drishti_tree-nodes.js';
export { OUTLINE_TONES, outlineFilter, outlineKey, TreeOutline } from './drishti_tree-outline.js';
export { TREE_ELEMENT, defineTreeElement } from './drishti_tree-element.js';
export { Decomposition } from './drishti_decomposition.js';

/**
 * Starts drishti-tree in a page (the bundle calls it when it loads): defines <drishti-tree>.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root && root.document && root.customElements) { defineTreeElement(root.customElements, root); } }
