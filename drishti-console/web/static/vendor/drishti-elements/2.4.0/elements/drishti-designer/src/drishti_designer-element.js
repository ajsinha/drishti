/*
 * Drishti Elements
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY.
 *
 * This file is the proprietary property of Ashutosh Sinha.
 * Copying, use, modification or distribution of this file, via any medium,
 * is not permitted except with the express prior written permission of the
 * copyright holder.
 *
 * See the LICENSE file in the root of this repository.
 */
/* The web components of drishti-designer: <drishti-layout-canvas>, <drishti-palette>, <drishti-inspector>, <drishti-outline>,
   <drishti-yaml-pane> and <drishti-designer-shell>. Each is its class drawn into the element once options are given (el.options =
   {...}: a canvas needs its model and selection, so the components start from a property, never from attributes); el.component is
   the plain API. No shadow root: the page's tokens and the library's style sheet reach them. */
import { LayoutCanvas } from './drishti_layout-canvas.js';
import { Palette } from './drishti_palette.js';
import { Inspector } from './drishti_inspector.js';
import { TreeOutline as Outline } from '../../drishti-tree/src/drishti_index.js';
import { YamlPane } from './drishti_yaml-pane.js';
import { DesignerShell } from './drishti_shell.js';

/** Every element of drishti-designer and the class it draws. */
export const DESIGNER_ELEMENTS = Object.freeze({
  'drishti-layout-canvas': LayoutCanvas, 'drishti-palette': Palette, 'drishti-inspector': Inspector, 'drishti-outline': Outline,
  'drishti-yaml-pane': YamlPane, 'drishti-designer-shell': DesignerShell
});

/**
 * Defines the elements (once each) on a registry.
 * @param {CustomElementRegistry} registry
 * @param {Window} win
 * @returns {void}
 */
export function defineDesignerElements(registry, win) {
  Object.keys(DESIGNER_ELEMENTS).forEach((name) => {
    if (registry.get(name)) { return; }
    const Cls = DESIGNER_ELEMENTS[name];
    registry.define(name, class extends win.HTMLElement {
      set options(o) { if (this.component && this.component.destroy) { this.component.destroy(); } this.textContent = ''; this.component = new Cls(this, o); }
      get options() { return this.component ? this.component.o : null; }
    });
  });
}
