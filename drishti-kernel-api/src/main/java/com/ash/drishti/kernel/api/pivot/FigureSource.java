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
package com.ash.drishti.kernel.api.pivot;

import java.util.Map;

/**
 * Where Rachana's {@code measure(...)} and {@code cell(...)} find their figures (HOW_IT_FITS.md part 3): implemented by the server, which
 * knows the model measures, the compute engines' pivot providers (VaR, exposure …) and the saved pivot views, and registered once at
 * start ({@code EngineFunctions.figures(...)}). A figure is answered for the person who asks (the server's current request), exactly as
 * its own engine computes it: a computed measure is never summed by anyone, a masked figure stays masked.
 */
public interface FigureSource {

    /**
     * {@code measure(name, [scope], [options])}: a model measure or a compute engine's measure for one scope, exactly from the engine
     * that computes it.
     *
     * @param name the measure's name ({@code VAR}, {@code MTM})
     * @param scope the scope, {@code LEVEL:MEMBER} ({@code DESK:DESK-COMM}); null or blank for the whole
     * @param options further choices by name ({@code date}, {@code confidence} …), as the measure's engine reads them
     * @return the figure (a number); null when the measure has nothing for that scope
     * @throws IllegalArgumentException with the reason, in words, for a measure, a scope or an option that is not known or not
     *     allowed to the person
     */
    Object measure(String name, String scope, Map<String, String> options);

    /**
     * {@code cell(view, coordinates)}: one cell of a saved pivot view, as the view computes it for the person.
     *
     * @param view the saved view's id ({@code desk-pnl})
     * @param coordinates a member for each of the view's row and column fields, by field ({@code DESK: DESK-COMM}); a field left out
     *     reads its total; a view with several values names the one wanted under {@code value}
     * @return the cell's figure; null when the cell is empty
     * @throws IllegalArgumentException with the reason, in words, for a view or a coordinate that is not known or not allowed
     */
    Object cell(String view, Map<String, String> coordinates);
}
