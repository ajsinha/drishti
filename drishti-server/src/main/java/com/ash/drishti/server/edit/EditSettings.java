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
package com.ash.drishti.server.edit;

import com.ash.drishti.compute.ConfigValues;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Write-back's settings (WRITE_BACK.md section 13), read once at start from the folder {@code config/edit/} ({@code drishti.edit.file}
 * points at its {@code edit.yaml}): {@code edit.yaml} (the site switch, which shared log, the edit primary), {@code lanes.yaml} (lanes,
 * depth, warnings, the lease, the source command timeout), {@code policy.yaml} (approval per target, approver roles, self-approval, the
 * restate and override roles, per kind) and {@code retention.yaml}. Every key has its default, so a missing file means the shipped
 * values; {@code enabled: false} as shipped: a fresh install or an upgrade is read-only.
 *
 * @param enabled the site switch: no change is accepted anywhere while false
 * @param log {@code auto}, {@code sqlite} or {@code postgres} (section 11; {@code s3} is refused: not built)
 * @param primary the edit primary's node id in a cluster without PostgreSQL, or empty
 * @param follow how often a node that is not the edit primary reads the primary's applied changes (its overlays and owned values)
 * @param lanes P, a power of two (default 64)
 * @param maxLaneDepth a lane deeper than this refuses new changes ("busy, try again")
 * @param laneWarn a lane whose oldest change waits longer than this is warned about on Admin → Edits
 * @param laneLease how long a lane's owner holds it without renewing
 * @param commandTimeout how long a source command may stay sent and unconfirmed before Admin → Edits calls it unconfirmed
 * @param policy the approval policy
 * @param keep how long the edit log is kept (decision 16.3: 7 years)
 * @param hot how long entries stay in the hot window before they are archived
 * @param origin where the settings came from, for Admin → Edits
 */
