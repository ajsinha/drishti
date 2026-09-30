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
package com.ash.drishti.inference;

import com.ash.drishti.sutra.model.Sutra;
import java.util.Map;

/**
 * The layout a view is actually built from: the Sutra with its inference gaps filled, or a wholly
 * inferred one. Data-free, so it is cached per (Sutra version, document shape).
 *
 * @param sutra the effective layout, in Sutra form
 * @param label how it was built, as shown to users: {@code Sutra irs-vanilla v3 + inference}
 * @param inferred whether inference contributed anything
 * @param explanations panel id to rule, score and reason, for inferred panels
 */
public record EffectiveLayout(Sutra sutra, String label, boolean inferred, Map<String, String> explanations) {}
