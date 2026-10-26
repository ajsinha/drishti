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
    /** History queries: the kind keeps no daily history here (history off for it, or every connector of it holds today's data only). */
    HISTORY_NOT_KEPT("DRS-1012", 404),
    /** History queries: the entity has no data in the range asked. */
    HISTORY_NO_DATA("DRS-1013", 404),
    /** History queries: no day of the range was read within the budget, or too many history requests are running. */
    HISTORY_TIMEOUT("DRS-1014", 504),
    /** The connector a kind is routed to is being restarted (its file changed, a retry) and was not back within the read's wait. */
    CONNECTOR_RESTARTING("DRS-1015", 503),
    /**
     * An administrator turned the command off (Admin → Commands): its kind is not active on this server, by command, link, API, export, BI
     * or AI alike, and nothing of it is shown.
     */
    COMMAND_OFF("DRS-1016", 403),
    SUTRA_PARSE("DRS-2001", 422),
    SUTRA_INVALID("DRS-2002", 422),
    SUTRA_NOT_FOUND("DRS-2003", 404),
    PROPOSAL_NOT_FOUND("DRS-2005", 404),
    PROPOSAL_CONFLICT("DRS-2006", 409),
    FOUR_EYES("DRS-2007", 403),
    /** A search form (the form: section of a pack, or a Design's Form tab) that is not valid YAML or names a field, type or operator wrongly. */
    FORM_INVALID("DRS-2060", 422),
    /** The history: section of a pack names a field, shape or limit wrongly. */
    HISTORY_INVALID("DRS-2061", 422),
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
    /** BI with AI (docs/guides/BI_AI.md): the model's answer could not be used (not a valid report, not a query the engine accepts); nothing was changed. */
    AI_ANSWER("DRS-4020", 422),
    /** History queries: a range of more business days than the kind's or the server's max-days. */
    HISTORY_RANGE_TOO_LARGE("DRS-4021", 400),
    /** History queries: a range that cannot be read (TO before FROM, a future FROM, an unreadable date or range). */
    HISTORY_BAD_RANGE("DRS-4022", 400),
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
    /**
     * VaR: a row of a date's P&amp;L vectors rejected when the date loads (a vector of the wrong length, NaN or infinity in it, not in USD,
     * a failed row with a vector); never padded or loaded in part. Counted and listed in the answer's provenance, not thrown.
     */
    VAR_ROW_REJECTED("DRS-5201", 422),
    /** VaR: the same trade has two P&amp;L vectors on one business date: the date is not loaded. */
    VAR_DUPLICATE_TRADE("DRS-5202", 422),
    /** VaR: a date's inputs are not one run (several batch ids, a vector of another scenario set, two scenario sets, a bad scenario set). */
    VAR_INCONSISTENT_DATE("DRS-5203", 422),
    /** VaR: a date's vectors need more memory than the engine's budget (drishti.var.memory-budget-mb): capacity, try later or raise it. */
    VAR_CAPACITY("DRS-5204", 503),
    /** Exposure: a bad scope word or parameter ({@code q}, {@code collateral}, {@code date} not a grid date, {@code by}, {@code run}). */
    EXPOSURE_BAD_PARAMETER("DRS-5440", 400),
    /**
     * Exposure: a date's run is inconsistent (its grid, two batch ids, a duplicate row, a trade in two netting sets): the date is not
     * loaded and the date before answers with a warning; asked with {@code strict=true}, the answer is this refusal.
     */
    EXPOSURE_INCONSISTENT_RUN("DRS-5441", 409),
    /** Exposure: a what-if's candidate paths are not of the date's run (another run id, another number of paths or grid dates). */
    EXPOSURE_WHATIF_RUN("DRS-5442", 400),
    /** Exposure: the scope sums more netting sets than an interactive answer may (a bank-wide PFE): the official capture computes it. */
    EXPOSURE_BATCH_JOB("DRS-5443", 503),
    /**
     * Exposure: a row of a date's exposure paths rejected when the date loads (a vector of another length, NaN or infinity, a grid index
     * out of the run's grid); counted and listed in the answer's provenance, never padded.
     */
    EXPOSURE_ROW_REJECTED("DRS-5444", 422),
    /** Exposure: a date's netting-set matrices need more memory than the budget (drishti.ccr.exposure.memory-budget-gb). */
    EXPOSURE_CAPACITY("DRS-5445", 503),
    /**
     * Exposure: the exact what-if (method {@code paths}) could not be answered: the Python exposure engine (the reactive connector
     * {@code exposure-whatif} and its compute pool) is not running on this server, or did not answer in time.
     */
    EXPOSURE_METHOD_UNAVAILABLE("DRS-5446", 503),
    /** A connector file or draft that fails validation. */
    CONNECTOR_INVALID("DRS-5031", 422),
    /** A connector changed since it was read (ETag mismatch), already exists, or is in use and the change was not confirmed. */
    CONNECTOR_CONFLICT("DRS-5032", 409),
    CONNECTOR_NOT_FOUND("DRS-5033", 404),
    /** Admin, Artifacts: no installed artifact (pack, connector or BI file) or no archive of that name. */
    ARTIFACT_NOT_FOUND("DRS-5040", 404),
    /** Admin, Artifacts: an uninstall or disable was refused because other things depend on the artifact; the answer lists them. */
    ARTIFACT_IN_USE("DRS-5041", 409),
    /** Admin, Artifacts: a restore or an install found an artifact of that name already in place (replace it explicitly). */
    ARTIFACT_CONFLICT("DRS-5042", 409),
    /** Admin, Artifacts: a destructive action needs the artifact's name typed as confirmation. */
    ARTIFACT_CONFIRM("DRS-5043", 422),
    /** Admin, Artifacts: the action does not apply to this artifact (a pack template's connector is only disabled or reset) or an uploaded file is not valid. */
    ARTIFACT_INVALID("DRS-5044", 422),
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
    /** A start-up warning, never an HTTP answer: a {@code drishti.security.redact} entry names no field of the loaded packs' samples, so it masks nothing (Admin → Health, {@code masksMatchingNothing}). */
    MASK_MATCHES_NOTHING("DRS-6011", 500),
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
    /** An update names a saved state that is no longer the latest: someone saved since. */
    BI_STALE("DRS-9007", 409),
    /** Datasets: the YAML is malformed or a key, name, type, mode or cardinality is not valid (the problem names the path). */
    DATASET_INVALID("DRS-9010", 422),
    /** Datasets: a measure, calculated column or row rule does not parse, names something the model lacks, or misuses an aggregate. */
    DATASET_EXPRESSION("DRS-9011", 422),
    /** Datasets: a relationship, hierarchy, rollup or security rule points at a table or column that does not exist, or the model cannot be joined as asked. */
    DATASET_RELATION("DRS-9012", 422),
    /** Datasets: the document uses a feature the grammar reserves and this server does not build yet (time intelligence, window functions, Python measures). */
    DATASET_NOT_BUILT("DRS-9013", 501),
    /** Datasets: a composed dataset (from/join) names a source, column, measure or join key that does not exist, or joins keys of different types. */
    DATASET_COMPOSE("DRS-9014", 422),
    /** Datasets: a join that repeats rows (many-to-many, or the one side listed on the wrong end): measures of the repeated side add up too high. A warning of the check, not a refusal. */
    DATASET_FANOUT("DRS-9015", 422),
    /** Datasets: a dataset reads itself, directly or through the datasets it is composed of or derived from. */
    DATASET_CYCLE("DRS-9016", 422),
    /** Datasets: a derived dataset (made by a Python Transform) names no Transform, no inputs, or columns it cannot have. */
    DATASET_DERIVED("DRS-9017", 422),
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
    QUERY_ENGINE("DRS-9027", 500),
    /** Queries (wave B scale): the query needs more memory than the engine may use, even spilling to disk ({@code drishti.bi.query.memory-limit}). */
    QUERY_MEMORY("DRS-9028", 413),
    /** Queries (wave B scale): the planner's estimate of rows or bytes is over the query's budget ({@code options.budget} or the work class's); the message has the estimate and the fix. */
    QUERY_COST("DRS-9029", 413),
    /** Reports: the YAML is malformed or a key, id, grid position, filter or setting is not valid (the problem names the path). */
    REPORT_INVALID("DRS-9030", 422),
    /** Reports: a field, measure, hierarchy, page or bookmark the report names does not exist in its dataset or in the report. */
    REPORT_REFERENCE("DRS-9031", 422),
    /** Reports: a visual is built wrongly (a well it does not have, a measure in a field well or a field in a measure well, a required well empty, too many items). */
    REPORT_VISUAL("DRS-9032", 422),
    /** Reports: the report uses a visual type, filter or setting the grammar reserves and this server does not build yet. */
    REPORT_NOT_BUILT("DRS-9033", 501),
    /** Reports: the report's dataset cannot be found, or its model has errors, so the report cannot be drawn. */
    REPORT_DATASET("DRS-9034", 422),
    /** Reports: a visual query names a page or visual the report lacks, or its interaction state (slicers, selection, drill path) is not valid. */
    REPORT_STATE("DRS-9035", 422),
    /** Reports (2.4.0): a theme item is not valid (its palette, colours, font or visual defaults), or a report names a theme that does not exist. */
    THEME_INVALID("DRS-9036", 422),
    /** Reports (2.6.0): a well names {@code param.<name>} that is not a field parameter of the report's model, or whose choices do not fit the well. */
    REPORT_FIELD_PARAMETER("DRS-9037", 422),
    /** Reports (2.6.0): a statement (a page with {@code layout: statement}, or the report's {@code statement:} settings) is not valid, or it cannot be laid out. */
    STATEMENT_INVALID("DRS-9038", 422),
    /** Governance: publishing the dataset change would break published reports (a field, measure or hierarchy they use is removed or renamed); a bi-admin may force it. */
    BI_IMPACT("DRS-9040", 409),
    /** Governance: a BI bundle is not readable, fails its checksums, is too large or is not a bundle of this format. */
    BI_BUNDLE("DRS-9041", 422),
    /** Governance: an import would overwrite content that changed since it was exported (conflicts), or names a workspace or owner that cannot be mapped. */
    BI_IMPORT("DRS-9042", 409),
    /** A report or dataset declares expected values (tests) and one fails or cannot run: publishing is refused unless a bi-admin forces it. */
    BI_TESTS("DRS-9043", 409),
    /** Streaming BI: a live table cannot be kept (a table over an array, a kind no running connector serves, or its feed is failing and holds no rows). */
    LIVE_TABLE("DRS-9050", 422),
    /** Streaming BI: too many live streams are open for the server or for this user (drishti.bi.live.max-streams, max-streams-per-user). */
    LIVE_STREAM("DRS-9051", 429),
    /** Streaming BI: a board's YAML is malformed, or it names a report, page or visual that does not exist, or a tile or alert rule is not valid. */
    BOARD_INVALID("DRS-9052", 422),
    /** Streaming BI: the board uses a setting the grammar reserves and this server does not build (notifications, subscriptions). */
    BOARD_NOT_BUILT("DRS-9053", 501),
    /** Distribution: an export could not be made (exports are off, the target does not exist or cannot be read, the file is over the size limit, or it took too long). */
    EXPORT_FAILED("DRS-9060", 422),
    /** Distribution: a subscription is not valid (its target, schedule, format, recipients or view state), or the person has too many, or subscriptions are off. */
    SUBSCRIPTION_INVALID("DRS-9061", 422),
    /** Alerts: an alert on a measure or a field is not valid (its source, condition, trigger or delivery), or the person has too many, or alerts are off. */
    ALERT_INVALID("DRS-9062", 422),
    /** Python in BI: a Python item's manifest is not valid (interface, entry, inputs, packages, target, limits or source), or a report names a Python item that does not fit. */
    PYTHON_INVALID("DRS-9070", 422),
    /** Python in BI: the code raised an error or returned something that is not a number per group or a chart (the traceback is shown to authors only). */
    PYTHON_FAILED("DRS-9071", 422),
    /** Python in BI: the run was stopped for exceeding its time, memory or output limit. */
    PYTHON_LIMIT("DRS-9072", 413),
    /** Python in BI: the sandbox refused something the code tried (a package off the allow-list, the network, a file outside the scratch folder, a process). */
    PYTHON_SANDBOX("DRS-9073", 403),
    /** Python in BI: the runner has no free worker (bi.python.workers) or is switched off or not installed. */
    PYTHON_BUSY("DRS-9074", 503),
    /** Python in BI: a Transform returned something a derived dataset cannot use (not a frame, a declared column missing, too many rows) or its inputs do not match the dataset's. */
    TRANSFORM_FAILED("DRS-9075", 422),
    /** Python in BI: a test shipped with a Python item failed, so the item is not published (a bi-admin may force it). */
    PYTHON_TESTS("DRS-9076", 422),
    /** BI: a Vega-Lite specification could not be drawn (a url or loader in it, a library that did not load); raised by the page, in the visual's frame, and listed here so the code has one home. */
    VEGA_FAILED("DRS-9077", 422),
    /** BI runs (docs/architecture/BI_RUNS.md): no such run, or not one the caller may see. */
    RUN_NOT_FOUND("DRS-9080", 404),
    /** BI runs: the caller has {@code queue.per-user} runs queued or running, or the node's queue is at {@code queue.max} (Retry-After). */
    RUN_QUEUE_FULL("DRS-9081", 429),
    /** BI runs: an ad-hoc heavy question above the confirm threshold; the body carries the estimate and a confirm token. */
    RUN_CONFIRM("DRS-9082", 409),
    /** BI runs: the result of a run that is queued, running, failed or cancelled was asked for. */
    RUN_NOT_DONE("DRS-9083", 409),
    /** BI runs: the run's result was removed by retention or the storage budget (the run row stays, expired). */
    RUN_EXPIRED("DRS-9084", 410),
    /** BI runs: the server or node stopped while the run waited or ran, and it could not start again (attempts, access, a changed model). */
    RUN_INTERRUPTED("DRS-9085", 503),
    /** BI runs: the estimate exceeds every available engine's limits, or the result exceeds the store's per-result rows or bytes. */
    RUN_TOO_LARGE("DRS-9086", 413),
    /** BI runs: the node that owns the run did not answer. */
    RUN_NODE_UNREACHABLE("DRS-9087", 503),
    /** BI runs: the result store is at its budget and nothing can be removed. */
    RUN_STORE_FULL("DRS-9088", 507),
    /** BI runs: a dashboard interaction would need a background run; nothing ran; the body carries the estimate. */
    RUN_NEEDED("DRS-9089", 409),
    /** The model layer: a {@code rachana: 2} dataset that is not valid (its Check lists every problem); publishing it is refused. */
    LAYER_DATASET_INVALID("DRS-9100", 422),
    /** The model layer: a dataset source the server cannot use when it is read (a connector that is not running or has no SQL target, a query that fails). */
    LAYER_DATASET_SOURCE("DRS-9102", 422),
    /** The model layer: a pack-generated dataset is read-only (narrow it with {@code extends}). */
    LAYER_DATASET_GENERATED("DRS-9107", 409),
    /** The model layer: the query of a dataset whose source is a model does not validate, or its answer lacks a declared column. */
    LAYER_SOURCE_QUERY("DRS-9108", 422),
    /** The model layer: a model that reaches itself through a dataset whose source is a model (a cycle). */
    LAYER_SOURCE_CYCLE("DRS-9109", 422),
    /** The model layer: a data model that is not valid (its Check lists every problem); publishing it is refused. */
    LAYER_MODEL_INVALID("DRS-9120", 422),
    /** The model layer: a breaking change (a measure, column or relationship removed) published without a new major version, or a version that goes down. */
    LAYER_MODEL_BREAKING("DRS-9126", 409),
    /** The expression language (DEL): an expression that is not DEL v2 or does not fit its scope, its types or the catalogue (a measure that does not compile). */
    EXPR_INVALID("DRS-9127", 422),
    /** A key, function or value of a later wave used before that wave (filter context, parameters, calculation groups: wave B). */
    LATER_WAVE("DRS-9129", 422),
    /** The model layer's security safeguard: a dataset a restricted role would see in full, with neither a row rule nor a {@code may-see-all} note. */
    LAYER_MODEL_SAFEGUARD("DRS-9130", 409),
    /** Model Check warning: readers of a dataset that no row rule of the model names; they see no rows (never refuses a publish). */
    LAYER_MODEL_ROLES_WITHOUT_RULE("DRS-9131", 200),
    /** Model Check (a warning): a measure hidden for some roles whose columns those roles still see, so a default measure or a define reaches the number. */
    LAYER_MODEL_HIDDEN_REACHABLE("DRS-9132", 200),
    /** Model query: the shape (an unknown key, a wrong type, {@code mq} missing or not 1, two time forms). */
    MQ_SHAPE("DRS-9150", 422),
    /** Model query: the model is not found, not readable by the caller, retired, or a draft the caller may not query. */
    MQ_MODEL("DRS-9151", 404),
    /** Model query: a field, measure, hierarchy or id the model lacks. */
    MQ_NAME("DRS-9152", 422),
    /** Model query: a field or measure outside the model's contract. */
    MQ_CONTRACT("DRS-9153", 422),
    /** Model query: a local measure ({@code define}) that does not compile or shadows a model measure. */
    MQ_DEFINE("DRS-9154", 422),
    /** Model query: a filter with an operator or value the field's type does not allow, an unknown relative window, a top without n or by, a drill past the last level. */
    MQ_FILTER("DRS-9155", 422),
    /** Model query: a field, filter or row rule not reachable many-to-one from a measure's fact. */
    MQ_REACH("DRS-9156", 422),
    /** Model query: a time window over the limit, live with no live dataset, an as-of in the future. */
    MQ_TIME("DRS-9157", 422),
    /** Model query: amounts in several currencies added in one group (a warning in wave W1). */
    MQ_CURRENCY("DRS-9158", 422),
    /** Model query: a semi-additive measure across dates (a warning in wave W1). */
    MQ_SEMI_ADDITIVE("DRS-9159", 422),
    /** Model query: a path that repeats rows (row extracts across two facts). */
    MQ_FANOUT("DRS-9160", 422),
    /** Model query: a filter, sort or top on a field masked for the caller. */
    MQ_MASKED("DRS-9161", 403),
    /** Model query: a Python measure in a grouped query (a python visual runs it). */
    MQ_PYTHON("DRS-9162", 422),
    /** Model query: a field or measure hidden for the caller's roles (hidden-for): the same answer as a name the model lacks, said as not available to them. */
    MQ_NOT_FOR_ROLE("DRS-9163", 422),
    /** Model query (wave B): a parameter value the model does not take ({@code params}: unknown name, wrong kind, outside min..max, a field parameter's value not among its fields). */
    MQ_PARAM("DRS-9164", 422),
    /** Model query (wave B): a calculation group or item the model does not have ({@code calc}), or a measure the item cannot apply to. */
    MQ_CALC_GROUP("DRS-9165", 422),
    /** Model query (wave B): a filter-context or time function that cannot be planned (via names no relationship, parent_of no hierarchy, as_of no constant date, a context deeper than three). */
    MQ_CONTEXT("DRS-9166", 422),
    /** The expression converter could not keep the meaning of an old-syntax expression (it is left as it was). */
    EXPR_CONVERT("DRS-9190", 422),
    /** Drishti SQL: not a statement of the dialect (syntax, more than one statement, DDL or DML: it is read-only). */
    SQL_SYNTAX("DRS-9170", 422),
    /** Drishti SQL: a table or column the model does not have for the caller (a hidden column is not found), or of a table the statement does not read. */
    SQL_NAME("DRS-9171", 422),
    /** Drishti SQL: a join on a predicate that is not a relationship of the model. */
    SQL_JOIN("DRS-9172", 422),
    /** Drishti SQL: a function the dialect does not take (table functions, user functions, random()). */
    SQL_FUNCTION("DRS-9173", 422),
    /** Drishti SQL: an aggregate over a measure, or a measure in WHERE. */
    SQL_MEASURE("DRS-9174", 422),
    /** Drishti SQL: a window over the rows of a dataset (windows run over the answer). */
    SQL_WINDOW("DRS-9175", 422),
    /** Drishti SQL: another model, a connector, a kind or a file named. */
    SQL_SCOPE("DRS-9176", 422),
    /** Drishti SQL: two different times in one statement, or a time clause that is not valid. */
    SQL_TIME("DRS-9177", 422),
    /** Drishti SQL: SELECT DISTINCT on a fact without grouping. */
    SQL_DISTINCT("DRS-9178", 422),
    /** Drishti SQL: a statement the dialect does not take (yet); the message says what to write. */
    SQL_SHAPE("DRS-9179", 422),
    /** Standing query: no such subscription for the caller. */
    SQ_NOT_FOUND("DRS-9180", 404),
    /** Standing query: the server holds {@code drishti.bi.mq.max-subscriptions} already. */
    SQ_SERVER_LIMIT("DRS-9181", 429),
    /** Standing query: the caller holds {@code drishti.bi.mq.max-subscriptions-per-user} already. */
    SQ_USER_LIMIT("DRS-9182", 429),
    /** Standing query: a subscription to a past as-of date, which never changes. */
    SQ_PAST("DRS-9183", 422),
    /** Standing query (wave B scale): the node that owns the model's shard could not be reached to forward the subscription; the caller retries (Retry-After). */
    SQ_OWNER_UNREACHABLE("DRS-9184", 503),
    /** Drishti*net: the cluster is off on this node (no config/net/node.yaml with enabled: true), so its cluster endpoints are not served. */
    NET_OFF("DRS-5050", 404),
    /** Drishti*net: a call between nodes without a valid cluster credential (no key, a bad signature, outside the 60 s window, a bad on-behalf-of token). */
    NET_REFUSED("DRS-5051", 401),
    /** Drishti*net: the peer a request was forwarded to failed, or answered something that is not an answer; the next peer was tried once. */
    NET_PEER_FAILED("DRS-5052", 502),
    /** Drishti*net: the peer a request was forwarded to did not answer within routing.yaml's forward-timeout. */
    NET_PEER_TIMEOUT("DRS-5053", 504),
    /** Drishti*net: the caller's roles may not reach the node (access.yaml), or the node takes no forwarded work (drained). */
    NET_NOT_REACHABLE("DRS-5054", 403),
    /** Drishti*net: a file of config/net/ was changed since it was read (If-Match), or a setting is refused; nothing was written. */
    NET_FILE_REFUSED("DRS-5055", 409),
    /** Drishti*net: a management request that cannot be done (an unknown member, removing a member that is alive, a malformed seed). */
    NET_BAD_REQUEST("DRS-5056", 400),
    /** Content store (docs/architecture/CONTENT_STORE.md): the item changed elsewhere since it was read (or already exists): reload and try again. */
    CONTENT_CONFLICT("DRS-5057", 409),
    /** Content store: a key that is not a valid content key (empty, too long, a {@code ..} segment, control characters). */
    CONTENT_BAD_KEY("DRS-5058", 400),
    /** Content store: the backing store (database, bucket, shared folder) could not be reached or answered with an error. */
    CONTENT_UNAVAILABLE("DRS-5059", 503),
    /** Compute workers (docs/architecture/PYTHON_COMPUTE.md): compute is off on this server ({@code compute.enabled}). */
    COMPUTE_OFF("DRS-9201", 503),
    /** Compute: refused before any work (unknown pool, requirements not approved, an environment not built, four eyes, a bad request). */
    COMPUTE_REFUSED("DRS-9202", 409),
    /** Compute: the pool's queue for the request's class is full: try again in a moment. */
    COMPUTE_BUSY("DRS-9203", 429),
    /** Compute: the computation passed its time budget and was stopped. */
    COMPUTE_TIMEOUT("DRS-9204", 504),
    /** Compute: the computation failed, crashed, passed a limit or was refused by its engine; the message says which. */
    COMPUTE_FAILED("DRS-9205", 502),
    /** Compute: no such approval request or running computation. */
    COMPUTE_NOT_FOUND("DRS-9206", 404),
    /** Write-back (docs/architecture/WRITE_BACK.md): editing is off on this server ({@code edit.enabled: false} in config/edit/edit.yaml). */
    EDIT_OFF("DRS-9301", 403),
    /** Write-back: the change is refused: the field is not editable for you (no Sutra says so, a mask, a role, a read-only field, a restatement without the role), or the target is not usable. */
    EDIT_REFUSED("DRS-9302", 403),
    /** Write-back: the fields you changed have moved since you opened the record; the answer carries the three-way difference. */
    EDIT_CONFLICT("DRS-9303", 409),
    /** Write-back: the change's lane is full ({@code lanes.max-lane-depth}): busy, try again in a moment; your draft is kept. */
    EDIT_BUSY("DRS-9304", 503),
    /** Write-back: no such change. */
    EDIT_NOT_FOUND("DRS-9305", 404),
    /** Write-back: edits are paused (the edit primary is not reachable, or this cluster has no shared edit log); reading is not affected. */
    EDIT_PAUSED("DRS-9306", 503);

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
