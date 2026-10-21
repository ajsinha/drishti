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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.rachana.ui.migrate.PackDryRun;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The migration of pages to boards for administrators (docs/architecture/UI_BOARD.md 11.2 and 11.3, phase P5): the dry run and the proof, each
 * kept as a report in the content store ({@code ui/migration/<pack>/<run>.json}, {@code <run>.proof.json}) for the Admin pages, and the per-pack
 * switch.
 */
@RestController
@RequestMapping("/api/v1/ui")
public class UiMigrationController {

    /** A dry run's request. */
    public record DryRun(String pack) {}

    /** A test person of the proof: their name and roles (as a token would carry them). */
    public record Person(String user, List<String> roles) {}

    /** A proof's request. */
    public record Proof(String pack, List<Person> users, Integer samples, Map<String, List<String>> ids) {}

    /** A switch. */
    public record Switch(String screens) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final PackScreens screens;
    private final BoardProof proof;
    private final ContentStore store;
    private final Entitlements entitlements;

    public UiMigrationController(PackScreens screens, BoardProof proof, ContentStore store, Entitlements entitlements) {
        this.screens = screens;
        this.proof = proof;
        this.store = store;
        this.entitlements = entitlements;
    }

    private static String pack(String pack) {
        if (pack == null || !pack.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give the pack by name");
        }
        return pack;
    }

    private String keep(String pack, String suffix, ObjectNode report) {
        String run = ZonedDateTime.now(ZoneOffset.UTC).format(RUN);
        report.put("run", run);
        try {
            store.putLatest("ui/migration/" + pack + "/" + run + suffix, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return run;
    }

    /** {@code POST /ui/migrate/dry-run {pack}}: every screen of the pack converted in memory; nothing changes for anyone. */
    @PostMapping("/migrate/dry-run")
    public JsonNode dryRun(@RequestBody DryRun body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String pack = pack(body == null ? null : body.pack());
        ObjectNode report = PackDryRun.report(pack, screens.dryRun(pack));
        keep(pack, ".json", report);
        return report;
    }

    /** {@code POST /ui/migrate/proof {pack, users, samples, ids}}: the old pages and the boards compared for each person. */
    @PostMapping("/migrate/proof")
    public JsonNode proof(@RequestBody Proof body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String pack = pack(body == null ? null : body.pack());
        if (body.users() == null || body.users().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the test people (at least one per access profile: full, masked, row-restricted)");
        }
        List<Principal> users = body.users().stream().map(u -> new Principal(u.user(), u.roles() == null ? List.of() : u.roles())).toList();
        ObjectNode report = proof.prove(pack, users, body.samples() == null || body.samples() < 1 ? 20 : body.samples(), body.ids(), null);
        keep(pack, ".proof.json", report);
        return report;
    }

    /** {@code GET /ui/migrate/{pack}}: the pack's kept reports, newest last. */
    @GetMapping("/migrate/{pack}")
    public List<String> reports(@PathVariable String pack, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String prefix = "ui/migration/" + pack(pack) + "/";
        return store.list(prefix).stream().map(k -> k.substring(prefix.length())).sorted().toList();
    }

    /** {@code GET /ui/migrate/{pack}/{report}}: one kept report ({@code <run>.json} or {@code <run>.proof.json}). */
    @GetMapping("/migrate/{pack}/{report:.+}")
    public JsonNode report(@PathVariable String pack, @PathVariable String report, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        if (!report.matches("[0-9-]+(\\.proof)?\\.json")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no such report");
        }
        ContentStore.Item it = store.get("ui/migration/" + pack(pack) + "/" + report)
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no report " + report));
        return JSON.readTree(it.text().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code GET /ui/packs}: each pack's screens ({@code boards} or {@code sutras}); a pack not listed is on {@code sutras}. */
    @GetMapping("/packs")
    public Map<String, String> packs(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return screens.modes();
    }

    /** {@code PUT /ui/packs/{pack} {screens: boards|sutras}}: the switch, at once (this node, until the next start; ui.yaml for good). */
    @PutMapping("/packs/{pack}")
    public Map<String, String> setPack(@PathVariable String pack, @RequestBody Switch body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        screens.set(pack(pack), PackScreens.Mode.of(body == null ? null : body.screens()));
        return screens.modes();
    }
}
