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
package com.ash.drishti.server.dataload;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.dataload.DataInbox;
import com.ash.drishti.dataload.ReadyDesk;
import com.ash.drishti.dataload.ReadyForm;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.engines.EngineGateFilter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, Data loads, Upload and {@code drishti.py dataload ready} (DATA_LAKE_GUIDE.md section 3.5): the file taken in, its {@code .ready} previewed
 * and checked, then pushed. Administrators; a token needs {@code loads:write} for the {@code POST}s and the {@code DELETE}. A push is audited: who,
 * the file, its SHA-256, the {@code .ready} text. In a cluster every call is answered by the node that runs the inbox; the upload, which
 * can be larger than one forwarded call, goes there in signed parts.
 */
@RestController
public class ReadyController {

    /** The upload's path, passed on by this controller rather than whole by the engine gate. */
    public static final String UPLOAD = "/api/v1/admin/dataload/upload";
    private static final int PART = 8 << 20;

    private final ReadyDesk desk;
    private final DataInbox inbox;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final EngineGateFilter.Peers peers;

    ReadyController(ReadyDesk desk, DataInbox inbox, Entitlements entitlements, AuditLog audit, EngineGateFilter.Peers peers) {
        this.desk = desk;
        this.inbox = inbox;
        this.entitlements = entitlements;
        this.audit = audit;
        this.peers = peers == null ? EngineGateFilter.Peers.NONE : peers;
    }

    /** The pickers: lakes, tables with their kind and pack, files in the inbox folder with no marker, the facts above the form. */
    @GetMapping("/api/v1/admin/dataload/choices")
    public Map<String, Object> choices(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return desk.choices();
    }

    /** The file as the raw body, {@code ?file=<name>}; streamed to the staging folder, its SHA-256 computed on the way, its rows counted after. */
    @PostMapping(UPLOAD)
    public ResponseEntity<?> upload(@RequestParam("file") String file, HttpServletRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        if (inbox.started() || peers.fromPeer(req)) {
            return ResponseEntity.ok(desk.receive(req.getInputStream(), file, req.getContentLengthLong(), p.user()));
        }
        Optional<String> node = peers.runs("dataload");
        if (node.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENGINE_OFF, "the data inbox is off on every server: turn it on in Admin, Engines (dataload)");
        }
        return relay(node.get(), file, req.getInputStream(), p);
    }

    /** One part of an upload another node relays: {@code ?file=}, {@code ?id=} after the first, {@code ?last=true} on the end. */
    @PostMapping(UPLOAD + "/part")
    public Map<String, Object> part(@RequestParam("file") String file, @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "last", defaultValue = "false") boolean last, HttpServletRequest req, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        return desk.part(id, file, req.getInputStream(), last, p.user());
    }

    /** The stream cut into parts that each fit one signed peer call; at most one part in memory. */
    private ResponseEntity<?> relay(String node, String file, InputStream in, Principal p) throws IOException {
        String id = null;
        byte[] next = in.readNBytes(PART);
        while (true) {
            byte[] after = next.length == PART ? in.readNBytes(PART) : new byte[0];
            boolean last = after.length == 0;
            String q = UPLOAD + "/part?file=" + URLEncoder.encode(file, StandardCharsets.UTF_8) + "&last=" + last
                    + (id == null ? "" : "&id=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
            PeerHttp.Answer a;
            try {
                a = peers.call(node, "POST", q, next, p);
            } catch (IOException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "node " + node + ", which runs the data inbox, is not reachable: try again shortly");
            }
            if (a.status() >= 300 || last) {
                return ResponseEntity.status(a.status()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(a.body());
            }
            Map<?, ?> st = new com.fasterxml.jackson.databind.ObjectMapper().readValue(new ByteArrayInputStream(a.body()), Map.class);
            id = String.valueOf(st.get("id"));
            next = after;
        }
    }

    /** An upload dropped. */
    @DeleteMapping(UPLOAD + "/{id}")
    public Map<String, Object> discard(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        desk.discard(id, p.user());
        return Map.of("discarded", true);
    }

    /** A file already in the inbox folder, by name: {@code {"file": "<name>"}}; nothing is copied. */
    @PostMapping("/api/v1/admin/dataload/pick")
    public Map<String, Object> pick(@RequestBody Map<String, String> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return desk.pick(body == null ? null : body.get("file"), p.user());
    }

    /** A file's state: its SHA-256 and rows when computed, with progress until then. */
    @GetMapping("/api/v1/admin/dataload/item/{id}")
    public Map<String, Object> item(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return desk.state(desk.item(id, p.user()));
    }

    /** From the form's fields: the exact {@code .ready} text and the field errors. */
    @PostMapping("/api/v1/admin/dataload/ready/preview")
    public Map<String, Object> preview(@RequestBody ReadyForm form, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        var b = desk.preview(form, p.user());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", b.text());
        out.put("errors", b.errors());
        out.put("derived", b.derived());
        return out;
    }

    /** The checks: each a pass, a warning or a refusal in words, with the fix. */
    @PostMapping("/api/v1/admin/dataload/ready/check")
    public Map<String, Object> check(@RequestBody ReadyForm form, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return desk.check(form, p.user());
    }

    /** A {@code .ready} someone already has, as the form's fields ({@code {"text": "..."}}); a key the inbox does not know is said as {@code refused}. */
    @PostMapping("/api/v1/admin/dataload/ready/read")
    public Map<String, Object> read(@RequestBody Map<String, String> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return desk.read(body == null ? "" : body.get("text"));
    }

    /** Push: the checks again, the data file first and its {@code .ready} last; refused ({@code DRS-5008}) with the reason. Audited. */
    @PostMapping("/api/v1/admin/dataload/ready/push")
    public ReadyDesk.Pushed push(@RequestBody ReadyForm form, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        entitlements.requireAdmin(p);
        try {
            ReadyDesk.Pushed d = desk.push(form, p.user());
            record(p, "dataload.push", "inbox/" + d.file(), "sha256 " + d.sha256() + ", " + d.rows() + " rows\n" + d.text());
            return d;
        } catch (DrishtiException e) {
            record(p, "dataload.push-refused", String.valueOf(form == null ? null : form.item()), e.getMessage());
            throw e;
        }
    }

    private void record(Principal p, String action, String subject, String detail) {
        if (audit != null) {
            try {
                audit.record(p.user(), action, subject, detail);
            } catch (RuntimeException e) {
                // the push is done either way
            }
        }
    }
}
