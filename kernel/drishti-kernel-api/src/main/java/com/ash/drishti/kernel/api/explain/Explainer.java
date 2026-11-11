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
package com.ash.drishti.kernel.api.explain;

import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;

/**
 * One kind of number that can be explained (docs/architecture/SHARED_SCREENS.md section 3, "One Explain for every number"): a VaR or ES
 * figure, an exposure, a pivot cell. Each engine registers its own as a bean; the core's {@link NumberExplainService} finds them by
 * {@link #kind()} and knows nothing of what they explain, so a domain module (banking) carries its explainers with it.
 *
 * <p>An explainer computes as the person who asks: the same entitlements, row rules and masks as the screen the number came from. It
 * never names a row or an input the person may not see; at most it counts what is outside their view.
 */
public interface Explainer {

    /** The kind asked for ({@code kind=var}): lower case, unique among the registered explainers. */
    String kind();

    /** What the kind explains, in words (the kinds list and the drawer's crumb). */
    String title();

    /** The measures it explains ({@code measure=}); the first is the default. */
    List<String> measures();

    /** The compute engine whose number this is (Admin → Engines), or null for the core: off, the kind is refused in words. */
    default String engine() {
        return null;
    }

    /**
     * The explanation of one number. {@link ExplainRequest#wants(String)} says which parts are wanted: a hover preview asks only for the
     * preview, so the heavy parts (why it moved reads two dates) are computed only when the drawer or the page opens.
     */
    Explanation explain(Principal p, ExplainRequest r);
}
