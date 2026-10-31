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

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.compute.ConfigValues;
import com.ash.drishti.identity.SwitchLedger;
import com.ash.drishti.packs.ConnectorFiles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Connectors' {@code writes:} (WRITE_BACK.md › The design and its reasons, section 5.4): the Sutra says <i>what</i> may change, the connector says <i>how</i> in that
 * system, the lane owner says <i>when</i> and in what order. A command is
 * <pre>
 * writes:
 *   amend-notional:
 *     kind: trade
 *     params: [leg]                 # extra parameters a Sutra may supply by name
 *     jdbc: { sql: ..., expect-rows: 1, already-applied: ... }   # the plugin's block: its template, guard and idempotency
 *     confirm: reply                # reply | ack-topic | next-load
 *     timeout: 30s
 * </pre>
 * and is checked when the connector loads: the plugin must write (today the JDBC plugin; the file and lake plugins never: the lake is not
 * written per edit, approved overlays are folded into the next build), the guard and the idempotency clause are required, every
 * {@code :param} must be one Drishti supplies or the command declares. A command with a problem is refused and Admin → Connectors says
 * why; the connector stays read-only for it. A connector writes only once an administrator switches its writes on (audited; off by default).
 */
public final class ConnectorWrites {

    public static final Set<String> CONFIRMS = Set.of("reply", "ack-topic", "next-load");
    private static final Set<String> KEYS = Set.of("kind", "params", "confirm", "timeout", "description");

    /** One usable command. */
    public record Command(String connector, String name, String kind, String pluginKey, Map<String, Object> block, List<String> params, String confirm,
            Duration timeout) {

        /** The command filled for one change's item. */
        public WriteCommand fill(Map<String, Object> values) {
            return new WriteCommand(name, block, values);
        }
    }

    private final Function<String, ConnectorFiles.Definition> definitions;
    private final Function<String, Optional<SourcePlugin>> plugins;
    private final SwitchLedger switches;
    private volatile java.util.function.Predicate<String> switchedOn;

    public ConnectorWrites(Function<String, ConnectorFiles.Definition> definitions, Function<String, Optional<SourcePlugin>> plugins, SwitchLedger switches) {
        this.definitions = definitions;
        this.plugins = plugins;
        this.switches = switches;
    }

