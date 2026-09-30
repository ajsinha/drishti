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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.rachana.SutraException;
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

    @ExceptionHandler(SutraException.class)
    ProblemDetail sutra(SutraException e) {
        ProblemDetail p = drishti(e);
        p.setProperty("problems", e.problems());
        return p;
    }

    @ExceptionHandler(DrishtiException.class)
    ProblemDetail drishti(DrishtiException e) {
        HttpStatus status = HttpStatus.valueOf(e.errorCode().httpStatus());
        if (status.is5xxServerError()) {
            LOG.warn("request failed: {}", e.getMessage(), e);
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        p.setTitle(e.errorCode().name().toLowerCase().replace('_', ' '));
        p.setProperty("code", e.errorCode().code());
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail bad(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setProperty("code", "DRS-5001");
        return p;
    }
}
