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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean fileBinding;

    public DesignShip(DesignService designs, DesignController designApi, SutraGovernance governance, SutraRegistry sutras, RachanaProperties props,
            Entitlements entitlements, @Value("${drishti.builder.file-binding:false}") boolean fileBinding) {
        this.designs = designs;
        this.designApi = designApi;
        this.governance = governance;
        this.sutras = sutras;
        this.props = props;
        this.entitlements = entitlements;
        this.fileBinding = fileBinding;
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
        StoredDesign d = designs.get(who.user(), id);
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

    /** The evidence that goes to the reviewers; sample contents are never part of it. */
    ObjectNode evidence(StoredDesign d, JsonNode matrix) {
        ObjectNode e = JSON.createObjectNode();
        e.put("designId", d.id).put("designName", d.name).put("owner", d.owner).put("rev", d.rev).put("kind", d.kind);
        if (d.base != null) {
            e.put("base", d.base);
        }
        e.put("notes", d.notes == null ? "" : d.notes);
        ArrayNode names = e.putArray("sampleNames");
        ArrayNode synthetic = e.putArray("syntheticSamples");
        for (SampleInfo s : d.samples) {
            names.add(s.name());
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
    public ObjectNode shared(String id, String token) {
        StoredDesign d = byToken(id, token);
        ObjectNode o = JSON.createObjectNode();
        o.put("id", d.id).put("name", d.name).put("kind", d.kind).put("status", d.status).put("sutra", d.sutra).put("rev", d.rev).put("owner", d.owner);
        if (d.base != null) {
            o.put("base", d.base);
        }
        ArrayNode samples = o.putArray("sampleNames");
        d.samples.forEach(s -> samples.add(s.name()));
        ArrayNode log = o.putArray("ops");
        d.ops.forEach(e -> log.addObject().put("at", e.path("at").asLong()).set("ops", e.path("ops")));
        return o;
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

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- development file binding ------------------------------------------------------------------------------------

    public boolean fileBinding() {
        return fileBinding;
    }

    private void requireBinding(Principal who) {
        if (!fileBinding) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "binding a design to a file is off on this server (drishti.builder.file-binding)");
        }
        if (!props.studioSave() || !entitlements.mayAuthor(who)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "binding to a file needs the author right and drishti.rachana.studio-save");
        }
    }

    /** The file {@code rel} names under a Sutra directory (an existing one wins; else the first directory), never outside it. */
    Path resolve(String rel) {
        if (rel == null || rel.isBlank() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\u0000")
                || !(rel.endsWith(".yaml") || rel.endsWith(".yml"))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' is a path under a Sutra directory, ending in .yaml");
        }
        Path first = null;
        Path firstRoot = null;
        for (String dir : props.dirs()) {
            Path root = Path.of(dir).toAbsolutePath().normalize();
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' must stay inside the Sutra directory");
            }
            if (Files.isRegularFile(target)) {
                return inside(root, target);
            }
            if (first == null) {
                first = target;
                firstRoot = root;
            }
        }
        if (first == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no Sutra directory is configured (drishti.rachana.dirs)");
        }
        return inside(firstRoot, first);
    }

    private static Path inside(Path root, Path target) {
        try {
            Path parent = target.getParent();
            Files.createDirectories(root);
            Path realRoot = root.toRealPath();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            if (parent == null || !parent.toRealPath().startsWith(realRoot)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' must stay inside the Sutra directory");
            }
            return target;
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' is not readable: " + e.getMessage());
        }
    }

    /** Binds the Design to {@code rel}; an existing file's text becomes the Sutra (undo brings the old one back). */
    public StoredDesign bind(Principal who, String id, String rel) throws IOException {
        requireBinding(who);
        designs.get(who.user(), id);
        Path file = resolve(rel);
        if (Files.isRegularFile(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            designs.setBinding(who.user(), id, rel, hash(text));
            return designs.adoptFileText(who.user(), id, text, hash(text));
        }
        return designs.setBinding(who.user(), id, rel, null);
    }

    public StoredDesign unbind(Principal who, String id) {
        return designs.setBinding(who.user(), id, null, null);
    }

    /** Writes the Sutra to the bound file; refused when the file was edited elsewhere since the Design last read it (409). */
    public StoredDesign saveFile(Principal who, String id) throws IOException {
        requireBinding(who);
        StoredDesign d = designs.get(who.user(), id);
        if (d.boundFile == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design is not bound to a file");
        }
        Path file = resolve(d.boundFile);
        if (Files.isRegularFile(file)) {
            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            if (!onDisk.equals(d.sutra) && !hash(onDisk).equals(d.boundSync)) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, d.boundFile + " changed on disk after the design last read it: load it first (or unbind)");
            }
        }
        sutras.check(d.sutra);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, d.sutra, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        if ("OFF".equals(sutras.hotReload())) {
            sutras.reload();
        }
        return designs.setBinding(who.user(), id, d.boundFile, hash(d.sutra));
    }

    /** Brings an edit made to the bound file (in an IDE) back into the Design; the result says whether it changed. */
    public record Synced(StoredDesign design, boolean changed, boolean missing) {}

    public Synced sync(Principal who, String id) throws IOException {
        StoredDesign d = designs.get(who.user(), id);
        if (d.boundFile == null || !fileBinding) {
            return new Synced(d, false, false);
        }
        Path file = resolve(d.boundFile);
        if (!Files.isRegularFile(file)) {
            return new Synced(d, false, true);
        }
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        String h = hash(onDisk);
        if (h.equals(d.boundSync) || onDisk.equals(d.sutra)) {
            if (!h.equals(d.boundSync)) {
                d = designs.setBinding(who.user(), id, d.boundFile, h);
            }
            return new Synced(d, false, false);
        }
        return new Synced(designs.adoptFileText(who.user(), id, onDisk, h), true, false);
    }

    /** The directories a design may be bound under, as configured (for the workbench's hint). */
    public List<String> bindableDirs() {
        return props.dirs();
    }
}