    /** What is wrong with a definition's {@code writes:}, per command (an empty map: every command is usable). */
    public static Map<String, List<String>> problems(ConnectorFiles.Definition d, Optional<SourcePlugin> plugin) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : d.writes().entrySet()) {
            String name = e.getKey();
            List<String> p = new ArrayList<>();
            if (!ConnectorFiles.NAME.matcher(name).matches()) {
                p.add("a command's name is lower case, digits and -, not '" + name + "'");
            }
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                p.add(name + ": a command is a mapping (kind, the plugin's block, confirm, timeout)");
                out.put(name, p);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            if (!(m.get("kind") instanceof String)) {
                p.add(name + ": kind names the kind the command amends");
            }
            Object confirm = m.getOrDefault("confirm", "reply");
            if (!CONFIRMS.contains(String.valueOf(confirm))) {
                p.add(name + ": confirm is reply, ack-topic or next-load, not '" + confirm + "'");
            }
            if (m.get("timeout") != null) {
                try {
                    ConfigValues.duration(String.valueOf(m.get("timeout")), name + ".timeout");
                } catch (IllegalArgumentException | IllegalStateException x) {
                    p.add(name + ": timeout is a time such as 30s, not '" + m.get("timeout") + "'");
                }
            }
            Set<String> known = new TreeSet<>(WriteCommand.SUPPLIED);
            if (m.get("params") instanceof List<?> l) {
                l.forEach(x -> known.add(String.valueOf(x)));
            }
            if (plugin.isEmpty()) {
                p.add(name + ": the connector's plugin is not running, so its commands cannot be checked; it writes nothing until it is");
            } else if (plugin.get().writeKey().isEmpty()) {
                p.add(name + ": plugin " + d.plugin() + " cannot write (only the JDBC plugin writes in 2.8.0; a lake is never written per edit:"
                        + " approved overlays are folded into the next build)");
            } else {
                String key = plugin.get().writeKey().get();
                for (String k : m.keySet()) {
                    if (!KEYS.contains(k) && !k.equals(key)) {
                        p.add(name + ": '" + k + "' is not a key of a command (kind, params, confirm, timeout, " + key + ")");
                    }
                }
                if (!(m.get(key) instanceof Map<?, ?> block)) {
                    p.add(name + ": the command's " + key + ": block is required");
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> b = (Map<String, Object>) block;
                    p.addAll(plugin.get().checkWrite(name, b, known));
                }
            }
            if (!p.isEmpty()) {
                out.put(name, p);
            }
        }
        return out;
    }

    /** True when an administrator switched this connector's writes on. */
    public boolean on(String connector) {
        java.util.function.Predicate<String> o = switchedOn;
        if (o != null) {
            return o.test(connector);
        }
        return switches != null && switches.find(SwitchLedger.WRITES, connector).map(SwitchLedger.Entry::on).orElse(false);
    }

    /** Decides the switch by {@code on} instead of the ledger (a server without identity, and tests). */
    public ConnectorWrites switchedBy(java.util.function.Predicate<String> on) {
        this.switchedOn = on;
        return this;
    }

    /** Switches a connector's writes on or off (administrators; audited by the caller). */
    public void set(String connector, boolean on, String reason, String by) {
        switches.set(List.of(new SwitchLedger.Change(SwitchLedger.WRITES, connector, on)), reason, by);
    }

    /** A usable command, or why it cannot be used now. */
    public Command command(String connector, String name) {
        ConnectorFiles.Definition d;
        try {
            d = definitions.apply(connector);
        } catch (RuntimeException e) {
            throw new IllegalStateException("connector " + connector + " cannot be read: " + e.getMessage());
        }
        if (d == null) {
            throw new IllegalStateException("there is no connector " + connector);
        }
        if (!(d.writes().get(name) instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("connector " + connector + " has no writes: command " + name);
        }
        Optional<SourcePlugin> plugin = plugins.apply(connector);
        List<String> problems = problems(d, plugin).get(name);
        if (problems != null) {
            throw new IllegalStateException("connector " + connector + "'s command " + name + " is refused: " + String.join("; ", problems));
        }
        if (!on(connector)) {
            throw new IllegalStateException("connector " + connector + "'s writes are off: an administrator switches them on (Admin -> Edits)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) raw;
        String key = plugin.orElseThrow().writeKey().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) m.get(key);
        List<String> params = m.get("params") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        Duration timeout = m.get("timeout") == null ? Duration.ofSeconds(30) : ConfigValues.duration(String.valueOf(m.get("timeout")), name + ".timeout");
        return new Command(connector, name, String.valueOf(m.get("kind")), key, block, params, String.valueOf(m.getOrDefault("confirm", "reply")), timeout);
    }

    /** Why a command cannot be used for a kind now, or empty. */
    public Optional<String> unusable(String connector, String name, String kind) {
        try {
            Command c = command(connector, name);
            if (!c.kind().equals(kind)) {
                return Optional.of("command " + name + " of " + connector + " amends " + c.kind() + ", not " + kind);
            }
            return Optional.empty();
        } catch (IllegalStateException e) {
            return Optional.of(e.getMessage());
        }
    }

    public Optional<SourcePlugin> plugin(String connector) {
        return plugins.apply(connector);
    }

    /** Every connector's writes for Admin → Edits: its commands, problems, and whether its writes are on. */
    public List<Map<String, Object>> describe(List<String> connectors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : connectors) {
            ConnectorFiles.Definition d;
            try {
                d = definitions.apply(name);
            } catch (RuntimeException e) {
                continue;
            }
            if (d == null || d.writes().isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("connector", name);
            m.put("plugin", d.plugin());
            m.put("commands", new ArrayList<>(d.writes().keySet()));
            m.put("problems", problems(d, plugins.apply(name)));
            m.put("on", on(name));
            out.add(m);
        }
        return out;
    }
}
