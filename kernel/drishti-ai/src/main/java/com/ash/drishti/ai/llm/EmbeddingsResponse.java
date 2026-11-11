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
package com.ash.drishti.ai.llm;

import com.ash.drishti.ai.llm.Canonical.EmbeddingData;
import com.ash.drishti.ai.llm.Canonical.EmbeddingsUsage;
import com.ash.drishti.ai.llm.Canonical.ResponseDrishti;
import java.util.List;

/** The answer to an embeddings request (the console's {@code EmbeddingsResponse}). */
public record EmbeddingsResponse(String object, List<EmbeddingData> data, String model, EmbeddingsUsage usage, ResponseDrishti drishti) {

    public EmbeddingsResponse {
        object = object == null ? "list" : object;
        data = data == null ? List.of() : List.copyOf(data);
        usage = usage == null ? new EmbeddingsUsage(0, 0) : usage;
    }

    /** One vector per input, in order. */
    public List<float[]> vectors() {
        return data.stream().map(EmbeddingData::vector).toList();
    }
}
