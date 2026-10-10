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
package com.ash.drishti.server.connectors;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin &rarr; Connectors: the site's connectors, one file each ({@code config/connectors/<name>.yaml}). Administrators only;
 * a personal API token needs the {@code packs:admin} scope. Every change is audited (setting names, never values). The version
 * of a connector is its {@code ETag}: send it back as {@code If-Match} to change or delete an existing file, so two people
 * editing the same connector cannot overwrite each other.
 *
 * <p>Bodies: {@code PUT /{name}} takes {@code {"text": "<the YAML>"}} or the form's fields ({@code plugin}, {@code enabled},
 * {@code kinds}, {@code description}, {@code settings}).
 */
@RestController
@RequestMapping("/api/v1/admin/connectors")
public class ConnectorController {

    private final ConnectorManager connectors;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public ConnectorController(ConnectorManager connectors, Entitlements entitlements, AuditLog audit) {
        this.connectors = connectors;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** Every connector: origin ({@code file}, {@code pack}, {@code application}), plugin, kinds, state, health and problems. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectors", connectors.list());
        out.put("directory", connectors.files().dir().toString());
        out.put("watch", connectors.watchState());
        out.put("misnamed", connectors.misnamed());
        out.put("fileProblems", connectors.fileProblems());
        out.put("deprecated", connectors.applicationDefined());
        return out;
    }

    /** The plugins and the settings each reads (name, type, required, default, description, secret, group), for the form. */
    @GetMapping("/plugins")
    public Map<String, Object> plugins(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("plugins", connectors.plugins());
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Object> d = connectors.detail(name);
        return withEtag(d);
    }

    /** What a pack suggests for a connector name, to pre-fill New connector. 404 when no pack does. */
    @GetMapping("/{name}/suggestion")
    public Map<String, Object> suggestion(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return connectors.suggestion(name).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no loaded pack suggests a connector '" + name + "'"));
    }

    /** Creates or changes the file. An existing file needs {@code If-Match}. */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> put(@PathVariable String name, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestParam(defaultValue = "false") boolean confirm,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String text = connectors.textOf(name, body, p.user());
        ConnectorManager.Change c = connectors.save(name, text, unquote(ifMatch), confirm);
        audit.record(p.user(), "connector-saved", name, c.changed().isEmpty() ? "no change" : "settings " + c.changed());
        return withEtag(withWarnings(c));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestParam(defaultValue = "false") boolean confirm, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        ConnectorManager.Change c = connectors.delete(name, unquote(ifMatch), confirm);
        audit.record(p.user(), "connector-deleted", name, "file removed (kept in .history)");
        return c.detail();
    }

    /** Puts a pack's default back as the connector's file. */
    @PostMapping("/{name}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String name, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        ConnectorManager.Change c = connectors.reset(name, unquote(ifMatch));
        audit.record(p.user(), "connector-reset", name, "back to the pack's default; settings " + c.changed());
        return withEtag(withWarnings(c));
    }

    /** {@code {"enabled": false}}: switches it on or off, keeping the rest of the file. */
    @PostMapping("/{name}/enabled")
    public ResponseEntity<Map<String, Object>> enabled(@PathVariable String name, @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestParam(defaultValue = "false") boolean confirm,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!(body.get("enabled") instanceof Boolean on)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send {\"enabled\": true} or {\"enabled\": false}");
        }
        ConnectorManager.Change c = connectors.setEnabled(name, on, unquote(ifMatch), confirm);
        audit.record(p.user(), on ? "connector-enabled" : "connector-disabled", name, "");
        return withEtag(withWarnings(c));
    }

    /** Checks a draft without saving: errors and warnings per field. */
    @PostMapping("/{name}/validate")
    public Map<String, Object> validate(@PathVariable String name, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<ConnectorManager.Problem> ps = connectors.validateText(name, connectors.textOf(name, body, p.user()));
        return Map.of("ok", ps.stream().noneMatch(x -> "error".equals(x.level())), "problems", ps);
    }

    /**
     * Tries the connector: a throwaway instance is started on the draft's settings (or the saved ones when no body is sent),
     * asked what it holds, and closed. Changes nothing that runs; failures come back in words, TLS ones included.
     */
    @PostMapping("/{name}/test")
    public Map<String, Object> test(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String text;
        if (body == null || body.isEmpty()) {
            text = connectors.files().text(name);
            if (text == null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "send the draft to test: connector '" + name + "' has no file");
            }
        } else {
            text = connectors.textOf(name, body, p.user());
        }
        Map<String, Object> out = connectors.test(name, text);
        audit.record(p.user(), "connector-tested", name, Boolean.TRUE.equals(out.get("ok")) ? "reachable" : "problem");
        return out;
    }

    @GetMapping("/{name}/history/{id}")
    public Map<String, Object> revision(@PathVariable String name, @PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return Map.of("name", name, "id", id, "text", connectors.revisionText(name, id));
    }

    @PostMapping("/{name}/restore/{id}")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String name, @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestParam(defaultValue = "false") boolean confirm,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        ConnectorManager.Change c = connectors.restore(name, id, unquote(ifMatch), confirm);
        audit.record(p.user(), "connector-restored", name, "version " + id + "; settings " + c.changed());
        return withEtag(withWarnings(c));
    }

    private static Map<String, Object> withWarnings(ConnectorManager.Change c) {
        Map<String, Object> out = new LinkedHashMap<>(c.detail());
        out.put("changed", c.changed());
        out.put("warnings", c.warnings());
        return out;
    }

    private static ResponseEntity<Map<String, Object>> withEtag(Map<String, Object> body) {
        Object etag = body.get("etag");
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (etag != null) {
            b.eTag("\"" + etag + "\"");
        }
        return b.body(body);
    }

    private static String unquote(String etag) {
        if (etag == null) {
            return null;
        }
        String t = etag.trim();
        if (t.startsWith("W/")) {
            t = t.substring(2);
        }
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }
}
