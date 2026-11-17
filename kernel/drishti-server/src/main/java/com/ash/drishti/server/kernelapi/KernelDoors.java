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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.server.api.ApiExceptionHandler;
import com.ash.drishti.server.security.TokenScopes;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * The doors a typed kernel call passes that the REST path's filters held (KERNEL_AND_MODULES.md section 2.3, the table of doors): the
 * token's scope for the REST path it replaces (one table, {@link TokenScopes}), the refusal as the REST path answers it (one table,
 * {@link ApiExceptionHandler#problem}), and the time the loopback call was given. A read runs on a thread of its own, as the loopback's
 * request did, so nothing of the calling request's thread leaks into it.
 */
@Component
public class KernelDoors {

    /** The time a call is given, as the loopback gave it: an engine's first answer of a business date loads its inputs. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ApiExceptionHandler problems;
    private final TokenScopes scopes;
    private final ExecutorService calls = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-kernel-call-", 0).factory());

    public KernelDoors(ApiExceptionHandler problems, TokenScopes scopes) {
        this.problems = problems;
        this.scopes = scopes;
    }

    @PreDestroy
    void close() {
        calls.shutdownNow();
    }

    /**
     * A read for the person, as {@code method path} (the REST path it replaces) would answer it: refused when the person's API token lacks
     * the path's scope, a refusal of the body mapped as the REST path maps it, and {@code 503 DRS-5003} after {@link #TIMEOUT}.
     *
     * @throws Kernel.Refused with the REST path's status, code and words
     */
    <T> T call(RequestPerson p, String method, String path, Supplier<T> body) {
        scope(p, method, path);
        Future<T> f = calls.submit(body::get);
        try {
            return f.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new Kernel.Refused(503, "DRS-5003", "this server did not answer its own call in " + TIMEOUT.toSeconds() + " s: " + method + " " + path);
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new Kernel.Refused(503, "DRS-5003", "interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw refusal(r);
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(c);
        }
    }

    /** As {@link #call}, on the calling thread: a door with nothing to read ({@code Access}). */
    <T> T now(Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException r) {
            throw refusal(r);
        }
    }

    /** Refuses as the token filter refuses ({@code 403 DRS-5002}) a person whose API token lacks the scope of {@code method path}. */
    void scope(RequestPerson p, String method, String path) {
        if (p.scopes() == null) {
            return;
        }
        TokenScopes.Verdict v = scopes.check(p.scopes(), method, path);
        if (v != TokenScopes.Verdict.ALLOWED) {
            throw new Kernel.Refused(403, "DRS-5002", scopes.refusal(v, method, path));
        }
    }

    /** The refusal a REST path answers for {@code e}: its status, code and words; {@code e} itself when no handler maps it. */
    RuntimeException refusal(RuntimeException e) {
        if (e instanceof Kernel.Refused) {
            return e;
        }
        ProblemDetail pd = problems.problem(e);
        if (pd == null) {
            return e;
        }
        Object code = pd.getProperties() == null ? null : pd.getProperties().get("code");
        return new Kernel.Refused(pd.getStatus(), code == null ? "HTTP-" + pd.getStatus() : String.valueOf(code),
                pd.getDetail() == null ? String.valueOf(pd.getStatus()) : pd.getDetail());
    }
}
