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
package com.ash.drishti.server.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.design.ops.Op;
import com.ash.drishti.rachana.design.ops.OpApplier;
import com.ash.drishti.rachana.design.ops.OpProblem;
import com.ash.drishti.rachana.design.ops.OpResult;
import com.ash.drishti.rachana.design.ops.Ops;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * A Design starts from {@code name@v}; when that Sutra later gets a newer live version the Design's base has moved. This notices
 * it ({@link #moved}) and offers a rebase: the Design's operations are replayed on the newer version, and whatever cannot be
 * replayed (a step that no longer applies, a hand-typed text edit) comes back as a problem instead of being lost silently.
 * Stateless and thread-safe.
 */
@Service
public class DesignRebase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DesignService designs;
    private final SutraRegistry sutras;
    private final OpApplier applier = new OpApplier();

    public DesignRebase(DesignService designs, SutraRegistry sutras) {
        this.designs = designs;
        this.sutras = sutras;
    }

    /** What a rebase did: the Design as it is now, the problems (steps not replayed) and the number of steps that were. */
    public record Rebased(StoredDesign design, List<OpProblem> problems, int replayed) {}

    /** {@code {base, name, from, to, latest}} when the Design's base Sutra has a newer version; else null. */
    public ObjectNode moved(StoredDesign d) {
        Optional<int[]> v = newer(d);
        if (v.isEmpty()) {
            return null;
        }
        String name = d.base.substring(0, d.base.lastIndexOf('@'));
        return JSON.createObjectNode().put("base", d.base).put("name", name).put("from", v.get()[0]).put("to", v.get()[1])
                .put("latest", name + "@" + v.get()[1]);
    }

    private Optional<int[]> newer(StoredDesign d) {
        if (d.base == null) {
            return Optional.empty();
        }
        int at = d.base.lastIndexOf('@');
        if (at <= 0) {
            return Optional.empty();
        }
        try {
            int from = Integer.parseInt(d.base.substring(at + 1));
            Optional<Sutra> latest = sutras.latest(d.base.substring(0, at));
            return latest.isPresent() && latest.get().version() > from ? Optional.of(new int[] {from, latest.get().version()}) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Replays the Design's applied operations on the newest version of its base; the Design's base becomes that version. */
    public Rebased rebase(String user, String id, int baseRev) {
        StoredDesign d = designs.get(user, id);
        Optional<int[]> v = newer(d);
        if (v.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design's base has not moved: nothing to rebase");
        }
        String name = d.base.substring(0, d.base.lastIndexOf('@'));
        String newBase = name + "@" + v.get()[1];
        String baseText = sutras.source(name, v.get()[1]).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, newBase));
        List<Op> flat = new ArrayList<>();
        List<String> where = new ArrayList<>();
        List<OpProblem> problems = new ArrayList<>();
        int step = 0;
        for (JsonNode entry : d.ops.subList(0, Math.min(d.opsAt, d.ops.size()))) {
            step++;
            JsonNode ops = entry.path("ops");
            if (ops.size() == 1 && "text".equals(ops.get(0).path("op").asText())) {
                problems.add(new OpProblem(-1, "text", "DRS-5025", "step " + step + " was a hand-typed text edit (or an auto-design, or a file sync): it cannot"
                        + " be replayed on " + newBase + "; make that change again by hand", 0));
                continue;
            }
            for (Op op : Ops.parse(ops)) {
                flat.add(op);
                where.add("step " + step);
            }
        }
        if (!d.ops.isEmpty() && d.ops.size() >= designs.limits().maxOps()) {
            problems.add(new OpProblem(-1, "log", "DRS-5025", "the step log is full (drishti.builder.designs.max-ops): older steps were dropped and"
                    + " cannot be replayed on " + newBase, 0));
        }
        OpResult r = applier.apply(baseText, flat);
        Set<Integer> failed = new HashSet<>();
        for (OpProblem p : r.problems()) {
            failed.add(p.op());
            problems.add(new OpProblem(p.op(), p.name(), p.code(), where.get(p.op()) + " (" + p.name() + ") no longer applies on " + newBase + ": "
                    + p.message(), p.line()));
        }
        List<Op> done = new ArrayList<>();
        for (int i = 0; i < flat.size(); i++) {
            if (!failed.contains(i)) {
                done.add(flat.get(i));
            }
        }
        ArrayNode kept = Ops.toJson(done);
        StoredDesign out = designs.rebase(user, id, baseRev, newBase, baseText, r.yaml(), kept);
        return new Rebased(out, problems, done.size());
    }
}
