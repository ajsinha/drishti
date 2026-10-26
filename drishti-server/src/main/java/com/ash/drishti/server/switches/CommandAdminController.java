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
package com.ash.drishti.server.switches;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.server.admin.ArtifactImpact;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The switches of Functions, FUNC, for administrators (docs/admin/COMMANDS.md): every command of the loaded packs, grouped by pack, with
 * what it does and whether it is on; a batch of changes with one reason (kept in the audit log); the history of changes; and what turning
 * one off stops. An administrator only (what every user reads is {@link FunctionsController}).
 * A change is written to this node's database and spread to every node of the cluster by gossip ({@link SwitchSync}); the answer says
 * which version of the switches each node holds.
 */
@RestController
@RequestMapping("/api/v1/admin/commands")
public class CommandAdminController {

    /** At most this many changes in one batch, and this long a reason. */
    static final int MAX_CHANGES = 500;
    static final int MAX_REASON = 500;

    /** One change: {@code scope} {@code command} (a mnemonic) or {@code pack} (a pack's name), on or off. */
    public record ChangeIn(String scope, String name, Boolean on) {}

    /** {@code {reason, changes: [...]}}. */
    public record Batch(String reason, List<ChangeIn> changes) {}

    private final CommandCatalog catalog;
    private final SwitchLedger ledger;
    private final PackAccess access;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final RoleCatalog roles;
    private final ObjectProvider<NetService> net;
    private final ObjectProvider<ArtifactImpact> impact;

    public CommandAdminController(CommandCatalog catalog, SwitchLedger ledger, PackAccess access, Entitlements entitlements, AuditLog audit,
            RoleCatalog roles, ObjectProvider<NetService> net, ObjectProvider<ArtifactImpact> impact) {
        this.catalog = catalog;
        this.ledger = ledger;
        this.access = access;
        this.entitlements = entitlements;
        this.audit = audit;
        this.roles = roles;
        this.net = net;
        this.impact = impact;
    }

