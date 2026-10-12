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
package com.ash.drishti.server.bi.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.bi.ai.*} (docs/guides/BI_AI.md): the three BI hooks on the AI layer. They also need {@code drishti.ai.enabled}; while that is off every hook
 * answers DRS-4015.
 *
 * @param model the model the hooks use ({@code provider/model}); blank: the AI layer's default
 * @param values {@code labels-only} (default: the model sees names, types and descriptions, never a value) or {@code aggregates} (it may also see the reader's
 *     masked aggregate results, never rows of entities)
 * @param maxQuestionChars longest question or description accepted
 * @param maxTokens longest answer asked of the model
 * @param timeoutSeconds how long one model call may take
 * @param askRows most rows an "ask the data" answer returns, and the most the model sees of them in aggregates mode
 * @param explainDimensions most dimensions "explain this number" breaks a value down by
 * @param explainMembers most members listed per dimension
 * @param suggestions most visuals the designer copilot proposes at once
 * @param helperItems most items the dataset helper proposes per kind (relationships, measures, column descriptions)
 */
@ConfigurationProperties("drishti.bi.ai")
public record BiAiProperties(String model, String values, Integer maxQuestionChars, Integer maxTokens, Integer timeoutSeconds, Integer askRows, Integer explainDimensions,
        Integer explainMembers, Integer suggestions, Integer helperItems) {

    public BiAiProperties {
        model = model == null ? "" : model.strip();
        values = "aggregates".equalsIgnoreCase(values == null ? "" : values.strip()) ? "aggregates" : "labels-only";
        maxQuestionChars = maxQuestionChars == null || maxQuestionChars <= 0 ? 600 : maxQuestionChars;
        maxTokens = maxTokens == null || maxTokens <= 0 ? 2000 : maxTokens;
        timeoutSeconds = timeoutSeconds == null || timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        askRows = askRows == null || askRows <= 0 ? 200 : askRows;
        explainDimensions = explainDimensions == null || explainDimensions <= 0 ? 3 : explainDimensions;
        explainMembers = explainMembers == null || explainMembers <= 0 ? 5 : explainMembers;
        suggestions = suggestions == null || suggestions <= 0 ? 6 : suggestions;
        helperItems = helperItems == null || helperItems <= 0 ? 8 : helperItems;
    }

    public boolean aggregates() {
        return "aggregates".equals(values);
    }

    public static BiAiProperties defaults() {
        return new BiAiProperties(null, null, null, null, null, null, null, null, null, null);
    }
}
