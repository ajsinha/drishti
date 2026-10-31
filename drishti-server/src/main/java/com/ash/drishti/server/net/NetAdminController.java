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
package com.ash.drishti.server.net;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.net.config.NetFolder;
import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Admin → Net, Members and Files (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, sections 13 and 14): add a seed, remove a dead member, drain, leave, rejoin;
 * read, check, write and delete the files of {@code config/net/} with the same version check ({@code If-Match}), history and
 * audit as connector files. Administrators only; never a personal API token. Every action is audited with the node it was
 * made on. A key is never shown or set here: files name it as an environment reference.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetAdminController {

    private final NetService net;
    private final Entitlements entitlements;
    private final AuditLog audit;

    public NetAdminController(NetService net, Entitlements entitlements, AuditLog audit) {
        this.net = net;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    private void record(Principal p, String action, String subject, String detail) {
        audit.record(p.user(), action, subject, detail + " (on node " + (net.nodeId().isEmpty() ? "with the cluster off" : net.nodeId()) + ")");
    }

    // -- members ------------------------------------------------------------------------------------------------------

    /** Body {@code {url, name?}}: saved as {@code peers/<name>.yaml} and tried at once. */
    @PostMapping("/seeds")
    public Map<String, Object> addSeed(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String url = body.path("url").asText("").trim().replaceAll("/+$", "");
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "'" + url + "' is not host:port or an http(s) address");
        }
        if (u.getHost() == null || u.getPort() < 0 && !url.startsWith("https://")) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "a seed is host:port or an http(s) address with a port: " + url);
        }
        String name = body.path("name").asText("").isBlank()
                ? (u.getHost() + (u.getPort() < 0 ? "" : "-" + u.getPort())).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("^-+", "")
                : body.path("name").asText().trim();
        String id = "peers/" + name + ".yaml";
        if (!NetFolder.valid(id)) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "'" + name + "' is not a peer name: lower-case letters, digits and dashes");
        }
        String text = net.folder().render(id, Map.of("url", url));
        List<NetSchema.Problem> problems = net.folder().check(id, text);
        if (problems.stream().anyMatch(NetSchema.Problem::error)) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, problems.get(0).toString());
        }
        net.folder().write(id, text, null);
        net.reload();
        boolean answered = net.running() && net.contact(url);
        record(p, "net-seed-added", name, url + (answered ? ", answered" : ", did not answer yet"));
        return Map.of("file", id, "url", url, "answered", answered);
    }

    @DeleteMapping("/members/{id}")
    public Map<String, Object> remove(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        net.remove(id);
        record(p, "net-member-removed", id, "taken out of the member table");
        return Map.of("removed", id);
    }

    @PostMapping("/drain")
    public Map<String, Object> drain(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        net.drain(true);
        record(p, "net-drain", net.nodeId(), "takes no forwarded work until undrained");
        return Map.of("drained", true);
    }

    @PostMapping("/undrain")
    public Map<String, Object> undrain(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        net.drain(false);
        record(p, "net-undrain", net.nodeId(), "takes forwarded work again");
        return Map.of("drained", false);
    }

    @PostMapping("/leave")
    public Map<String, Object> leave(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        net.leave();
        record(p, "net-leave", net.nodeId(), "gossiped left; serves peers nothing until rejoin");
        return Map.of("left", true);
    }

    @PostMapping("/rejoin")
    public Map<String, Object> rejoin(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        net.rejoin();
        record(p, "net-rejoin", net.nodeId(), "alive again with a higher incarnation");
        return Map.of("left", false);
    }

    /**
     * A console that moved from one server of the cluster to another tells the one it moved to (body {@code {from, to, why, console}}), so
     * each failover is an audit row as well as a line in the console's log. The console's service identity or an administrator.
     */
    @PostMapping("/console-event")
    public Map<String, Object> consoleEvent(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!p.roles().contains(Entitlements.SERVICE)) {
            entitlements.requireAdmin(p);
        }
        String from = body.path("from").asText("");
        String to = body.path("to").asText("");
        audit.record("console", "net-console-failover", to, "console " + body.path("console").asText("?") + " moved from " + from + " to " + to
                + (body.path("why").asText("").isBlank() ? "" : ": " + body.path("why").asText()) + " (recorded on node " + net.nodeId() + ")");
        return Map.of("recorded", true);
    }

    // -- files --------------------------------------------------------------------------------------------------------

    /** Every file of the folder with its version and problems, and where the folder is. */
    @GetMapping("/files")
    public Map<String, Object> files(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        NetFolder.Loaded l = net.loaded();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", net.folder().dir().toString());
        out.put("watch", net.watchState());
        out.put("files", net.folder().files().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("exists", f.exists());
            m.put("etag", f.etag());
            m.put("bytes", f.bytes());
            m.put("refused", l.refused(f.id()));
            m.put("problems", l.problems().getOrDefault(f.id(), List.of()).stream().map(NetViews::problem).toList());
            return m;
        }).toList());
        return out;
    }

    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        requireFile(id);
        String text = net.folder().text(id).orElse("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("exists", net.folder().text(id).isPresent());
        out.put("text", text);
        out.put("etag", net.folder().text(id).map(NetFolder::etag).orElse(""));
        out.put("problems", net.loaded().problems().getOrDefault(id, List.of()).stream().map(NetViews::problem).toList());
        out.put("history", net.folder().history(id).stream().map(r -> Map.of("id", r.id(), "at", r.at().toString(), "bytes", r.bytes())).toList());
        return out;
    }

    @GetMapping("/file/revision")
    public Map<String, Object> revision(@RequestParam String id, @RequestParam String rev, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        requireFile(id);
        String text = net.folder().revisionText(id, rev).orElseThrow(() -> new DrishtiException(ErrorCode.NET_BAD_REQUEST, "no revision " + rev + " of " + id));
        return Map.of("id", id, "rev", rev, "text", text);
    }

    /** Body {@code {text}}: what the file would say, without writing it. */
    @PostMapping("/file/check")
    public Map<String, Object> check(@RequestParam String id, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        requireFile(id);
        List<NetSchema.Problem> problems = net.folder().check(id, body.path("text").asText(""));
        return Map.of("id", id, "ok", problems.stream().noneMatch(NetSchema.Problem::error), "problems", problems.stream().map(NetViews::problem).toList());
    }

    /** Body {@code {text}}, header {@code If-Match} (the version read; {@code ""} for a new file). A file with an error is refused, nothing written. */
    @PutMapping("/file")
    public ResponseEntity<Map<String, Object>> save(@RequestParam String id, @RequestBody JsonNode body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        requireFile(id);
        String text = body.path("text").asText("");
        List<NetSchema.Problem> problems = net.folder().check(id, text);
        if (problems.stream().anyMatch(NetSchema.Problem::error)) {
            Map<String, Object> refusal = new LinkedHashMap<>();
            refusal.put("code", ErrorCode.NET_FILE_REFUSED.code());
            refusal.put("detail", id + " was not written: " + problems.stream().filter(NetSchema.Problem::error).findFirst().orElseThrow());
            refusal.put("problems", problems.stream().map(NetViews::problem).toList());
            return ResponseEntity.status(409).body(refusal);
        }
        try {
            net.folder().write(id, text, ifMatch == null ? null : ifMatch);
        } catch (IllegalStateException e) {
            throw new DrishtiException(ErrorCode.NET_FILE_REFUSED, e.getMessage());
        }
        net.reload();
        record(p, "net-file-saved", id, "version " + NetFolder.etag(text) + (problems.isEmpty() ? "" : ", " + problems.size() + " warning(s)"));
        return ResponseEntity.ok(Map.of("id", id, "etag", NetFolder.etag(text), "problems", problems.stream().map(NetViews::problem).toList()));
    }

    @DeleteMapping("/file")
    public Map<String, Object> delete(@RequestParam String id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        requireFile(id);
        boolean gone;
        try {
            gone = net.folder().delete(id, ifMatch);
        } catch (IllegalStateException e) {
            throw new DrishtiException(ErrorCode.NET_FILE_REFUSED, e.getMessage());
        }
        net.reload();
        if (gone) {
            record(p, "net-file-deleted", id, "file removed (kept in the history)");
        }
        return Map.of("id", id, "deleted", gone);
    }

    private static void requireFile(String id) {
        if (!NetFolder.valid(id)) {
            throw new DrishtiException(ErrorCode.NET_BAD_REQUEST, "'" + id + "' is not a file of config/net/: node.yaml, gossip.yaml, routing.yaml, access.yaml or peers/<name>.yaml");
        }
    }
}
