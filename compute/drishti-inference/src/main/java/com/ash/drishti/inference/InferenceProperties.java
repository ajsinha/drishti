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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.inference.*}.
 *
 * @param semanticsFile optional site file replacing the bundled {@code inference/semantics.yaml}
 * @param packSemanticsFiles semantic hints contributed by enabled packs, tried before the core roles
 */
@ConfigurationProperties("drishti.inference")
public record InferenceProperties(String semanticsFile, java.util.List<String> packSemanticsFiles) {

    public InferenceProperties {
        packSemanticsFiles = packSemanticsFiles == null ? java.util.List.of() : java.util.List.copyOf(packSemanticsFiles);
    }
}
