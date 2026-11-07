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
/* drishti-risk: the banking module's chart kinds on drishti-chart (licensed with the module; the console serves this library only where
   the banking module is licensed and to the people whose role or lens uses it). Loading it registers the kinds; a chart then names them
   like any other: new Chart(el, {kind: 'risk.backtest', ...}). */
import { registerRiskKinds } from './drishti_risk-kinds.js';

export { TRAFFIC_LIGHT, trafficLight, tailFigures, tenorText, eepeOf, buildPnlDistribution, buildBacktest, buildExposureProfile, buildVarChange,
  registerRiskKinds } from './drishti_risk-kinds.js';
export { RISK_GRID_DEFAULTS, TONES, figureColumn, riskGrid } from './drishti_risk-grid.js';

/** The kinds this library adds to drishti-chart. */
export const RISK_KINDS = Object.freeze(['risk.pnl-distribution', 'risk.backtest', 'risk.exposure-profile', 'risk.var-change']);

registerRiskKinds();

/**
 * Starts drishti-risk in a page (the bundle calls it): the kinds are registered already, when the module is read.
 * @param {Window} root
 * @returns {void}
 */
export function install(root) { if (root) { registerRiskKinds(); } }
