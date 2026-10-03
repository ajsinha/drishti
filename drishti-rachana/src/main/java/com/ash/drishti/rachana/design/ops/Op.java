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
package com.ash.drishti.rachana.design.ops;

/**
 * One edit of a Sutra, as the Build workbench makes them: a panel added, moved, changed, bound to data or removed, or the
 * Sutra's title, strip, keys, match or whole text replaced. An operation is plain data (see {@link Ops} for its JSON) and
 * is applied by {@link OpApplier}, which keeps the text's comments and key order and refuses an operation whose result
 * would not be a valid Sutra.
 */
public sealed interface Op permits AddPanel, Move, SetOption, Bind, Remove, SetTitle, SetStrip, SetKeys, SetMatch, Text {

    /** The operation's name in JSON ({@code addPanel}, {@code move}, {@code setOption}, ...). */
    String name();

    /**
     * Edits {@code doc} in place.
     *
     * @throws OpException when the operation cannot be applied; {@code doc} may then be half-edited and is discarded
     */
    void applyTo(SutraDoc doc);
}
