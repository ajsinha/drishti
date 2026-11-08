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
/* drishti-core: what every other library and every page builds on. Formats (the server's rules), business dates, theme tokens, the API
   client, the event bus, accessibility helpers, resize handles, keys, dialogs, drawers and toasts, the element maker, Explain as every
   component draws it, and the loader of the other libraries. In a page the bundle (drishti_core.js) registers it as window.Drishti.elements.core and runs install(): the resize
   component starts, and the console's older names (Drishti.resize, Drishti.GridKeys) point here. */
import { installResize } from './drishti_resize.js';
import { GridKeys } from './drishti_keys.js';
import { load } from './drishti_loader.js';

export { MINUS, EMPTY, MASK, COMPACT_UNITS, plainDigits, roundHalfUp, group, formatNumber, formatPercent, formatBasisPoints, formatCurrency,
  formatCompact, defineFormats, hasFormat, namedFormat, formatNames, formatSpec, formatNamed, formatter, formatPythonFloat, BYTE_UNITS,
  formatBytes, formatDuration } from './drishti_format.js';
export { parseDate, daysInMonth, isoDate, today, formatDate, formatDateRange, isBusinessDay, addBusinessDays, previousBusinessDay,
  businessDaysBetween } from './drishti_dates.js';
export { DEFAULT_THEME, THEME_STORAGE_KEY, TEXT_TOKENS, GROUND_TOKENS, themes, scheme, currentTheme, setTheme, token, tokens, parseColor,
  luminance, contrastRatio, readableOn, contrastProblems } from './drishti_theme.js';
export { AS_OF_HEADER, ApiError, errorWords, ApiClient, createClient, api, call } from './drishti_api.js';
export { EventBus, createBus, bus } from './drishti_events.js';
export { FOCUSABLE, focusable, trapFocus, announce, rovingIndex, rovingTabindex } from './drishti_a11y.js';
export { installResize, scanResize, resizeHandle, resetResize } from './drishti_resize.js';
export { trackPointer } from './drishti_pointer.js';
export { WORKBENCH, LAYOUT, interpretPanelKey, GridKeys, keyName, normalizeKey, keymap } from './drishti_keys.js';
export { openDialog, closeDialog, openDrawer, ToastTray, toast } from './drishti_overlay.js';
export { configure, manifest, libraryVersion, requirements, loaded, load, staticUrl, loadScript, loadStyle } from './drishti_loader.js';
export { isBlank, compareValues, compareRows, cycleSort, passes, rowFilter, filterWords, FILTER_CHANNEL, filterRows, textSortKey,
  compareTextKeys } from './drishti_filters.js';
export { h, placeAt, placeUnder, attrAllowed, safeUrl } from './drishti_dom.js';
export { parseNumberText, numberEditText } from './drishti_parse.js';
export { toCsv, download } from './drishti_files.js';
export { sparkline, renderExplain } from './drishti_explain.js';

/**
 * Starts drishti-core in a page (the bundle calls it when it loads; nothing happens without a document): the resize component, and
 * the console's names for what moved here (window.Drishti.resize, window.Drishti.GridKeys) and window.Drishti.elements.load.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) {
  const D = root.Drishti = root.Drishti || {};
  D.GridKeys = GridKeys;
  D.elements = D.elements || {};
  D.elements.load = load;
  if (root.document && root.requestAnimationFrame) { installResize(root); }
}
