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
package com.ash.drishti.server.api;

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.rachana.SutraException;
import com.ash.rachana.SutraProblem;
import com.ash.drishti.rachana.SutraRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every API error is RFC 7807 {@code problem+json} with a stable {@code DRS-nnnn} code. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final SutraRegistry sutras;
    private final RefusalLog refusals;

    @org.springframework.beans.factory.annotation.Autowired
    ApiExceptionHandler(SutraRegistry sutras) {
        this(sutras, new RefusalLog(LOG, System::currentTimeMillis, 60_000));
    }

    ApiExceptionHandler(SutraRegistry sutras, RefusalLog refusals) {
        this.sutras = sutras;
        this.refusals = refusals;
    }

    /**
     * The problem an exception answers on a REST path, from this one table (the handlers below): what a typed kernel call refuses with
     * (KERNEL_AND_MODULES.md section 2.3), so its status, code and words are the REST path's. Null for an exception no handler maps
     * (the framework's own 500).
     */
    public ProblemDetail problem(RuntimeException e) {
        if (e instanceof SutraException x) {
            return sutra(x);
        }
        if (e instanceof com.ash.drishti.server.collab.RateLimitedException x) {
            return rateLimited(x).getBody();
        }
        if (e instanceof com.ash.drishti.server.bi.mq.MqException x) {
            return modelQuery(x);
        }
        if (e instanceof com.ash.drishti.server.bi.runs.RunProblem x) {
            return run(x).getBody();
        }
        if (e instanceof com.ash.drishti.server.caches.RefusedForNow x) {
            return refusedForNow(x).getBody();
        }
        if (e instanceof com.ash.drishti.server.admission.BusyRefusal x) {
            return busy(x).getBody();
        }
        if (e instanceof com.ash.drishti.server.security.CommandOffException x) {
            return commandOff(x);
        }
        if (e instanceof com.ash.drishti.server.engines.EngineOffException x) {
            return engineOff(x);
        }
        if (e instanceof com.ash.drishti.api.lifecycle.ComponentSuspended x) {
            return suspended(x);
        }
        if (e instanceof DrishtiException x) {
            return drishti(x);
        }
        if (e instanceof com.ash.lake.LakeException x && drishti(x) != null) {
            return lake(x);
        }
        if (e instanceof com.ash.rachana.RachanaException x && drishti(x) != null) {
            return rachana(x);
        }
        if (e instanceof java.time.format.DateTimeParseException x) {
            return badValue(x);
        }
        if (e instanceof IllegalArgumentException x) {
            return bad(x);
        }
        return null;
    }

    /** A Sutra problem names its file relative to the Sutra root; the absolute paths stay in the log only (SEC-11). */
    @ExceptionHandler(SutraException.class)
    ProblemDetail sutra(SutraException e) {
        LOG.info("Sutra rejected: {}", e.getMessage());
        List<SutraProblem> shown = sutras.relative(com.ash.drishti.rachana.DrishtiRachana.drs(e.problems()));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(ErrorCode.SUTRA_INVALID.httpStatus()),   // Core's code carries no status: the host's table does
                shown.size() + " problem(s): " + shown);
        p.setTitle(ErrorCode.SUTRA_INVALID.name().toLowerCase().replace('_', ' '));
        p.setProperty("code", ErrorCode.SUTRA_INVALID.code());
        p.setProperty("problems", shown);
        return p;
    }

    /** Over a collaboration rate limit: {@code 429 DRS-7003} with {@code Retry-After}. */
    @ExceptionHandler(com.ash.drishti.server.collab.RateLimitedException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> rateLimited(com.ash.drishti.server.collab.RateLimitedException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        p.setTitle("too many");
        p.setProperty("code", e.errorCode().code());
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    /**
     * A model query refused (docs/architecture/ARCHITECTURE.md §28 › The model layer: the specification of wave W1, section 5.7): the first problem's code, {@code path} and {@code fix}, and every problem of the
     * step that failed. A masked field ({@code DRS-9161}) also names {@code DRS-9021}, the engine's code for the same refusal.
     */
    @ExceptionHandler(com.ash.drishti.server.bi.mq.MqException.class)
    ProblemDetail modelQuery(com.ash.drishti.server.bi.mq.MqException e) {
        ProblemDetail p = drishti(e);
        com.ash.drishti.server.bi.mq.MqException.Problem first = e.problems().get(0);
        p.setProperty("path", first.path());
        p.setProperty("fix", first.fix());
        p.setProperty("problems", e.problems());
        if ("DRS-9161".equals(first.code())) {
            p.setProperty("also", ErrorCode.QUERY_MASKED.code());
        }
        return p;
    }

    /** A BI run refusal (docs/architecture/ARCHITECTURE.md §28 › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 16): its extra properties (estimate, confirmToken, run hint) and {@code Retry-After}. */
    @ExceptionHandler(com.ash.drishti.server.bi.runs.RunProblem.class)
    org.springframework.http.ResponseEntity<ProblemDetail> run(com.ash.drishti.server.bi.runs.RunProblem e) {
        ProblemDetail p = drishti(e);
        e.properties().forEach(p::setProperty);
        var b = org.springframework.http.ResponseEntity.status(p.getStatus()).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store");
        if (e.retryAfterSeconds() > 0) {
            b.header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
        }
        return b.body(p);
    }

    /** A date refused for room and remembered (VAR_GUIDE.md section 26.8): its code, and {@code Retry-After} the seconds left. */
    @ExceptionHandler(com.ash.drishti.server.caches.RefusedForNow.class)
    org.springframework.http.ResponseEntity<ProblemDetail> refusedForNow(com.ash.drishti.server.caches.RefusedForNow e) {
        ProblemDetail p = drishti(e);
        p.setProperty("retryAfterSeconds", e.retryAfterSeconds());
        return org.springframework.http.ResponseEntity.status(p.getStatus()).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds())).body(p);
    }

    /**
     * New work asked to wait because the server is very busy (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): {@code 503}, its
     * {@code DRS-95xx} code, {@code Retry-After}, and the calm words ({@code title}, {@code because}) the console and drishti.py show.
     */
    @ExceptionHandler(com.ash.drishti.server.admission.BusyRefusal.class)
    org.springframework.http.ResponseEntity<ProblemDetail> busy(com.ash.drishti.server.admission.BusyRefusal e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        p.setTitle(e.title());
        p.setProperty("code", e.errorCode().code());
        p.setProperty("busy", true);
        p.setProperty("work", e.work().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        p.setProperty("because", e.because());
        p.setProperty("retryAfterSeconds", e.retryAfterSeconds());
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store").body(p);
    }

    /** A command an administrator turned off (Admin → Functions): {@code 403 DRS-1016} with the command and since when, nothing else. */
    @ExceptionHandler(com.ash.drishti.server.security.CommandOffException.class)
    ProblemDetail commandOff(com.ash.drishti.server.security.CommandOffException e) {
        ProblemDetail p = drishti(e);
        e.properties().forEach(p::setProperty);
        return p;
    }

    /** A compute engine that runs nowhere (ADMIN_GUIDE.md section 5.1): {@code 404 DRS-1020} naming the engine. */
    @ExceptionHandler(com.ash.drishti.server.engines.EngineOffException.class)
    ProblemDetail engineOff(com.ash.drishti.server.engines.EngineOffException e) {
        ProblemDetail p = drishti(e);
        e.properties().forEach(p::setProperty);
        return p;
    }

    /** A suspended module, pack or connector (docs/architecture/LIFECYCLE.md): {@code 503 DRS-1037} naming it, who suspended it and since when. */
    @ExceptionHandler(com.ash.drishti.api.lifecycle.ComponentSuspended.class)
    ProblemDetail suspended(com.ash.drishti.api.lifecycle.ComponentSuspended e) {
        ProblemDetail p = drishti(e);
        e.properties().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler(DrishtiException.class)
    ProblemDetail drishti(DrishtiException e) {
        HttpStatus status = HttpStatus.valueOf(e.errorCode().httpStatus());
        if (RefusalLog.expected(e)) {
            refusals.refused(e);                     // another node is down or slow: one short line per change, counted (SOAK-06)
        } else if (e instanceof com.ash.drishti.api.lifecycle.ComponentSuspended) {
            LOG.debug("refused: {}", e.getMessage());   // an administrator suspended it: expected, never a fault (the audit has the suspend)
        } else if (status.is5xxServerError() && com.ash.drishti.api.UnreadableData.in(e).isPresent()) {
            // the source said what cannot be read and what to do (an Iceberg table moved, a codec not read): one line, no stack trace (QA3B-03)
            LOG.warn("request failed: {}", e.getMessage());
            LOG.debug("request failed: {}", e.getMessage(), e);
        } else if (status.is5xxServerError()) {
            LOG.warn("request failed: {}", e.getMessage(), e);
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(e.errorCode().name().toLowerCase().replace('_', ' '));
        p.setProperty("code", e.errorCode().code());
        if (e instanceof com.ash.drishti.server.bi.python.PythonFailure f && f.traceback() != null) {
            p.setProperty("traceback", f.traceback());          // only for a run an author asked for (PythonRuns)
        }
        return p;
    }

    /**
     * Drishti Lake's refusals ({@code LK-}, thrown by the lake's inbox, loader and memory guard the server hosts) as Drishti's codes with
     * the lake's words: a bad request {@code DRS-5001}, a file not there {@code DRS-1001}, too large {@code DRS-5005}, a refused
     * {@code .ready} {@code DRS-5008}, memory-only data {@code 403 DRS-5219}. {@code LK-1006} (unreadable data) is not mapped here.
     */
    @ExceptionHandler(com.ash.lake.LakeException.class)
    ProblemDetail lake(com.ash.lake.LakeException e) {
        DrishtiException d = drishti(e);
        if (d == null) {
            throw e;
        }
        return drishti(d);
    }

    /** A lake refusal as Drishti's own exception (its words without the lake's code), null for one Drishti does not map. */
    static DrishtiException drishti(com.ash.lake.LakeException e) {
        ErrorCode code = switch (e.error()) {
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            case NOT_FOUND -> ErrorCode.ENTITY_NOT_FOUND;
            case TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE;
            case READY_REFUSED -> ErrorCode.READY_PUSH_REFUSED;
            case MEMORY_ONLY -> ErrorCode.MEMORY_ONLY;
            default -> null;
        };
        if (code == null) {
            return null;
        }
        String m = e.getMessage();
        String prefix = e.error().code() + " ";
        return new DrishtiException(code, m.startsWith(prefix) ? m.substring(prefix.length()) : m, e);
    }

    /**
     * Rachana's refusals ({@code RCH-}, thrown while a screen answers: a panel or row a click names that is not there) as Drishti's code
     * of the same number ({@code DrishtiRachana.drs}, the edge's one mapping) with its status and Rachana's words; a code Drishti does
     * not have is not mapped here.
     */
    @ExceptionHandler(com.ash.rachana.RachanaException.class)
    ProblemDetail rachana(com.ash.rachana.RachanaException e) {
        DrishtiException d = drishti(e);
        if (d == null) {
            throw e;
        }
        return drishti(d);
    }

    /** A Rachana refusal as Drishti's own exception, null for a code Drishti does not have. */
    static DrishtiException drishti(com.ash.rachana.RachanaException e) {
        String drs = com.ash.drishti.rachana.DrishtiRachana.drs(e.code().code());
        for (ErrorCode c : ErrorCode.values()) {
            if (c.code().equals(drs)) {
                return new DrishtiException(c, e.words(), e);
            }
        }
        return null;
    }

    /** A required query parameter that is missing: {@code 400 DRS-5001} naming it (SEC-10), not the 401 of a missing principal. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(org.springframework.web.bind.MissingServletRequestParameterException e) {
        return badRequest("the query parameter '" + e.getParameterName() + "' is required");
    }

    /** A parameter of the wrong type, or a date that is not one ({@code from=xx}): {@code 400 DRS-5001}, no stack trace (SEC-10). */
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            java.time.format.DateTimeParseException.class})
    ProblemDetail badValue(Exception e) {
        if (e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m) {
            return badRequest("the parameter '" + m.getName() + "' has a value of the wrong type");
        }
        return badRequest("not a date (use yyyy-MM-dd): '" + ((java.time.format.DateTimeParseException) e).getParsedString() + "'");
    }

    private static ProblemDetail badRequest(String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        p.setTitle("bad request");
        p.setProperty("code", ErrorCode.BAD_REQUEST.code());
        return p;
    }

    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    ProblemDetail binding(org.springframework.web.bind.ServletRequestBindingException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "no principal on request");
        p.setProperty("code", "DRS-5010");
        return p;
    }

    /**
     * A body that is not JSON, or JSON of the wrong shape for the endpoint (a list for an object): {@code 400 DRS-5001}
     * saying so, never the framework's bare 400. The parser's own message is left out (it names internal classes).
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String detail = cause instanceof com.fasterxml.jackson.core.JsonParseException
                ? "the request body is not valid JSON"
                : cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException m && m.getPath().isEmpty()
                        && m.getMessage() != null && m.getMessage().contains("No content")
                        ? "the request body is empty: send a JSON object"
                        : cause instanceof com.fasterxml.jackson.databind.JsonMappingException
                                ? "the request body is JSON of the wrong shape for this endpoint (an object with the documented fields)"
                                : "the request body is missing or cannot be read: send a JSON object";
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        p.setTitle("bad request");
        p.setProperty("code", ErrorCode.BAD_REQUEST.code());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail bad(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setProperty("code", "DRS-5001");
        return p;
    }
}
