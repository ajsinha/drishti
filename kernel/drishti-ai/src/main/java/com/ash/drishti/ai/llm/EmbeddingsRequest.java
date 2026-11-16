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

import com.ash.drishti.ai.llm.Canonical.DrishtiRequest;
import java.util.List;

/** POST /v1/embeddings, typed (the console's {@code EmbeddingsRequest}). {@code input} is a list of texts. */
public record EmbeddingsRequest(String model, List<String> input, String encodingFormat, Integer dimensions, String user, DrishtiRequest drishti) {

    public EmbeddingsRequest {
        model = model == null || model.isBlank() ? "embedding" : model;
        input = input == null ? List.of() : List.copyOf(input);
    }

    public static EmbeddingsRequest of(String model, List<String> input) {
        return new EmbeddingsRequest(model, input, null, null, null, null);
    }

    public List<String> texts() {
        return input;
    }
}
