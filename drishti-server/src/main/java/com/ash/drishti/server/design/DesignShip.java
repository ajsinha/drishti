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
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.api.DesignController;
import com.ash.drishti.server.governance.Proposal;
import com.ash.drishti.server.governance.SutraGovernance;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The shipping side of a Design: propose it for review with its evidence (the check matrix, the sample names, the notes and the
 * design's identity travel with the proposal; approval makes the Design {@code live(vN)}), bind it to a file under a Sutra
 * directory on a development server (saving writes the file, an edit made in an IDE is read back), and share it read-only
 * (Sutra, operations and sample names, never sample contents). Thread-safe: it keeps no state of its own.
 */
@Service
public class DesignShip {

    /** The status a Design carries while its proposal waits, and after approval. */
    public static final String PROPOSED = "proposed(";
    public static final String LIVE = "live(v";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DesignService designs;
    private final DesignController designApi;
    private final SutraGovernance governance;
    private final SutraRegistry sutras;
    private final RachanaProperties props;
    private final Entitlements entitlements;
    private final DesignRebase rebase;

    public DesignShip(DesignService designs, DesignController designApi, SutraGovernance governance, SutraRegistry sutras, RachanaProperties props,
            Entitlements entitlements, DesignRebase rebase) {
        this.rebase = rebase;
        this.designs = designs;
        this.designApi = designApi;
        this.governance = governance;
        this.sutras = sutras;
        this.props = props;
        this.entitlements = entitlements;
    }

    @PostConstruct
    void listen() {
        governance.addListener(this::proposalChanged);
    }

    // ---- propose with evidence ---------------------------------------------------------------------------------------

    /** What {@link #propose} did: the proposal (governance on) or the saved Sutra (governance off, the Design is live at once). */
    public record Proposed(Proposal proposal, Sutra saved) {}

    public Proposed propose(Principal who, String id, String note) throws IOException {
        if (!props.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving from the workbench is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(who)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, who.user() + " is not a Sutra author");
        }
        StoredDesign d = numbered(who, designs.get(who.user(), id));
        JsonNode matrix = null;
        if (!d.samples.isEmpty() && d.sutra != null && !d.sutra.isBlank()) {
            matrix = designApi.check(id, who);
            d = designs.get(who.user(), id);
        }
        if (governance.enabled()) {
            Proposal pr = governance.propose(d.sutra, note, who, evidence(d, matrix));
            designs.markStatus(who.user(), id, PROPOSED + pr.id() + ")", d.rev);
            return new Proposed(pr, null);
        }
        Sutra s = sutras.save(d.sutra);
        designs.markStatus(who.user(), id, LIVE + s.version() + ")", d.rev);
        return new Proposed(null, s);
    }

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^version:[ \\t]*\\d+");

    /**
     * A Design never rewrites a version that exists: when its Sutra's {@code name@version} is already defined, a Design made from
     * {@code name@v} is published as {@code name@(latest+1)} (the {@code version:} line is renumbered, as a step the Design can undo),
     * and a Design that is not based on that Sutra is refused here, at propose time, with DRS-2028 (saying when a pack owns it).
     */
    StoredDesign numbered(Principal who, StoredDesign d) {
        if (d.sutra == null || d.sutra.isBlank()) {
            return d;
        }
        Sutra s = sutras.check(d.sutra);
        Optional<Sutra> latest = sutras.latest(s.name());
        Optional<String> live = sutras.source(s.name(), s.version());
        if (latest.isEmpty() || live.isEmpty() || live.get().equals(d.sutra)) {
            return d;
        }
        if (d.base == null || !d.base.startsWith(s.name() + "@")) {
            String owner = packOwned(s) ? "a pack owns it" : "it is already live";
            throw new SutraException(List.of(new SutraProblem("DRS-2028", s.id() + " exists already (" + owner + "): this design is not based on it, so it"
                    + " cannot publish over it. Start the design from " + s.name() + "@" + latest.get().version()
                    + " (Open a Sutra), or give it a new name or a higher version", s.location())));
        }
        int next = latest.get().version() + 1;
        Matcher m = VERSION_LINE.matcher(d.sutra);
        if (!m.find()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot renumber the Sutra to version " + next + ": no 'version:' line at the top level");
        }
        String renumbered = d.sutra.substring(0, m.start()) + "version: " + next + d.sutra.substring(m.end());
        return designs.update(who.user(), d.id, new DesignService.Patch(null, null, null, renumbered, null));
    }

    private boolean packOwned(Sutra s) {
        return sutras.fileOf(s.id()).map(f -> props.dirs().isEmpty()
                || !f.toAbsolutePath().normalize().startsWith(Path.of(props.dirs().get(0)).toAbsolutePath().normalize())).orElse(false);
    }

    /** The evidence that goes to the reviewers; sample contents are never part of it. */
    ObjectNode evidence(StoredDesign d, JsonNode matrix) {
        ObjectNode e = JSON.createObjectNode();
        e.put("designId", d.id).put("designName", d.name).put("owner", d.owner).put("rev", d.rev).put("kind", d.kind);
        if (d.base != null) {
            e.put("base", d.base);
        }
        e.put("notes", d.notes == null ? "" : d.notes);
        ObjectNode moved = rebase.moved(d);
        if (moved != null) {
            e.set("baseMoved", moved);
        }
        ArrayNode names = e.putArray("sampleNames");
        ArrayNode refs = e.putArray("refSamples");
        ArrayNode synthetic = e.putArray("syntheticSamples");
        for (SampleInfo s : d.samples) {
            names.add(s.name());
            if (StoredDesign.REF.equals(s.type())) {
                refs.addObject().put("name", s.name()).put("kind", s.refKind()).put("id", s.refId());
            }
            if (StoredDesign.SYNTHETIC.equals(s.type())) {
                synthetic.add(s.name());
            }
        }
        if (matrix == null) {
            e.putNull("matrix");
        } else {
            e.set("matrix", matrix);
        }
        return e;
    }

    private void proposalChanged(Proposal p) {
        JsonNode ev = p.evidence();
        if (ev == null || !ev.path("designId").isTextual() || !ev.path("owner").isTextual() || p.pending()) {
            return;
        }
        String status = Proposal.APPROVED.equals(p.status()) ? LIVE + p.version() + ")" : "draft";
        designs.markStatus(ev.get("owner").asText(), ev.get("designId").asText(), status, ev.path("rev").canConvertToInt() ? ev.get("rev").asInt() : null);
    }

    // ---- read-only sharing -------------------------------------------------------------------------------------------

    /** A new share token (the old one stops working); only its hash is kept. */
    public String share(Principal who, String id) {
        designs.get(who.user(), id);
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String token = b64.encodeToString(who.user().getBytes(StandardCharsets.UTF_8)) + "." + b64.encodeToString(secret);
        designs.setShare(who.user(), id, hash(token));
        return token;
    }

    public void unshare(Principal who, String id) {
        designs.setShare(who.user(), id, null);
    }

    /** What a token holder sees: the Sutra, the operations and the sample names; never sample contents, notes or tests. */
    public ObjectNode shared(String id, String token, Principal viewer) {
        StoredDesign d = byToken(id, token);
        ObjectNode o = JSON.createObjectNode();
        o.put("id", d.id).put("name", d.name).put("kind", d.kind).put("status", d.status).put("sutra", d.sutra).put("rev", d.rev).put("owner", d.owner);
        if (d.base != null) {
            o.put("base", d.base);
        }
        ArrayNode samples = o.putArray("sampleNames");
        d.samples.forEach(s -> samples.add(sampleLabel(s, viewer)));
        ArrayNode log = o.putArray("ops");
        d.ops.forEach(e -> log.addObject().put("at", e.path("at").asLong()).set("ops", e.path("ops")));
        return o;
    }

    /** A sample's name for {@code viewer}: a reference carries an entity id, so its name shows only to someone who may open that kind. */
    String sampleLabel(SampleInfo s, Principal viewer) {
        return StoredDesign.REF.equals(s.type()) && !entitlements.mayOpen(viewer, s.refKind()) ? s.refKind() + " (reference)" : s.name();
    }

    private StoredDesign byToken(String id, String token) {
        DrishtiException gone = new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "this link is not valid (it may have been revoked)");
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0 || id == null) {
            throw gone;
        }
        try {
            String owner = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            StoredDesign d = designs.get(owner, id);
            if (d.shareHash == null || !MessageDigest.isEqual(d.shareHash.getBytes(StandardCharsets.UTF_8), hash(token).getBytes(StandardCharsets.UTF_8))) {
                throw gone;
            }
            return d;
        } catch (IllegalArgumentException | DrishtiException e) {
            throw gone;
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