    /** Every pack and command with its state, the roles (columns for grants per role, not used yet) and the version each node holds. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return view();
    }

    /** Applies the changes, with one reason; the list as it is then. */
    @PostMapping
    public Map<String, Object> change(@RequestBody Batch batch, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<ChangeIn> changes = batch == null || batch.changes() == null ? List.of() : batch.changes();
        String reason = batch == null || batch.reason() == null ? "" : batch.reason().strip();
        if (changes.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no changes: send {reason, changes: [{scope, name, on}]}");
        }
        if (changes.size() > MAX_CHANGES || reason.length() > MAX_REASON) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX_CHANGES + " changes and a reason of " + MAX_REASON + " characters");
        }
        List<SwitchLedger.Change> commands = new ArrayList<>();
        List<ChangeIn> packs = new ArrayList<>();
        for (ChangeIn c : changes) {
            if (c == null || c.name() == null || c.on() == null) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "each change is {scope, name, on}");
            }
            if (SwitchLedger.COMMAND.equals(c.scope())) {
                CommandCatalog.Command cmd = catalog.command(c.name())
                        .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no loaded pack defines the command '" + c.name() + "'"));
                if (ledger.on(SwitchLedger.COMMAND, cmd.code()) != c.on()) {
                    commands.add(new SwitchLedger.Change(SwitchLedger.COMMAND, cmd.code(), c.on()));
                }
            } else if (SwitchLedger.PACK.equals(c.scope())) {
                if (!access.installed().contains(c.name())) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + c.name() + "' is not loaded; installed: " + access.installed());
                }
                packs.add(c);
            } else {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "scope is command or pack, not '" + c.scope() + "'");
            }
        }
        for (ChangeIn c : packs) {
            if (access.isEnabled(c.name()) != c.on()) {
                access.setEnabled(c.name(), c.on(), p.user(), reason);
            }
        }
        if (!commands.isEmpty()) {
            ledger.set(commands, reason, p.user());
            commands.forEach(c -> audit.record(p.user(), c.on() ? "command-enabled" : "command-disabled", c.name(), reason));
        }
        return view();
    }

    /** The changes, newest first; {@code scope} and {@code name} narrow them to one switch. */
    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(required = false) String scope, @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "200") int limit, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<Map<String, Object>> items = ledger.history(scope, name, limit).stream().map(CommandAdminController::entry).toList();
        return Map.of("items", items);
    }

    /** What turning the command off stops: its page and API paths, and what names its kind (BI datasets, saved items, shares). */
    @GetMapping("/{code}/impact")
    public Map<String, Object> impact(@PathVariable String code, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        CommandCatalog.Command cmd = catalog.command(code)
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "no loaded pack defines the command '" + code + "'"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", cmd.code());
        out.put("kind", cmd.kind());
        out.put("page", "/v/" + cmd.kind() + "/…");
        out.put("api", "/api/v1/views/" + cmd.kind() + "/…");
        ArtifactImpact a = impact.getIfAvailable();
        out.put("uses", a == null ? List.of() : a.ofKind(cmd.code(), cmd.kind()).groups());
        return out;
    }

    private Map<String, Object> view() {
        List<Map<String, Object>> packs = new ArrayList<>();
        int total = 0;
        int off = 0;
        for (CommandCatalog.PackCommands pc : catalog.packs()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            String name = pc.pack().name();
            pm.put("name", name);
            pm.put("code", pc.pack().code());
            pm.put("title", pc.pack().title());
            pm.put("on", access.isEnabled(name));
            pm.put("neededBy", access.requiredBy(name));
            ledger.find(SwitchLedger.PACK, name).ifPresent(e -> state(pm, e));
            List<Map<String, Object>> cmds = new ArrayList<>();
            for (CommandCatalog.Command c : pc.commands()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("code", c.code());
                cm.put("kind", c.kind());
                cm.put("label", c.label());
                cm.put("description", c.description());
                cm.put("example", c.example());
                boolean on = ledger.on(SwitchLedger.COMMAND, c.code());
                cm.put("on", on);
                ledger.find(SwitchLedger.COMMAND, c.code()).ifPresent(e -> state(cm, e));
                cmds.add(cm);
                total++;
                off += on ? 0 : 1;
            }
            pm.put("commands", cmds);
            packs.add(pm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", ledger.node());
        out.put("hash", ledger.hash());
        long newest = ledger.newest();
        out.put("newest", newest == 0 ? null : SwitchLedger.timeOf(newest).toString());
        out.put("total", total);
        out.put("off", off);
        out.put("roles", roles.all().stream().map(r -> r.name()).sorted().toList());
        out.put("nodes", nodes());
        out.put("packs", packs);
        return out;
    }

    private static void state(Map<String, Object> m, SwitchLedger.Entry e) {
        m.put("since", e.at() == null ? null : e.at().toString());
        m.put("actor", e.actor());
        m.put("reason", e.reason());
        m.put("node", e.node());
    }

    /** This node and, with the cluster on, each member with the switches hash it gossips: in step when it equals this node's. */
    private List<Map<String, Object>> nodes() {
        List<Map<String, Object>> out = new ArrayList<>();
        NetService n = net.getIfAvailable();
        if (n == null || n.node().isEmpty()) {
            out.add(Map.of("node", ledger.node(), "self", true, "state", "alive", "hash", ledger.hash(), "inStep", true));
            return out;
        }
        for (Member m : n.node().get().table().entries()) {
            boolean self = m.nodeId().equals(n.nodeId());
            String h = self ? ledger.hash() : m.switchesHash();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("node", m.nodeId());
            r.put("self", self);
            r.put("state", m.state().wire());
            r.put("hash", h);
            r.put("inStep", h.equals(ledger.hash()));
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> entry(SwitchLedger.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", e.scope());
        m.put("name", e.name());
        m.put("grantee", e.grantee());
        m.put("on", e.on());
        m.put("reason", e.reason());
        m.put("actor", e.actor());
        m.put("at", e.at() == null ? Instant.EPOCH.toString() : e.at().toString());
        m.put("node", e.node());
        return m;
    }
}
