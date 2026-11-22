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
package com.ash.drishti.server.live;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.rachana.live.spi.Change;
import com.ash.rachana.live.spi.ChangeSink;
import com.ash.rachana.table.LiveTableSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Drishti's write-back bound as the live tables' sink (LT-8, i8 step 3): a checked change is handed to the edit service as the person
 * who made it, under the declaration's form ({@code live:<table>}), with the version it was made on and its change id; lanes, approvals
 * (four eyes: the change waits as {@code held}), the outbox, cluster ownership and connector writes stay the write-back's. Rachana's own
 * outbox is not installed. The person is the request's: the protocol mount names them for the write it serves ({@link #as}), and a
 * change whose maker ({@code by}) is not that person is refused, so the caller always reaches the write-back and its audit record. The
 * change id is Rachana's end to end: the write-back keeps it, so {@link #madeBy} and {@link #prior} answer a repeat from the write-back's
 * own durable record (edit log, approval queue) on any node and after a restart (PROTOCOL 16).
 */
public final class DrishtiChangeSink implements ChangeSink {

    /** The form a declaration's edits are checked under in the write-back: {@code live:<table>}, its one panel. */
    public static String formName(String table) {
        return "live:" + table;
    }

    /** The write-back's panel name for a live table's form. */
    public static final String PANEL = "edit";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<Principal> CALLER = new ThreadLocal<>();

    private final Function<String, LiveTableSpec> specs;
    private final BiFunction<Principal, JsonNode, List<Map<String, Object>>> submit;
    private final Function<String, Optional<Map<String, Object>>> known;

    /**
     * @param specs the declarations by table name
     * @param submit the write-back's submit as a person
     * @param known what the write-back knows of a change id ({@code EditService.known}: {@code {state, by, needed?, message?}})
     */
    public DrishtiChangeSink(Function<String, LiveTableSpec> specs, BiFunction<Principal, JsonNode, List<Map<String, Object>>> submit,
            Function<String, Optional<Map<String, Object>>> known) {
        this.specs = specs;
        this.submit = submit;
        this.known = known;
    }

    /** The person the calling thread writes as (the mount's mark), or null outside a write. */
    static Principal caller() {
        return CALLER.get();
    }

    @Override
    public String madeBy(String changeId) {
        return known.apply(changeId).map(m -> m.get("by") == null ? null : String.valueOf(m.get("by"))).orElse(null);
    }

    /** The digest of the change first taken under the id, as the write-back kept it with the change (so a reused id is refused on this path too). */
    @Override
    public String priorDigest(String changeId) {
        return known.apply(changeId).map(m -> m.get("digest") == null ? null : String.valueOf(m.get("digest"))).orElse(null);
    }

    @Override
    public Outcome prior(String changeId) {
        return known.apply(changeId).map(DrishtiChangeSink::outcome).orElse(null);
    }

    /** The write-back's state of a change as the protocol says it. */
    static Outcome outcome(Map<String, Object> m) {
        String state = String.valueOf(m.getOrDefault("state", "queued"));
        Object said = m.get("message");
        return switch (state) {
            case "awaiting" -> new Outcome(State.HELD, "waiting for approval" + (m.get("needed") == null ? "" : " (" + m.get("needed")
                    + " needed)"), null);
            case "applied" -> new Outcome(State.APPLIED, null, null);
            case "rejected", "withdrawn" -> new Outcome(State.REJECTED, said == null ? state : String.valueOf(said), null);
            case "refused", "conflicted" -> new Outcome(State.FAILED, said == null ? state : String.valueOf(said), null);
            default -> new Outcome(State.QUEUED, null, null);
        };
    }

    /** Runs a write as the person (the sink submits as them); the mark is the calling thread's and is removed after. */
    public static <T> T as(Principal p, java.util.function.Supplier<T> write) {
        Principal before = CALLER.get();
        CALLER.set(p);
        try {
            return write.get();
        } finally {
            if (before == null) {
                CALLER.remove();
            } else {
                CALLER.set(before);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Outcome accept(Change c) {
        Principal p = CALLER.get();
        if (p == null || !p.user().equals(c.by())) {
            return new Outcome(State.REFUSED, "the change was not made through a signed-in request", null);
        }
        LiveTableSpec spec = specs.apply(c.table());
        if (spec == null || !"update".equals(c.op())) {
            return new Outcome(State.REFUSED, "the write-back takes amendments of existing rows only", null);
        }
        Map<String, Object> save = spec.edit().get("save") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        ObjectNode body = JSON.createObjectNode();
        body.put("kind", save.get("kind") != null ? String.valueOf(save.get("kind")) : DrishtiAuthoriser.kind(spec));
        body.put("entity", String.valueOf(c.key().get(0)));
        body.set("basedOn", JSON.valueToTree(c.basedOn()));
        body.put("sutra", formName(c.table()));
        body.put("panel", PANEL);
        body.put("via", "blotter");
        body.put("id", c.id());
        body.put("digest", c.digest());
        if (c.reason() != null) {
            body.put("reason", c.reason());
        }
        ArrayNode items = body.putArray("items");
        c.set().forEach((col, to) -> {
            ObjectNode i = items.addObject();
            i.put("field", col);
            i.set("from", JSON.valueToTree(c.from().get(col)));
            i.set("to", JSON.valueToTree(to));
        });
        List<Map<String, Object>> got;
        try {
            got = submit.apply(p, body);
        } catch (RuntimeException e) {
            return new Outcome(State.REFUSED, e.getMessage(), null);
        }
        Map<String, Object> first = got.isEmpty() ? Map.of() : got.get(0);
        if (first.get("id") != null && !c.id().equals(first.get("id"))) {
            // never answered under another id: the change would not be found again by the id its maker holds
            return new Outcome(State.REFUSED, "the write-back did not keep the change id " + c.id(), null);
        }
        if ("rejected".equals(first.get("state"))) {
            return new Outcome(State.REFUSED, String.valueOf(first.getOrDefault("message", "rejected")), null);
        }
        return outcome(first);
    }
}
