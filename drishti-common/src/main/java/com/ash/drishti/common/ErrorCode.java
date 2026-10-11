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
 * 3 inference, 4 engine and graph, 5 API, 6 identity, 7 collaboration (shares, threads, the inbox), 8 embedded views. Codes are never reused.
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
    /** A kind is routed to a connector (by a pack) for which no connector file or definition exists. */
    CONNECTOR_NOT_CONFIGURED("DRS-1011", 404),
    SUTRA_PARSE("DRS-2001", 422),
    SUTRA_INVALID("DRS-2002", 422),
    SUTRA_NOT_FOUND("DRS-2003", 404),
    PROPOSAL_NOT_FOUND("DRS-2005", 404),
    PROPOSAL_CONFLICT("DRS-2006", 409),
    FOUR_EYES("DRS-2007", 403),
    /** A search form (the form: section of a pack, or a Design's Form tab) that is not valid YAML or names a field, type or operator wrongly. */
    FORM_INVALID("DRS-2060", 422),
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
    /** A regular expression the search refuses: too long, too large once expanded, a feature the linear-time engine does not offer (back-references, look-around), or not valid. */
    REGEX_REFUSED("DRS-4010", 400),
    /** A regular expression ran over its time budget on one value. */
    REGEX_TIMEOUT("DRS-4011", 408),
    /** The AI layer (docs/architecture/AI_PROVIDERS.md) or the MCP client is switched off. */
    AI_OFF("DRS-4015", 404),
    /** An AI or MCP call failed at the provider or server (never carries a key or a prompt). */
    AI_FAILED("DRS-4016", 502),
    /** An AI or MCP call did not answer in time (same code as {@link #AI_FAILED}). */
    AI_TIMEOUT("DRS-4016", 504),
    /** An AI call is over a per-user rate or cost limit. */
    AI_RATE_LIMITED("DRS-4017", 429),
    /** The AI policy refuses the call for this user, role or feature. */
    AI_POLICY("DRS-4018", 403),
    /** No such model, provider, MCP server or tool. */
    AI_NOT_FOUND("DRS-4019", 404),
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
    /** A data-load announcement or a Data loads query names a pack that is not loaded (or one the caller may not reach: the two look the same). */
    LOAD_PACK_NOT_FOUND("DRS-5011", 404),
    /** A data-load announcement names a kind the pack does not own. */
    LOAD_KIND_UNKNOWN("DRS-5012", 422),
    /** A connector file or draft that fails validation. */
    CONNECTOR_INVALID("DRS-5031", 422),
    /** A connector changed since it was read (ETag mismatch), already exists, or is in use and the change was not confirmed. */
    CONNECTOR_CONFLICT("DRS-5032", 409),
    CONNECTOR_NOT_FOUND("DRS-5033", 404),
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
    MAIL_UNAVAILABLE("DRS-7012", 503),
    /** A bridge test, or a post, failed or the bridge is not usable: bridges off, its URL variable unset or not allowed, or the endpoint refused. */
    BRIDGE_UNAVAILABLE("DRS-7013", 503),
    /** No bridge with that name in {@code drishti.collab.bridges.webhooks}. */
    BRIDGE_NOT_FOUND("DRS-7014", 404),
    /** A picture was asked for where snapshots are off (the server's policy or the kind's pack), or no recipient could be shown one. */
    SNAPSHOT_REFUSED("DRS-7015", 403),
    /** The picture could not be drawn: it took longer than the timeout, is larger than the byte limit, or the renderer failed. */
    SNAPSHOT_FAILED("DRS-7016", 503),
    /** Embedded views (Drishti Elements): the embed token is missing, malformed, expired, for another audience, or embedding is off. */
    EMBED_TOKEN_INVALID("DRS-8001", 401),
    /** Embedded views: the request's {@code Origin} is not one of the host application's registered origins. */
    EMBED_ORIGIN("DRS-8002", 403),
    /** Embedded views: the host application is unknown or disabled, or its credentials are wrong. */
    EMBED_APP("DRS-8003", 403),
    /** Embedded views: over the host application's or the user's call rate ({@code Retry-After} says when to retry). */
    EMBED_RATE_LIMITED("DRS-8004", 429),
    /** Embedded views: the kind is not one this host application may show. */
    EMBED_KIND("DRS-8005", 400),
    /** Embedded views: the element's contract version is no longer served (raised by the console). */
    EMBED_VERSION_GONE("DRS-8006", 410),
    /** Business intelligence (Rupaka): no such workspace, item or version, or the caller may not know it exists. */
    BI_NOT_FOUND("DRS-9001", 404),
    /** Business intelligence: the caller lacks the BI power, or the workspace role, the action needs (the reason says which). */
    BI_FORBIDDEN("DRS-9002", 403),
    /** Business intelligence: a name, kind, body, audience, tag or endorsement that is not valid. */
    BI_INVALID("DRS-9003", 422),
    /** Business intelligence: the item or workspace is in a state that forbids this (name taken, already proposed, personal workspace cannot publish or be shared). */
    BI_CONFLICT("DRS-9004", 409),
    /** Business intelligence: with four-eyes on, nobody approves their own proposal. */
    BI_FOUR_EYES("DRS-9005", 403),
    /** Business intelligence is switched off on this server (drishti.bi.enabled). */
    BI_OFF("DRS-9006", 403),
    /** Datasets: the YAML is malformed or a key, name, type, mode or cardinality is not valid (the problem names the path). */
    DATASET_INVALID("DRS-9010", 422),
    /** Datasets: a measure, calculated column or row rule does not parse, names something the model lacks, or misuses an aggregate. */
    DATASET_EXPRESSION("DRS-9011", 422),
    /** Datasets: a relationship, hierarchy, rollup or security rule points at a table or column that does not exist, or the model cannot be joined as asked. */
    DATASET_RELATION("DRS-9012", 422),
    /** Datasets: the document uses a feature the grammar reserves and this server does not build yet (time intelligence, window functions, Python measures). */
    DATASET_NOT_BUILT("DRS-9013", 501),
    /** Queries: the query names a field, measure, operator or value the dataset does not have or cannot take. */
    QUERY_INVALID("DRS-9020", 422),
    /** Queries: the query filters, sorts on or probes a column masked for the caller. */
    QUERY_MASKED("DRS-9021", 403),
    /** Queries: the answer would exceed the row limit (drishti.bi.query.max-rows). */
    QUERY_ROWS("DRS-9022", 413),
    /** Queries: the answer would exceed the byte limit (drishti.bi.query.max-bytes). */
    QUERY_BYTES("DRS-9023", 413),
    /** Queries: the query ran past its time limit (drishti.bi.query.timeout) and was stopped. */
    QUERY_TIMEOUT("DRS-9024", 504),
    /** Queries: too many queries at once for the server or for this user (drishti.bi.query.max-concurrent, max-concurrent-per-user). */
    QUERY_BUSY("DRS-9025", 429),
    /** Queries: a table's connector cannot be read (not running, no SQL target for a direct table, nothing for the date). */
    QUERY_SOURCE("DRS-9026", 502),
    /** Queries: the analytic engine failed on a compiled query (a bug or a limit of the engine; the message says which). */
    QUERY_ENGINE("DRS-9027", 500);

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
