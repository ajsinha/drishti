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
/* The web components of drishti-inputs: <drishti-date>, <drishti-date-range>, <drishti-time>, <drishti-datetime>, <drishti-tree-picker>, <drishti-multiselect>,
   <drishti-combobox>, <drishti-select>, <drishti-form>, <drishti-filter-bar>, <drishti-code> and <drishti-code-diff>. Each is its class drawn into the
   element: options as a property (el.options = {...}), or as attributes for the simple ones (label, calendar, mode, value, readonly);
   el.value reads and sets the value (el.disabled turns a select off and on); el.component is the plain API; events come out as drishti:<name> DOM events (drishti:change).
   No shadow root, so the page's tokens and the library's style sheet reach them. */
import { DatePicker } from './drishti_date-picker.js';
import { DateRangePicker } from './drishti_date-range.js';
import { TimePicker, DateTimePicker } from './drishti_time-picker.js';
import { TreePicker } from './drishti_tree-picker.js';
import { MultiSelect } from './drishti_multiselect.js';
import { Combobox } from './drishti_combobox.js';
import { Select } from './drishti_select.js';
import { Form } from './drishti_form.js';
import { FilterBar } from './drishti_filter-bar.js';
import { CodeEditor, CodeDiff } from './drishti_code-editor.js';

/** Every element of drishti-inputs and the class it draws. */
export const INPUT_ELEMENTS = Object.freeze({
  'drishti-date': DatePicker, 'drishti-date-range': DateRangePicker, 'drishti-time': TimePicker, 'drishti-datetime': DateTimePicker, 'drishti-tree-picker': TreePicker, 'drishti-multiselect': MultiSelect,
  'drishti-combobox': Combobox, 'drishti-select': Select, 'drishti-form': Form, 'drishti-filter-bar': FilterBar, 'drishti-code': CodeEditor, 'drishti-code-diff': CodeDiff
});

const ATTRS = ['label', 'calendar', 'mode', 'value', 'readonly', 'as-of', 'placeholder', 'name', 'disabled'];

/** @private options from the element's attributes */
function fromAttributes(el) {
  const o = {};
  ATTRS.forEach((a) => {
    if (!el.hasAttribute(a)) { return; }
    const v = el.getAttribute(a), key = a === 'readonly' ? 'readOnly' : a === 'as-of' ? 'asOf' : a;
    o[key] = a === 'readonly' || a === 'disabled' ? true : v;
  });
  if (el.hasAttribute('options-json')) { Object.assign(o, JSON.parse(el.getAttribute('options-json'))); }
  return o;
}

/**
 * Defines the elements (once each) on a registry.
 * @param {CustomElementRegistry} registry
 * @param {Window} win
 * @returns {void}
 */
export function defineInputElements(registry, win) {
  Object.keys(INPUT_ELEMENTS).forEach((name) => {
    if (registry.get(name)) { return; }
    const Cls = INPUT_ELEMENTS[name];
    registry.define(name, class extends win.HTMLElement {
      connectedCallback() { if (!this.component && (this.attributes.length > 1 || this.hasAttribute('options-json') || Cls === DatePicker || Cls === CodeEditor || Cls === Select)) { this.make(fromAttributes(this)); } }
      make(o) { if (this.component) { this.component.destroy(); } this.component = new Cls(this, Cls === Select && this.hasAttribute('native') ? Object.assign({ native: true }, o) : o); }   // <drishti-select native>: a pick fires change as a native select's did
      set options(o) { this.make(Object.assign(fromAttributes(this), o)); }
      get options() { return this.component ? this.component.o : null; }
      get value() { return this.component && this.component.getValue ? this.component.getValue() : null; }
      set value(v) { if (this.component && this.component.setValue) { this.component.setValue(v); } }
      get disabled() { return !!(this.component && this.component.o && this.component.o.disabled); }
      set disabled(v) { if (this.component && this.component.setDisabled) { this.component.setDisabled(!!v); } }
      focus(o) { if (this.component && this.component.focus) { this.component.focus(); } else { super.focus(o); } }
      disconnectedCallback() { /* kept: an element moved in the page keeps its component */ }
    });
  });
}
