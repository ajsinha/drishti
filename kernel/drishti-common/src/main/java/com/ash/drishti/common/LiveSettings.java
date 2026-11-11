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
package com.ash.drishti.common;

import org.springframework.core.env.Environment;

/**
 * Something built from settings that packs contribute (roles, link graph, search columns, formats, About pages, kind routes) and
 * that must follow them while the server runs. When the packs change, the pack reloader asks every {@code LiveSettings} bean
 * to {@link #prepare} against a <em>candidate</em> environment (the current one with the new pack settings in it) and only when
 * all of them accepted does it run the returned commits one after another, in a single reload at a time.
 *
 * <p>Rules for an implementation: {@code prepare} reads the candidate environment, validates and builds the new state, and
 * throws if the settings cannot be used (nothing has changed yet, so the reload is refused and the last good packs keep
 * running); the returned commit only swaps a {@code volatile} reference (or a copy-on-write structure) and does not throw,
 * so readers see the old state or the new one, never a mixture. Anything derived (caches, compiled patterns) is rebuilt in
 * {@code prepare} or dropped by the commit.
 */
public interface LiveSettings {

    /**
     * Builds the state for {@code candidate} and returns the step that makes it current.
     *
     * @throws RuntimeException when the settings are not usable; the old state is untouched
     */
    Runnable prepare(Environment candidate);
}
