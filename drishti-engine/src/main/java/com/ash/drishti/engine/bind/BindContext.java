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
package com.ash.drishti.engine.bind;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.Fingerprint;
import com.ash.drishti.graph.LinkRef;
import com.ash.drishti.inference.EffectiveLayout;
import com.ash.drishti.sutra.el.EvalContext;
import java.util.List;
import java.util.Map;

/**
 * Everything one view binding needs. Immutable; shared by the panel binders running in parallel.
 *
 * @param doc the entity document
 * @param layout the effective layout
 * @param fingerprint the document's shape fingerprint
 * @param eval evaluation context over the document
 * @param links references discovered in the document
 * @param linked linked documents fetched within the budget (absent ones are pending or missing)
 * @param pending linked entities not fetched in time
 */
public record BindContext(
        EntityDocument doc,
        EffectiveLayout layout,
        Fingerprint fingerprint,
        EvalContext eval,
        List<LinkRef> links,
        Map<EntityRef, EntityDocument> linked,
        java.util.Set<EntityRef> pending) {}