public record EditSettings(boolean enabled, String log, String primary, Duration follow, int lanes, int maxLaneDepth, Duration laneWarn, Duration laneLease,
        Duration commandTimeout, Policy policy, Duration keep, Duration hot, String origin) {

    public static final List<String> LOGS = List.of("auto", "sqlite", "postgres");
    public static final List<String> TARGETS = List.of("overlay", "owned", "source");

    /**
     * The approval policy: approvals needed per target (0 none, 1 one, 2 two), who may approve, whether the submitter may approve their
     * own change (never by default), the role a restatement (a past business date) needs and the role {@code Apply mine anyway} needs.
     * {@code kinds} narrows per kind (a kind may only ask for more approvals, never fewer).
     */
    public record Policy(Map<String, Integer> approval, List<String> approverRoles, boolean selfApproval, String restateRole,
            String overrideRole, Map<String, Map<String, Integer>> kinds) {

        public Policy {
            approval = Map.copyOf(approval);
            approverRoles = List.copyOf(approverRoles);
            kinds = Map.copyOf(kinds);
        }

        /** Approvals a change to a kind's target needs: the site's, raised (never lowered) by the kind's and by the field's own. */
        public int needed(String kind, String target, int asked) {
            int site = approval.getOrDefault(target, "source".equals(target) ? 1 : 0);
            int forKind = kinds.getOrDefault(kind, Map.of()).getOrDefault(target, 0);
            return Math.min(2, Math.max(site, Math.max(forKind, asked)));
        }
    }

    public EditSettings {
        if (!LOGS.contains(log)) {
            throw new IllegalArgumentException("edit.log is auto, sqlite or postgres, not '" + log + "' (an S3 edit log is designed, not built)");
        }
        if (Integer.bitCount(lanes) != 1) {
            throw new IllegalArgumentException("lanes.lanes is a power of two (64), not " + lanes);
        }
        primary = primary == null ? "" : primary;
    }

    /** The shipped values: off, 64 lanes. */
    public static EditSettings defaults() {
        return parse(Map.of(), Map.of(), Map.of(), Map.of(), UnaryOperator.identity(), "defaults");
    }

    /** This copy with the site switch set (tests, and Admin's view after a change by configuration). */
    public EditSettings withEnabled(boolean on) {
        return new EditSettings(on, log, primary, follow, lanes, maxLaneDepth, laneWarn, laneLease, commandTimeout, policy, keep, hot, origin);
    }

    public EditSettings withLanes(int p) {
        return new EditSettings(enabled, log, primary, follow, p, maxLaneDepth, laneWarn, laneLease, commandTimeout, policy, keep, hot, origin);
    }

    public EditSettings withPolicy(Policy p) {
        return new EditSettings(enabled, log, primary, follow, lanes, maxLaneDepth, laneWarn, laneLease, commandTimeout, p, keep, hot, origin);
    }

    public EditSettings withPrimary(String node, String logKind) {
        return new EditSettings(enabled, logKind, node, follow, lanes, maxLaneDepth, laneWarn, laneLease, commandTimeout, policy, keep, hot, origin);
    }

    /** Reads {@code edit.yaml} and its siblings in the same folder; an absent file gives its defaults. */
    public static EditSettings load(Path editYaml, UnaryOperator<String> resolve) {
        Path dir = editYaml.toAbsolutePath().getParent();
        return parse(read(editYaml), read(dir.resolve("lanes.yaml")), read(dir.resolve("policy.yaml")), read(dir.resolve("retention.yaml")),
                resolve, Files.isRegularFile(editYaml) ? editYaml.toString() : "defaults (no " + editYaml + ")");
    }

    private static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            return doc == null ? Map.of() : doc;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " is not valid YAML: " + e.getMessage(), e);
        }
    }

    static EditSettings parse(Map<String, Object> edit, Map<String, Object> lanesDoc, Map<String, Object> policyDoc, Map<String, Object> retention,
            UnaryOperator<String> resolve, String origin) {
        ConfigValues e = new ConfigValues(edit, "", resolve).section("edit");
        ConfigValues l = new ConfigValues(lanesDoc, "", resolve).section("lanes");
        ConfigValues p = new ConfigValues(policyDoc, "", resolve).section("policy");
        ConfigValues r = new ConfigValues(retention, "", resolve).section("retention");
        Map<String, Integer> approval = new LinkedHashMap<>();
        ConfigValues a = p.section("approval");
        for (String t : TARGETS) {
            approval.put(t, approvals(a.text(t, "source".equals(t) ? "one" : "none"), "policy.approval." + t));
        }
        Map<String, Map<String, Integer>> kinds = new LinkedHashMap<>();
        ConfigValues ks = p.section("kinds");
        for (String k : ks.keys()) {
            ConfigValues one = ks.section(k).section("approval");
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String t : one.keys()) {
                m.put(t, approvals(one.text(t, "none"), "policy.kinds." + k + ".approval." + t));
            }
            kinds.put(k, m);
        }
        Policy policy = new Policy(approval, p.list("approver-roles"), p.flag("self-approval", false), p.text("restate-role", "restate"),
                p.text("override-role", "override"), kinds);
        return new EditSettings(e.flag("enabled", false), e.text("log", "auto"), e.text("primary", ""), e.duration("follow", Duration.ofSeconds(1)),
                l.integer("lanes", 64, 1, 4096), l.integer("max-lane-depth", 1_000, 1, 10_000_000),
                l.duration("lane-warn", Duration.ofSeconds(2)), l.duration("lane-lease", Duration.ofSeconds(10)),
                l.duration("command-timeout", Duration.ofSeconds(30)), policy, years(r.text("keep", "7y")), r.duration("hot", Duration.ofDays(90)),
                origin);
    }

    static int approvals(String v, String key) {
        return switch (v.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "none", "0" -> 0;
            case "one", "1", "four-eyes" -> 1;
            case "two", "2" -> 2;
            default -> throw new IllegalArgumentException(key + " is none, one or two, not '" + v + "'");
        };
    }

    /** {@code 7y}, {@code 90d} or any duration the other settings take. */
    static Duration years(String v) {
        String t = v.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.endsWith("y")) {
            return Duration.ofDays(365L * Long.parseLong(t.substring(0, t.length() - 1).strip()));
        }
        if (t.endsWith("d")) {
            return Duration.ofDays(Long.parseLong(t.substring(0, t.length() - 1).strip()));
        }
        return ConfigValues.duration(t, "retention.keep");
    }
}
