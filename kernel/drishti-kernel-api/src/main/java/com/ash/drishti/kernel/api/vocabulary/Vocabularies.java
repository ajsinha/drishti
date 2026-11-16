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
package com.ash.drishti.kernel.api.vocabulary;

import java.util.List;
import java.util.ServiceLoader;

/** The vocabularies on the class path, found once. */
final class Vocabularies {

    static final List<DomainVocabulary> ALL = ServiceLoader.load(DomainVocabulary.class, DomainVocabulary.class.getClassLoader()).stream()
            .map(ServiceLoader.Provider::get).toList();

    /** The class path's vocabularies, then a loaded module's. */
    static List<DomainVocabulary> all() {
        return com.ash.drishti.common.Extensions.with(ALL, DomainVocabulary.class);
    }

    private Vocabularies() {
    }
}
