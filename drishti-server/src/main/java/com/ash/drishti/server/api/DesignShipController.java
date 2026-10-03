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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.server.design.DesignBinding;
import com.ash.drishti.server.design.DesignRebase;
import com.ash.drishti.server.design.DesignShip;
import com.ash.drishti.server.design.PackFragment;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shipping a Design, {@code /api/v1/builder/designs}: propose it with evidence ({@code POST /{id}/propose}), export it as a pack
 * fragment and import a zip of one ({@code GET /{id}/export}, {@code POST /import}), bind it to a file on a development
 * server ({@code POST|DELETE /{id}/bind}, {@code POST /{id}/save-file}, {@code GET /{id}/sync}) and share it read-only
 * ({@code POST|DELETE /{id}/share}, {@code GET /shared/{id}?token=}). The rules are in {@link DesignShip} and {@link PackFragment}.
 */
@RestController
@RequestMapping("/api/v1/builder/designs")
public class DesignShipController {

    private final DesignService designs;
    private final DesignController api;
    private final DesignShip ship;
    private final DesignBinding binding;
    private final DesignRebase rebase;
    private final PackFragment fragment;
    private final ShapeService shapes;
    private static final int OPTIONAL_MAX = 1 << 20;

    private final ObjectMapper mapper = new ObjectMapper();

    public DesignShipController(DesignService designs, DesignController api, DesignShip ship, DesignBinding binding, DesignRebase rebase, PackFragment fragment,
            ShapeService shapes) {
        this.binding = binding;
        this.rebase = rebase;
        this.designs = designs;
        this.api = api;
        this.ship = ship;
        this.fragment = fragment;
        this.shapes = shapes;
    }

    /** Body {@code {note?}}. 202 with the proposal (governance on), or 200 with the saved Sutra (governance off). */
    @PostMapping("/{id}/propose")
    public ResponseEntity<ObjectNode> propose(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who)
            throws IOException {
        JsonNode body = optional(request);
        DesignShip.Proposed p = ship.propose(who, id, body.path("note").asText(""));
        ObjectNode out = mapper.createObjectNode();
        if (p.proposal() != null) {
            out.putObject("proposal").put("id", p.proposal().id()).put("name", p.proposal().name()).put("version", p.proposal().version())
                    .put("status", p.proposal().status());
            StoredDesign now = designs.get(who.user(), id);
            out.put("status", now.status);
            moved(out, now);
            return ResponseEntity.accepted().body(out);
        }
        StoredDesign now = designs.get(who.user(), id);
        out.put("name", p.saved().name()).put("version", p.saved().version()).put("status", now.status);
        moved(out, now);
        return ResponseEntity.ok(out);
    }

    private void moved(ObjectNode out, StoredDesign d) {
        ObjectNode m = rebase.moved(d);
        if (m != null) {
            out.set("baseMoved", m);
        }
    }

    /**
     * Body {@code {baseRev}}: the Design's base Sutra has a newer version; its operations are replayed on that version. Answers the
     * design plus {@code problems} (steps that could not be replayed, each saying which and why) and {@code replayed}.
     */
    @PostMapping("/{id}/rebase")
    public ObjectNode rebase(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode body = optional(request);
        if (!body.path("baseRev").isIntegralNumber()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'baseRev' is required: the revision you built on (the 'rev' of the design)");
        }
        DesignRebase.Rebased r = rebase.rebase(who.user(), id, body.get("baseRev").asInt());
        ObjectNode out = api.view(designs.summary(r.design()), true);
        out.set("problems", mapper.valueToTree(r.problems()));
        out.put("replayed", r.replayed());
        return out;
    }

    // ---- pack fragments --------------------------------------------------------------------------------------------

