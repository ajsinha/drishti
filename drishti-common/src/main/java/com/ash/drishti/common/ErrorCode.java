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
 * 3 inference, 4 engine and graph, 5 API, 6 identity, 7 collaboration (shares, threads, the inbox). Codes are never reused.
 */
public enum ErrorCode {
    ENTITY_NOT_FOUND("DRS-1001", 404),
    NO_SOURCE_FOR_KIND("DRS-1002", 404),
    SOURCE_FAILED("DRS-1003", 502),
    SOURCE_TIMEOUT("DRS-1004", 504),
    INVALID_JSON("DRS-1005", 422),
    PLUGIN_LOAD_FAILED("DRS-1006", 500),
    /** A read "as known at" an instant from a store that keeps no earlier versions (it would show today's data). */
    NO_TIME_TRAVEL("DRS-1007", 400),
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
    /** {@code ?panel=} of an explain call names no panel of the view. */
    EXPLAIN_NO_PANEL("DRS-4006", 404),
    /** Ask about this page is switched off, globally or for the page's pack. */
    ASK_OFF("DRS-4007", 404),
    /** Ask: the model endpoint failed, is not configured, or answered something unusable. */
    ASK_FAILED("DRS-4008", 502),
    /** Ask: the model endpoint did not answer within the configured timeout (same code as {@link #ASK_FAILED}). */
    ASK_TIMEOUT("DRS-4008", 504),
    /** Ask: over the per-user rate or daily budget. */
    ASK_RATE_LIMITED("DRS-4009", 429),
    BAD_REQUEST("DRS-5001", 400),
    CACHE_NOT_FOUND("DRS-5004", 404),
    FORBIDDEN("DRS-5002", 403),
    /** Input over a configured size or count limit (builder samples). */
    PAYLOAD_TOO_LARGE("DRS-5005", 413),
    /** A Build workbench Design that does not exist, or is not the caller's (the two look the same). */
    DESIGN_NOT_FOUND("DRS-5006", 404),
    /** An edit made on an older revision of a Design than the server holds (reload, then edit again). */
    STALE_REVISION("DRS-5007", 409),
    UNAUTHENTICATED("DRS-5010", 401),
    USER_NOT_FOUND("DRS-6001", 404),
    USER_EXISTS("DRS-6002", 409),
    WEAK_PASSWORD("DRS-6003", 422),
    BAD_CREDENTIALS("DRS-6004", 401),
    ACCOUNT_LOCKED("DRS-6005", 423),
    LAST_ADMIN("DRS-6006", 409),
    INVALID_USER("DRS-6007", 422),
    ROLE_NOT_FOUND("DRS-6008", 404),
    ROLE_IN_USE("DRS-6009", 409),
    /** The console refuses everything but the account page until a password change asked for is done. */
    PASSWORD_CHANGE_DUE("DRS-6010", 403),
    /** No share with that id, or the caller is not its sender, a recipient or compliance (never a 403: the share's existence is not revealed). */
    SHARE_NOT_FOUND("DRS-7001", 404),
    /** No recipient, an unknown user or role, a role that may not be addressed, or over the recipient limits. */
    BAD_RECIPIENTS("DRS-7002", 422),
    /** Over a collaboration rate limit ({@code Retry-After} says when to try again). */
    COLLAB_RATE_LIMITED("DRS-7003", 429),
    /** Collaboration, the channel, or sharing for the kind's pack is off by policy. */
    SHARING_OFF("DRS-7004", 403),
    THREAD_NOT_FOUND("DRS-7005", 404),
    COMMENT_NOT_FOUND("DRS-7006", 404),
    THREAD_LOCKED("DRS-7007", 409),
    NOT_EDITABLE("DRS-7008", 403),
    STALE_COMMENT("DRS-7009", 409),
    ON_HOLD("DRS-7010", 423),
    /** Empty or too long text, a deny-pattern match, a masked copy with {@code on-masked-copy: reject}, or a bad pin. */
    TEXT_REFUSED("DRS-7011", 422),
    MAIL_UNAVAILABLE("DRS-7012", 503);

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
