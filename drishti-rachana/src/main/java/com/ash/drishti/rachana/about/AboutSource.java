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
package com.ash.drishti.rachana.about;

import java.util.Optional;

/**
 * Where the About text of a kind comes from: the packs' catalogue ({@link AboutCatalog}), or a catalogue with a Design's own
 * text laid over it ({@link DesignAbout}, the workbench's About tab). The glossary resolver and the lint ask this, so the
 * workbench answers with the same rules as the drawer.
 */
public interface AboutSource {

    /** What is said about {@code kind}, merged through {@code extends}, or empty. */
    Optional<AboutText> forKind(String kind);

    /** The core vocabulary's entry for a field name, or empty. */
    Optional<GlossaryEntry> core(String name);
}