    @GetMapping(path = "/{id}/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        StoredDesign d = designs.get(who.user(), id);
        Map<String, JsonNode> docs = new LinkedHashMap<>();
        for (StoredDesign.SampleInfo s : PackFragment.exportable(d)) {
            docs.put(s.name(), mapper.readTree(designs.sample(who.user(), id, s.name())));
        }
        JsonNode matrix = null;
        if (!docs.isEmpty() && d.sutra != null && !d.sutra.isBlank()) {
            try {
                matrix = api.check(id, who);
            } catch (DrishtiException e) {
                matrix = null;      // the fragment is still exported; its expect.yaml then asks only for no errors
            }
        }
        byte[] zip = fragment.export(d, docs, matrix);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + PackFragment.packName(d) + "-fragment.zip\"")
                .contentType(MediaType.parseMediaType("application/zip")).body(zip);
    }

    /** The body is a zip of a pack folder (or of a folder with Sutras, tests and samples). Answers the Designs made. */
    @PostMapping(path = "/import", consumes = {"application/zip", "application/octet-stream"})
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public ObjectNode importZip(HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        long max = shapes.limits().maxTotalBytes();
        if (request.getContentLengthLong() > max) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the zip is over " + shapes.limits().maxTotalMb() + " MiB (drishti.builder.max-total-mb)");
        }
        byte[] zip;
        try (InputStream in = request.getInputStream()) {
            zip = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        }
        if (zip.length > max) {
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the zip is over " + shapes.limits().maxTotalMb() + " MiB (drishti.builder.max-total-mb)");
        }
        PackFragment.Imported r = fragment.importZip(who.user(), zip, max);
        return PackFragment.toJson(r, d -> api.view(designs.summary(d), false));
    }

    // ---- sharing ---------------------------------------------------------------------------------------------------

    /** Makes (or renews) the read-only link. The token is shown once; revoke with DELETE. */
    @PostMapping("/{id}/share")
    public ObjectNode share(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        String token = ship.share(who, id);
        ObjectNode out = mapper.createObjectNode();
        out.put("token", token).put("path", "/build/d/" + id + "?share=" + token);
        return out;
    }

    @DeleteMapping("/{id}/share")
    public ObjectNode unshare(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        ship.unshare(who, id);
        return mapper.createObjectNode().put("shared", false);
    }

    /** What a link holder may see: Sutra, operations, sample names. Any signed-in user with the token; a bad or revoked one is 404. */
    @GetMapping("/shared/{id}")
    public ObjectNode shared(@PathVariable String id, @RequestParam String token, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return ship.shared(id, token, who);
    }

    // ---- file binding ----------------------------------------------------------------------------------------------

    /** Whether binding is on and where (a name under the server, never an absolute path; administrators also get the absolute directory). */
    @GetMapping("/binding")
    public ObjectNode binding(@RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        ObjectNode out = mapper.createObjectNode().put("enabled", binding.enabled());
        out.put("dir", binding.label(who));
        if (binding.enabled() && binding.isAdmin(who)) {
            out.put("absoluteDir", binding.absolute(who));
        }
        return out;
    }

    /** Body {@code {file}}: a path in your development directory. An existing file's text becomes the Sutra. */
    @PostMapping("/{id}/bind")
    public ObjectNode bind(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode body = optional(request);
        StoredDesign d = binding.bind(who, id, body.path("file").asText(null));
        return api.view(designs.summary(d), true);
    }

    @DeleteMapping("/{id}/bind")
    public ObjectNode unbind(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return api.view(designs.summary(binding.unbind(who, id)), true);
    }

    /** Writes the Sutra to the bound file (in your development directory; this never makes it live). 409 when the file was edited elsewhere first. */
    @PostMapping("/{id}/save-file")
    public ObjectNode saveFile(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return api.view(designs.summary(binding.saveFile(who, id)), false);
    }

    /** Reads the bound file: an edit made in an IDE becomes a step of the Design. {@code changed} says so. A POST: it changes the Design. */
    @PostMapping("/{id}/sync")
    public ObjectNode sync(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        DesignBinding.Synced s = binding.sync(who, id);
        ObjectNode out = api.view(designs.summary(s.design()), s.changed());
        out.put("changed", s.changed()).put("missing", s.missing());
        return out;
    }

    private JsonNode optional(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() == 0) {
            return mapper.createObjectNode();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(OPTIONAL_MAX + 1);
            if (bytes.length > OPTIONAL_MAX) {
                throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the request body is over " + OPTIONAL_MAX / 1024 + " KB");
            }
            if (bytes.length == 0) {
                return mapper.createObjectNode();
            }
            JsonNode n = mapper.readTree(bytes);
            return n != null && n.isObject() ? n : mapper.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
