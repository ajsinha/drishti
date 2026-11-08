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
/* drishti-inputs: the inputs every screen asks for, with no server needed. Business-date pickers keyed by holiday calendars (T-1,
   month-end, typed dates), a date-range picker with presets, a tree picker for hierarchies (search, partial selection, children from a
   server), a multi-select with search, chips and select-all, a combobox with server type-ahead, a single-choice select (keyboard, type-ahead, search, ARIA), forms from a schema (validation in
   words, conditional fields, arrays, a four-eyes submit), a filter bar whose filter model drishti-grid and charts follow on a bus, and
   the code editor over the vendored CodeMirror (Rachana, YAML, Python; completion from the function catalogue; lint; read-only; diff). */
export { CALENDAR_RULES, easterSunday, ruleDate, HolidayCalendar, defineCalendar, calendar, DATE_PRESETS, resolvePreset, parseDateInput, monthGrid,
  shiftDate } from './drishti_calendar.js';
export { uid, debounce, InputBase, Popover, Listbox, normalizeOptions, searchOptions } from './drishti_inputs-dom.js';
export { CalendarView, DatePicker } from './drishti_date-picker.js';
export { DateRangePicker } from './drishti_date-range.js';
export { parseTime, parseDateTime, timeSlots, TimePicker, DateTimePicker } from './drishti_time-picker.js';
export { TreeModel } from './drishti_tree.js';
export { TreePicker } from './drishti_tree-picker.js';
export { MultiSelect } from './drishti_multiselect.js';
export { TypeAhead, Combobox } from './drishti_combobox.js';
export { Select, selectElement, selectProperties } from './drishti_select.js';
export { words, pathParts, getPath, setPath, conditionHolds, fieldsOf, defaults, fieldProblem, validate, visibleValue, changes,
  parseField, currencyOf, offeredValues } from './drishti_schema.js';
export { Form } from './drishti_form.js';
export { FORM_HOST_SELECTOR, formOf, upgradeForms } from './drishti_form-host.js';
export { defineWidget, defaultWidget, applyHook } from './drishti_form-fields.js';
export { FilterModelBar, FilterBar } from './drishti_filter-bar.js';
export { upgradeDateInput, upgradeTimeInput, upgradeDateTimeInput, upgradeCheckboxes, upgradeFields } from './drishti_native.js';
export { rachanaMode, yamlMode, MODES, defineModes, MiniStream, tokens } from './drishti_code-modes.js';
export { wordAt, completions, insertText, lineDiff, sideBySide, diffStats } from './drishti_code-text.js';
export { ensureCodeMirror, yamlIndentAfter, CodeEditor, CodeDiff } from './drishti_code-editor.js';
export { upgradeCodeArea, upgradeCodeAreas, CODE_AREA_SELECTOR } from './drishti_code-upgrade.js';
import { upgradeCodeAreas, CODE_AREA_SELECTOR } from './drishti_code-upgrade.js';
export { INPUT_ELEMENTS, defineInputElements } from './drishti_inputs-element.js';
import { defineInputElements } from './drishti_inputs-element.js';

/**
 * Starts drishti-inputs in a page (the bundle calls it when it loads; nothing happens without a document): defines its elements and
 * makes the page's code areas (<textarea data-di-code>) code editors.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) {
  if (!root || !root.document) { return; }
  if (root.customElements) { defineInputElements(root.customElements, root); }
  const doc = root.document, go = () => upgradeCodeAreas(doc);          // the page's code areas (<textarea data-di-code>, <input data-di-code>), now and as they appear
  if (doc.readyState === 'loading') { doc.addEventListener('DOMContentLoaded', go); } else { go(); }
  if (root.MutationObserver) { new root.MutationObserver(() => { if (doc.querySelector(CODE_AREA_SELECTOR)) { go(); } }).observe(doc.documentElement, { childList: true, subtree: true }); }
}
