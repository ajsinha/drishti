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
package com.ash.drishti.server.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Security is on (the default, {@code drishti.security.enabled}) and no token secret is set ({@code DRISHTI_TOKEN_SECRET}): the server
 * refuses to start, and {@link Analyzer} says why and what to do instead of a stack trace.
 */
public final class SecurityNotConfiguredException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** What to do, in words. */
    public static final String ACTION = "Set DRISHTI_TOKEN_SECRET (at least 32 bytes, the same value for the console), or, for local "
            + "development only, start with the dev profile (--spring.profiles.active=dev or SPRING_PROFILES_ACTIVE=dev): sign-in off and "
            + "the development admin seeded. Setting DRISHTI_SECURITY_ENABLED=false turns sign-in off as well.";

    public SecurityNotConfiguredException() {
        super("Security is on (drishti.security.enabled, the default) and DRISHTI_TOKEN_SECRET is not set: the server will not start. "
                + ACTION);
    }

    /** The start-up failure in words (registered in META-INF/spring.factories). */
    public static final class Analyzer extends AbstractFailureAnalyzer<SecurityNotConfiguredException> {
        @Override
        protected FailureAnalysis analyze(Throwable root, SecurityNotConfiguredException cause) {
            return new FailureAnalysis("Security is on (drishti.security.enabled, the default) and DRISHTI_TOKEN_SECRET is not set.",
                    ACTION, cause);
        }
    }
}
