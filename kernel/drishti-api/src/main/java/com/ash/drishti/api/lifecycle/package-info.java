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
/**
 * The one lifecycle every runtime part of Drishti follows (docs/architecture/LIFECYCLE.md): modules, packs and connectors here, engines in
 * the engine specification (drishti-engine spec/1/SPEC.md section 7.1), with the same states and the same five transitions:
 * {@link com.ash.drishti.api.lifecycle.Lifecycle} (the contract), {@link com.ash.drishti.api.lifecycle.LifecycleState} and
 * {@link com.ash.drishti.api.lifecycle.Transition} (the table), {@link com.ash.drishti.api.lifecycle.LifecycleMachine} (the one
 * implementation of the table, with the request gate and the drain), {@link com.ash.drishti.api.lifecycle.ComponentHealth} and
 * {@link com.ash.drishti.api.lifecycle.ComponentStats} (what every part reports, cheaply), {@link com.ash.drishti.api.lifecycle.StatsRecorder}
 * (how it counts).
 */
package com.ash.drishti.api.lifecycle;
