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

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One configured provider (an account at a vendor, a self-hosted server): the models it serves and what it can do. Built by a
 * {@link ProviderFactory} from one {@code config/ai/<name>.yaml}. Implementations are thread-safe and hold no per-call state.
 */
public interface LlmProvider extends AutoCloseable {

    /** The provider's name (its file name): the first half of {@code provider/model}. */
    String id();

    /** The provider type (the factory that built it): {@code openai}, {@code anthropic}, ... */
    String type();

    Set<Capability> capabilities();

    /** The models this provider's file names, chat and embedding. */
    List<ModelInfo> models();

    Optional<LlmModel> chatModel(String logicalName);

    default Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return Optional.empty();
    }

    @Override
    default void close() {}
}
