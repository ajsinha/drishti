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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.explain.ExplainService;
import com.ash.drishti.engine.explain.PageContext;
import com.ash.drishti.identity.AccessLog;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Ask about this page (docs/architecture/CONTEXT_HELP.md, Optional: Ask). Server side only. The prompt is assembled from the
 * caller's own explain answer (so their field masks were applied before anything was read), the glossary inside it and the
 * pack's guide text, and nothing else: no entity document, no history, no other user's data. Off by default; while off, or
 * for a pack that has not opted in, no network call is made and the answer is {@code DRS-4007}.
 */
public final class AskService {

    private static final Logger LOG = LoggerFactory.getLogger("drishti.ask");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The answer, and what it was drawn from. */
    public record Answer(String answer, List<String> sources) {}

    private final ExplainService explain;
    private final AskProperties cfg;
    private final AskProvider provider;
    private final AskRateLimiter limiter;
    private final PackGuides guides;
    private final ObjectProvider<AccessLog> accessLog;

    public AskService(ExplainService explain, AskProperties cfg, AskProvider provider, AskRateLimiter limiter, PackGuides guides,
            ObjectProvider<AccessLog> accessLog) {
        this.explain = explain;
        this.cfg = cfg;
        this.provider = provider;
        this.limiter = limiter;
        this.guides = guides;
        this.accessLog = accessLog;
    }

    /** Whether the Ask box may be drawn for a page of this pack. */
    public boolean enabledFor(String pack) {
        return cfg.on(pack);
    }

    public Answer ask(EntityRef ref, AsOf asOf, ExplainService.Caller caller, String question, List<String> locales) {
        if (!cfg.enabled()) {
            throw new DrishtiException(ErrorCode.ASK_OFF, "Ask about this page is switched off");
        }
        String q = question == null ? "" : question.strip();
        if (q.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the question is empty");
        }
        if (q.length() > cfg.maxQuestionChars()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the question is longer than " + cfg.maxQuestionChars() + " characters");
        }
        PageContext ctx = explain.explain(ref, asOf, caller, null, null, locales);   // the caller's own explanation: masks and rights applied
        String pack = ctx.about() == null || ctx.about().pack() == null ? null : ctx.about().pack().name();
        if (!cfg.on(pack)) {
            throw new DrishtiException(ErrorCode.ASK_OFF, "Ask about this page is switched off for this page's pack");
        }
        long wait = limiter.tryAcquire(caller.user());
        if (wait > 0) {
            audit(caller.user(), ref, q, "rate-limited", 0);
            throw new DrishtiException(ErrorCode.ASK_RATE_LIMITED, "too many questions; try again in " + wait + " seconds");
        }
        String guide = guides.sectionFor(pack, ref.kind(), ctx.mnemonic()).orElse(null);
        AskPrompt.Prompt prompt = AskPrompt.build(ctx, guide, q, cfg, HexFormat.of().formatHex(randomBytes()));
        long t0 = System.nanoTime();
        try {
            String text = provider.complete(prompt.system(), prompt.user(), cfg.maxAnswerTokens(), cfg.timeout());
            String answer = cap(text);
            audit(caller.user(), ref, q, "ok", ms(t0));
            if (cfg.logAnswers()) {
                LOG.info("ask answer user={} ref={}/{}: {}", caller.user(), ref.kind(), ref.id(), answer);
            }
            return new Answer(answer, prompt.sources());
        } catch (AskProvider.Failure f) {
            audit(caller.user(), ref, q, f.timeout() ? "timeout" : "failed", ms(t0));
            LOG.warn("ask failed for {}: {}", caller.user(), f.getMessage());
            throw new DrishtiException(f.timeout() ? ErrorCode.ASK_TIMEOUT : ErrorCode.ASK_FAILED,
                    "Ask is unavailable (" + f.getMessage() + "); the page explanation is complete without it");
        }
    }

    private String cap(String text) {
        String t = text == null ? "" : text.strip();
        return t.length() <= cfg.maxAnswerChars() ? t : t.substring(0, cfg.maxAnswerChars() - 1) + "…";
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return b;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    /** The audit row: who, which page, outcome and latency in the server log; in the access log as action {@code ask}, with the question text only when {@code log-questions} is on. Never the answer. */
    private void audit(String user, EntityRef ref, String question, String outcome, long latencyMs) {
        LOG.info("ask user={} ref={}/{} chars={} model={} outcome={} ms={}", user, ref.kind(), ref.id(), question.length(), cfg.model(), outcome, latencyMs);
        AccessLog sink = accessLog.getIfAvailable();
        if (sink != null) {
            String detail = outcome + (cfg.logQuestions() ? ": " + question.substring(0, Math.min(question.length(), 300)) : " (" + question.length() + " chars)");
            sink.record(new AccessLog.Event(Instant.now(), user, "ask", ref.kind(), ref.id(), detail, null));
        }
    }
}
