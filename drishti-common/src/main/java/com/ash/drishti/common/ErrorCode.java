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
package com.ash.drishti.common;

/**
 * Stable error codes, {@code DRS-nnnn}. The first digit groups them: 1 sources and data, 2 Sutra,
 * 3 inference, 4 engine and graph, 5 API, 6 identity. Codes are never reused.
 */
public enum ErrorCode {
    ENTITY_NOT_FOUND("DRS-1001", 404),
    NO_SOURCE_FOR_KIND("DRS-1002", 404),
    SOURCE_FAILED("DRS-1003", 502),
    SOURCE_TIMEOUT("DRS-1004", 504),
    INVALID_JSON("DRS-1005", 422),
    PLUGIN_LOAD_FAILED("DRS-1006", 500),
    SUTRA_PARSE("DRS-2001", 422),
    SUTRA_INVALID("DRS-2002", 422),
    SUTRA_NOT_FOUND("DRS-2003", 404),
    PROPOSAL_NOT_FOUND("DRS-2005", 404),
    PROPOSAL_CONFLICT("DRS-2006", 409),
    FOUR_EYES("DRS-2007", 403),
    EL_SYNTAX("DRS-2101", 422),
    EL_EVAL("DRS-2102", 422),
    INFERENCE_FAILED("DRS-3001", 500),
    COMMAND_UNKNOWN("DRS-4001", 400),
    VIEW_FAILED("DRS-4002", 500),
    BAD_BUSINESS_DATE("DRS-4003", 400),
    BAD_SEARCH("DRS-4004", 400),
    BAD_REQUEST("DRS-5001", 400),
    CACHE_NOT_FOUND("DRS-5004", 404),
    FORBIDDEN("DRS-5002", 403),
    UNAUTHENTICATED("DRS-5010", 401),
    USER_NOT_FOUND("DRS-6001", 404),
    USER_EXISTS("DRS-6002", 409),
    WEAK_PASSWORD("DRS-6003", 422),
    BAD_CREDENTIALS("DRS-6004", 401),
    ACCOUNT_LOCKED("DRS-6005", 423),
    LAST_ADMIN("DRS-6006", 409),
    INVALID_USER("DRS-6007", 422);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
