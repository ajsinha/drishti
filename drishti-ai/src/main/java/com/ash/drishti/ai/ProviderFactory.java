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
package com.ash.drishti.ai;

/**
 * The extension point: one class per provider type. Register it as a Spring bean, or list it in
 * {@code META-INF/services/com.ash.drishti.ai.ProviderFactory} (ServiceLoader). A provider file whose {@code type} equals
 * {@link #type()} is handed to {@link #create}.
 */
public interface ProviderFactory {

    /** The value of {@code type:} in a provider file. */
    String type();

    /** @throws LlmException (kind CONFIG) when the file lacks something this type needs. */
    LlmProvider create(ProviderConfig config) throws LlmException;
}
