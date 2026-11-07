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
package com.ash.drishti.server.settings;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Changes to settings proposed in Admin → Configuration and decided by a second administrator (four eyes; docs/admin/OPERATIONS.md, "The
 * configuration editor"). A proposal names a key, the area whose file holds it ({@code server}, {@code engines} or {@code console}), the
 * new value and why. Nothing changes until another administrator approves it: the person who proposed may withdraw it, never approve or
 * reject it. An approved change of the server's (or an engine's) key is written to the server's {@code application.local.yaml} as one
 * flat line (comments and every other line kept), read at the next start; a console key is written by the console that approved it
 * ({@link #markApplied}). Secrets are never proposed here (they stay in the environment or a secrets file). Every step is an audit entry.
 * The proposals are kept in one JSON file under the data folder, written atomically.
 */
public final class SettingProposals {

    /** One proposal. {@code state}: proposed, approved, rejected or withdrawn; {@code applied}: written to its file. */
    public record Proposal(String id, String key, String area, String value, String was, String reason, String proposedBy, String proposedAt,
            String state, String decidedBy, String decidedAt, String comment, boolean applied) {

        Proposal decided(String newState, String by, String at, String why) {
            return new Proposal(id, key, area, value, was, reason, proposedBy, proposedAt, newState, by, at, why, applied);
        }

        Proposal asApplied() {
            return new Proposal(id, key, area, value, was, reason, proposedBy, proposedAt, state, decidedBy, decidedAt, comment, true);
        }
    }

    static final Set<String> AREAS = Set.of("server", "engines", "console");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_.\\-\\[\\]]{0,199}");
    private static final Pattern SECRET = Pattern.compile("(?i).*(secret|password|passwd|passphrase|token|credential|api-?key|private).*");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEEP = 500;

    private final Path store;
    private final Path localFile;
    private final AuditLog audit;
    private final Clock clock;

    /**
     * @param store the proposals' file ({@code <drishti.data.dir>/settings/proposals.json})
     * @param localFile the server's {@code application.local.yaml} (the file application.yaml imports from the working directory)
     */
    public SettingProposals(Path store, Path localFile, AuditLog audit, Clock clock) {
        this.store = store;
        this.localFile = localFile;
        this.audit = audit;
        this.clock = clock;
    }

    /** Newest first. */
    public synchronized List<Proposal> list() {
        List<Proposal> all = new ArrayList<>(read());
        java.util.Collections.reverse(all);
        return all;
    }

    /** Files a proposal; refused for a secret, an unknown area, a malformed key or a second open proposal of the same key. */
    public synchronized Proposal propose(String key, String area, String value, String was, String reason, String by) {
        String k = key == null ? "" : key.strip();
        if (!KEY.matcher(k).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "not a setting's key: " + key);
        }
        if (!AREAS.contains(area)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "settings of " + area + " are changed in their own files or screens, not here");
        }
        if (SECRET.matcher(k).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a secret is never proposed here: set " + k + " in the environment or the secrets file");
        }
        if (value == null || value.length() > 2000 || value.contains("\n")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the value must be one line of at most 2000 characters");
        }
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why: the approver reads it");
        }
        List<Proposal> all = new ArrayList<>(read());
        if (all.stream().anyMatch(p -> p.key().equals(k) && p.area().equals(area) && "proposed".equals(p.state()))) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, k + " already has a change waiting for approval: decide that one first");
        }
        Proposal p = new Proposal(newId(), k, area, value, was == null ? "" : was, reason.strip(), by, now(), "proposed", "", "", "", false);
        all.add(p);
        write(all);
        audit.record(by, "setting-proposed", "setting/" + k, area + " | " + value + " | " + p.reason());
        return p;
    }

    /** Approves or rejects; four eyes: never your own. An approved server or engine key is written to application.local.yaml at once. */
    public synchronized Proposal decide(String id, boolean approve, String comment, String by) {
        List<Proposal> all = new ArrayList<>(read());
        int at = indexOf(all, id);
        Proposal p = all.get(at);
        if (!"proposed".equals(p.state())) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "proposal " + id + " is already " + p.state());
        }
        if (p.proposedBy().equals(by)) {
            throw new DrishtiException(ErrorCode.FOUR_EYES, "four eyes: a second administrator decides your proposal");
        }
        Proposal out = p.decided(approve ? "approved" : "rejected", by, now(), comment == null ? "" : comment.strip());
        if (approve && !"console".equals(p.area())) {
            writeLocal(p, by);
            out = out.asApplied();
        }
        all.set(at, out);
        write(all);
        audit.record(by, approve ? "setting-approved" : "setting-rejected", "setting/" + p.key(), p.area() + " | " + p.value() + " | proposed by "
                + p.proposedBy() + (out.comment().isEmpty() ? "" : " | " + out.comment()));
        return out;
    }

    /** The proposer takes it back. */
    public synchronized Proposal withdraw(String id, String by) {
        List<Proposal> all = new ArrayList<>(read());
        int at = indexOf(all, id);
        Proposal p = all.get(at);
        if (!"proposed".equals(p.state()) || !p.proposedBy().equals(by)) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "only its proposer withdraws a proposal still waiting");
        }
        Proposal out = p.decided("withdrawn", by, now(), "");
        all.set(at, out);
        write(all);
        audit.record(by, "setting-withdrawn", "setting/" + p.key(), p.area());
        return out;
    }

    /** A console says it wrote an approved console key to its own file. */
    public synchronized Proposal markApplied(String id, String by) {
        List<Proposal> all = new ArrayList<>(read());
        int at = indexOf(all, id);
        Proposal p = all.get(at);
        if (!"approved".equals(p.state())) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "proposal " + id + " is not approved");
        }
        Proposal out = p.asApplied();
        all.set(at, out);
        write(all);
        audit.record(by, "setting-applied", "setting/" + p.key(), p.area() + " | " + p.value());
        return out;
    }

    // ---- application.local.yaml -----------------------------------------------------------------------------------------------

    private void writeLocal(Proposal p, String by) {
        try {
            List<String> lines = Files.isRegularFile(localFile) ? new ArrayList<>(Files.readAllLines(localFile, StandardCharsets.UTF_8)) : new ArrayList<>();
            if (lines.isEmpty()) {
                lines.add("# The site's own settings (read after application.yaml and the engines' files). Lines below a blank line marked");
                lines.add("# 'Admin -> Configuration' were written by approved proposals (four eyes); edit them like any other line.");
            }
            String line = p.key() + ": " + yamlValue(p.value()) + "   # Admin -> Configuration: proposal " + p.id() + " by " + p.proposedBy()
                    + ", approved by " + by + ", " + now();
            int found = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(p.key() + ":")) {
                    found = i;
                }
            }
            if (found >= 0) {
                lines.set(found, line);
            } else {
                lines.add(line);
            }
            Path dir = localFile.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = com.ash.drishti.common.TempFiles.createFile(dir, "application-local-writing-", ".tmp");
            Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            move(tmp, localFile);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the server could not write " + localFile + ": " + e.getMessage(), e);
        }
    }

    /** A value as YAML: a number, true/false or a [list] as it is; anything else a quoted string. */
    static String yamlValue(String v) {
        String s = v.strip();
        if (s.matches("-?\\d+(\\.\\d+)?") || s.equals("true") || s.equals("false") || s.startsWith("[") && s.endsWith("]")) {
            return s;
        }
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the store ----------------------------------------------------------------------------------------------------------------

    private List<Proposal> read() {
        if (!Files.isRegularFile(store)) {
            return List.of();
        }
        try {
            return JSON.readValue(store.toFile(), new TypeReference<List<Proposal>>() { });
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the proposals file " + store + " cannot be read: " + e.getMessage(), e);
        }
    }

    private void write(List<Proposal> all) {
        List<Proposal> kept = all.size() > KEEP ? all.subList(all.size() - KEEP, all.size()) : all;
        try {
            Files.createDirectories(store.toAbsolutePath().getParent());
            Path tmp = com.ash.drishti.common.TempFiles.createFile(store.toAbsolutePath().getParent(), "proposals-writing-", ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), kept);
            move(tmp, store);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the proposals file " + store + " cannot be written: " + e.getMessage(), e);
        }
    }

    private static void move(Path tmp, Path to) throws IOException {
        try {
            Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException x) {
            Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int indexOf(List<Proposal> all, String id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no proposal " + id);
    }

    private String now() {
        return clock.instant().toString();
    }

    private static String newId() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return "SP-" + HexFormat.of().formatHex(b);
    }

    /** The answer of the list, for the page. */
    public static Map<String, Object> answer(List<Proposal> list) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposals", list);
        return out;
    }
}
