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
package com.ash.drishti.server.explain;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.explain.ask.*}: Ask about this page (docs/architecture/CONTEXT_HELP.md, Optional: Ask). Off by default; when
 * off no network call is ever attempted. The key comes from the environment only ({@code ${DRISHTI_ASK_KEY:}} in
 * {@code application.yaml}); it is never logged or echoed.
 *
 * @param enabled the global switch
 * @param endpoint the model's HTTP endpoint (an OpenAI-compatible chat completions URL, or an Anthropic Messages URL)
 * @param api {@code openai} or {@code anthropic}: the request and response shape
 * @param model the model name sent to the endpoint
 * @param apiKey the credential, sent as a bearer token ({@code openai}) or {@code x-api-key} ({@code anthropic}); blank sends none
 * @param packs the packs where Ask is on; empty means none
 * @param values {@code labels-only} (default) or {@code shown}: whether rendered values go into the prompt
 * @param maxQuestionChars longest question accepted
 * @param maxPromptKb the whole prompt's cap in KiB; the pack guide is cut first
 * @param maxGuideChars the longest pack guide section placed in the prompt
 * @param maxAnswerTokens the model's output cap
 * @param maxAnswerChars the answer returned is cut to this many characters
 * @param timeout how long the endpoint may take
 * @param perUserPerMinute questions one user may ask in a minute
 * @param perUserPerDay questions one user may ask in a day
 * @param logQuestions whether the question text goes to the access log (the audit row is always written; answers are not stored)
 * @param logAnswers whether the answer text is also written to the server log (off by default)
 * @param anthropicVersion the {@code anthropic-version} header for {@code api: anthropic}
 */
@ConfigurationProperties("drishti.explain.ask")
public record AskProperties(Boolean enabled, String endpoint, String api, String model, String apiKey, List<String> packs, String values,
        Integer maxQuestionChars, Integer maxPromptKb, Integer maxGuideChars, Integer maxAnswerTokens, Integer maxAnswerChars, Duration timeout,
        Integer perUserPerMinute, Integer perUserPerDay, Boolean logQuestions, Boolean logAnswers, String anthropicVersion) {

    public AskProperties {
        enabled = enabled != null && enabled;
        endpoint = endpoint == null ? "" : endpoint.strip();
        api = api == null || api.isBlank() ? "openai" : api.strip().toLowerCase();
        model = model == null ? "" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        packs = packs == null ? List.of() : packs.stream().filter(p -> p != null && !p.isBlank()).map(String::strip).toList();
        values = "shown".equalsIgnoreCase(values == null ? "" : values.strip()) ? "shown" : "labels-only";
        maxQuestionChars = pos(maxQuestionChars, 500);
        maxPromptKb = pos(maxPromptKb, 24);
        maxGuideChars = pos(maxGuideChars, 6_000);
        maxAnswerTokens = pos(maxAnswerTokens, 400);
        maxAnswerChars = pos(maxAnswerChars, 2_000);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(15) : timeout;
        perUserPerMinute = pos(perUserPerMinute, 6);
        perUserPerDay = pos(perUserPerDay, 100);
        logQuestions = logQuestions == null || logQuestions;
        logAnswers = logAnswers != null && logAnswers;
        anthropicVersion = anthropicVersion == null || anthropicVersion.isBlank() ? "2023-06-01" : anthropicVersion.strip();
    }

    private static int pos(Integer v, int dflt) {
        return v == null || v <= 0 ? dflt : v;
    }

    /** Whether Ask is on for a pack: the global switch and the pack's own opt-in. */
    public boolean on(String pack) {
        return enabled && pack != null && packs.contains(pack);
    }

    /** Whether the prompt may carry rendered values. */
    public boolean valuesShown() {
        return "shown".equals(values);
    }
}
