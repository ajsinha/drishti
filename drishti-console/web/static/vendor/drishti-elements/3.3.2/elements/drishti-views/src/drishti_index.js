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
/* drishti-views: whole screens from a ViewModel. The open specification (contract 1.x, drishti-rachana's spec/1/viewmodel.schema.json,
   shipped as a pinned copy) and its validator; the painter, which draws a ViewModel into any element (inside a shadow root by default)
   and applies the frames of its live stream; the registry of panel kinds. It draws nothing another library draws, and computes nothing:
   every figure is the producer's text. */
export { CONTRACT, SCHEMA_SHA256 } from './drishti_views-schema.js';
export { LIMITS, FORBIDDEN_KEYS, validate, formatIssue } from './drishti_views-validate.js';
export { MESSAGES, TONES } from './drishti_views-cells.js';
export { PANEL_STATES } from './drishti_views-panels.js';
export { WIDTHS, arrange } from './drishti_views-layout.js';
export { OPS, conflate } from './drishti_views-patch.js';
export { SPECS, ViewHandle, paint } from './drishti_views-paint.js';
export { kinds } from './drishti_views-kinds.js';
export { StateStore, marksOf, effectsOf, hasEffects, triggerFor, requestText, resolveFire, resolveWire, pickEvent, formFields } from './drishti_views-state.js';
import { defineKind } from './drishti_views-kinds.js';
export { formSchema, formValues, problemsByField } from './drishti_views-forms.js';
export { inlineNodes, markupNodes, LINK_STATUS } from './drishti_views-words.js';
export { PAGE_ROWS, numberOfText, parseCondition } from './drishti_views-tables.js';
export { MAX_NESTING } from './drishti_views-tabs.js';
export { CHART_KINDS, chartTable } from './drishti_views-charts.js';
export { AGGS, engineAgg, aggregate, DATASET_FORMS, READ_LIMIT, valueText, rowKey, inferFields, derive, allowedAddress, Dataset, datasetsOf } from './drishti_views-data.js';
export { isMasked } from '../../drishti-core/src/drishti_index.js';
export { DATASET_KINDS, gridColumns, chartSpec, gridChart } from './drishti_views-datakinds.js';
export { LIVE_KINDS, LiveLink } from './drishti_views-live.js';
export { CONTROL_KINDS, TYPING_PAUSE_MS, Controls, controlValues, controlQuery, controlText } from './drishti_views-controls.js';

/**
 * Registers a panel kind's painter: a host's own kind, drawn in the frame, with the states, the layout and the patches of every kind.
 * @param {string} name a lower-case word ("seat-map")
 * @param {import('./drishti_views-kinds.js').KindPainter} def paint(body, panel, ctx) answers {update(panel), dispose()} or nothing
 * @returns {void}
 * @example registerKind('note', { paint(body, panel, ctx) { body.textContent = panel.data.text; } });
 */
export function registerKind(name, def) { defineKind(name, def); }
export { VIEW_ELEMENT, VIEW_ATTRIBUTES, define } from './drishti_views-element.js';
import { define as defineElement } from './drishti_views-element.js';

/**
 * Registers <elements-view> in a page (the bundle calls it when it loads); nothing under Node.
 * @param {Object} root the page's window
 * @returns {void}
 */
export function install(root) { if (root && root.customElements && root.HTMLElement) { defineElement({ window: root }); } }
export { KIND_DATA, stampOf, ViewError, complete, ViewBuilder, view, FrameBuilder, frame, sse } from './drishti_views-build.js';
