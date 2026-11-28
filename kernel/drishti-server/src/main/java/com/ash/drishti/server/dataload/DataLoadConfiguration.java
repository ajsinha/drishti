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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.lake.inbox.DataInbox;
import com.ash.lake.inbox.DataLoadSettings;
import com.ash.lake.inbox.LoaderProcess;
import com.ash.lake.inbox.ReadyDesk;
import com.ash.lake.inbox.ReadyRule;
import com.ash.lake.inbox.Routes;
import com.ash.lake.inbox.Signal;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.admission.BusyRefusal;
import com.ash.drishti.server.admission.Work;
import com.ash.drishti.server.engines.BasicEngine;
import com.ash.drishti.server.loads.LoadForwarding;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.net.NetService;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.net.InetAddress;
import java.time.Clock;
import java.time.ZoneId;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The server's wiring of the data inbox (docs/guides/DATA_LAKE_GUIDE.md; the inbox itself is the plain-Java module
 * {@code drishti-lakeload}): {@code drishti.dataload.*} bound onto {@link DataLoadSettings}, the loaded packs for routing, busy protection
 * for the busy check, the bridge to {@link LoadService} for the signal (the same service method the HTTP data-load signal calls), and the
 * inbox as a compute engine ({@code config/engines/dataload.yaml}, off as shipped); in a cluster each signal is passed on to the other
 * members ({@link LoadForwarding}, as this node): on starts its schedule, off stops it and a load that runs.
 */
@Configuration
public class DataLoadConfiguration {

    /** Who the inbox's signals are made by, in the Data loads history and the audit log. */
    static final String BY = "dataload";

    @Bean
    public DataLoadSettings dataLoadSettings(Environment env) {
        // bound key by key: the settings record also holds the process's LakeRuntime, which is not a setting
        Binder b = Binder.get(env);
        java.util.function.BiFunction<String, Class<?>, Object> v = (k, t) -> b.bind("drishti.dataload." + k, t).orElse(null);
        return new DataLoadSettings((Boolean) v.apply("owner", Boolean.class), (String) v.apply("dir", String.class),
                (java.time.Duration) v.apply("interval", java.time.Duration.class), (Integer) v.apply("stable-seconds", Integer.class),
                (Integer) v.apply("max-files", Integer.class), (Double) v.apply("max-gb", Double.class), (String) v.apply("loader-heap", String.class),
                (java.time.Duration) v.apply("load-timeout", java.time.Duration.class), (Double) v.apply("reject-max-percent", Double.class),
                (Boolean) v.apply("reload-duplicates", Boolean.class), (Integer) v.apply("keep-outbox-days", Integer.class),
                (String) v.apply("lake", String.class), (String) v.apply("rules-file", String.class), (Boolean) v.apply("nice", Boolean.class),
                (String) v.apply("default-mode", String.class), (Boolean) v.apply("new-tables", Boolean.class),
                com.ash.drishti.lakehost.DrishtiLake.runtime());
    }

    @Bean(destroyMethod = "stop")
    public DataInbox dataInbox(DataLoadSettings settings, ObjectProvider<LoadService> loads, ObjectProvider<AdmissionGate> gate,
            ObjectProvider<NetService> net, ObjectProvider<BusinessDates> dates, ObjectProvider<PeerCalls> peers,
            ObjectProvider<com.ash.drishti.engine.source.SourceRegistry> sources, Environment env,
            ObjectProvider<com.ash.drishti.registry.AccessRegistry> access, ObjectProvider<com.ash.drishti.server.security.PackAccess> packAccess) {
        BusinessDates bd = dates.getIfAvailable();
        ZoneId zone = bd == null ? ZoneId.systemDefault() : bd.zone();
        String node = node(net.getIfAvailable());
        return new DataInbox(settings, node, Clock.systemUTC(), zone, packs(loads, sources, moduleRules(env, access, packAccess)), new LoaderProcess(settings),
                signal(loads, new LoadForwarding(peers.getIfAvailable()), LoadForwarding.node(node)), busy(gate));
    }

    /** The inbox as a compute engine (ADMIN_GUIDE.md): live on and off; its thread is {@code dataload-cycle}. */
    @Bean
    public BasicEngine dataloadComputeEngine(DataInbox inbox) {
        return new BasicEngine("dataload").onStart(inbox::start).onStop(inbox::stop).threads("dataload-");
    }

    @Bean
    public DataLoadController dataLoadController(DataInbox inbox, Entitlements entitlements, ObjectProvider<AuditLog> audit) {
        return new DataLoadController(inbox, entitlements, audit.getIfAvailable());
    }

    /** The Upload page and {@code drishti.py dataload ready}: {@code drishti.dataload.upload.*}, and every module's checks of a file. */
    @Bean
    public ReadyDesk readyDesk(DataLoadSettings settings, DataInbox inbox, Environment env, ObjectProvider<LoadService> loads,
            ObjectProvider<com.ash.drishti.engine.source.SourceRegistry> sources, ObjectProvider<NetService> net, ObjectProvider<ReadyRule> rules,
            ObjectProvider<com.ash.drishti.registry.AccessRegistry> access,
            ObjectProvider<com.ash.drishti.server.security.PackAccess> packAccess) {
        ReadyDesk.Settings upload = Binder.get(env).bind("drishti.dataload.upload", ReadyDesk.Settings.class).orElseGet(ReadyDesk.Settings::defaults);
        return new ReadyDesk(settings, upload, inbox, packs(loads, sources, moduleRules(env, access, packAccess)), Clock.systemUTC(), node(net.getIfAvailable()), rules.orderedStream().toList());
    }

    @Bean
    public ReadyController readyController(ReadyDesk desk, DataInbox inbox, Entitlements entitlements, ObjectProvider<AuditLog> audit,
            ObjectProvider<com.ash.drishti.server.engines.EngineGateFilter.Peers> peers) {
        return new ReadyController(desk, inbox, entitlements, audit.getIfAvailable(), peers.getIfAvailable());
    }

    /** This node's name in the folder lock: its cluster id, else the host's name. */
    static String node(NetService net) {
        String id = net == null ? null : net.nodeId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "this-server";
        }
    }

    /**
     * The inbox rules of the modules that are on (each module's {@code module.yaml}, its {@code dataload:}: a file name, read from the
     * module's folder, or the rules inline), read at each cycle so that switching a module off or on changes the rules at once. The
     * module's folder is the one the module boot gave it, through the resolution.
     */
    static Supplier<java.util.List<Routes.ModuleRules>> moduleRules(Environment env, ObjectProvider<com.ash.drishti.registry.AccessRegistry> access,
            ObjectProvider<com.ash.drishti.server.security.PackAccess> packAccess) {
        return () -> {
            // the resolution's modules that are on, each in the folder the module boot gave it;
            // a rules file that cannot be read is a problem of the resolution (Health, DRS-1048), never a silent empty list
            java.util.List<Routes.ModuleRules> out = new java.util.ArrayList<>();
            com.ash.drishti.api.resolution.ConfigResolution r = com.ash.drishti.packs.PackPaths.resolution(env);   // the current one: a switch rebuilds it
            com.fasterxml.jackson.dataformat.yaml.YAMLMapper yaml = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
            java.util.Map<String, java.util.List<java.util.Map<String, Object>>> byModule = new java.util.LinkedHashMap<>();
            com.ash.drishti.server.security.PackAccess packs = packAccess.getIfAvailable();
            for (com.ash.drishti.api.resolution.ModuleTemplates.Rule rule : followPackSwitch(r.rules(), packs == null ? null : packs::installed, packs == null ? null : packs::isEnabled)) {
                byModule.computeIfAbsent(rule.module(), k -> new java.util.ArrayList<>()).add(rule.rule());
            }
            byModule.forEach((id, rules) -> out.add(new Routes.ModuleRules(id,
                    yaml.valueToTree(java.util.Map.of("dataload", java.util.Map.of("rules", rules))))));
            return out;
        };
    }

    /**
     * The rules a pack switch leaves: a rule that names a {@code pack:} which is installed here and switched off (Admin → Packs) is not
     * matched, so its files stay in the inbox, unloaded (docs/plans/MODULE_RATIONALISATION.md, gap G3). A rule of no pack, or of a pack this
     * server does not know, stays.
     */
    static java.util.List<com.ash.drishti.api.resolution.ModuleTemplates.Rule> followPackSwitch(
            java.util.List<com.ash.drishti.api.resolution.ModuleTemplates.Rule> rules, Supplier<java.util.List<String>> installed,
            java.util.function.Predicate<String> enabled) {
        if (installed == null || enabled == null) {
            return rules;
        }
        java.util.List<String> known = installed.get();
        return rules.stream().filter(rule -> {
            Object pack = rule.rule().get("pack");
            return pack == null || !known.contains(String.valueOf(pack)) || enabled.test(String.valueOf(pack));
        }).toList();
    }

    /** The loaded packs by name: the folder whose pack.yaml the loader reads, and the kinds; the lakes the connectors read. */
    static Routes.Packs packs(ObjectProvider<LoadService> loads, ObjectProvider<com.ash.drishti.engine.source.SourceRegistry> sources,
            Supplier<java.util.List<Routes.ModuleRules>> modules) {
        return new Routes.Packs() {
            @Override
            public java.util.List<Routes.ModuleRules> moduleRules() {
                return modules.get();
            }

            @Override
            public Routes.PackInfo find(String name) {
                LoadService s = loads.getIfAvailable();
                if (s == null) {
                    return null;
                }
                for (Pack p : s.loadedPacks()) {
                    if (p.name().equals(name)) {
                        return p.dir() == null ? null : new Routes.PackInfo(p.dir(), p.kinds());
                    }
                }
                return null;
            }

            @Override
            public java.util.List<String> names() {
                LoadService s = loads.getIfAvailable();
                return s == null ? java.util.List.of() : s.loadedPacks().stream().filter(p -> p.dir() != null).map(Pack::name).toList();
            }

            @Override
            public java.util.Set<String> lakesRead() {
                com.ash.drishti.engine.source.SourceRegistry r = sources == null ? null : sources.getIfAvailable();
                return r == null ? null : r.lakesRead();
            }
        };
    }

    /**
     * The signal: {@link LoadService#announce} with a ready announcement, as {@code POST /api/v1/packs/{pack}/loads} makes it, then passed
     * on to every other live member of the cluster by {@link LoadForwarding} as this node (the same path a person's announcement takes).
     * A refusal of the service (no such pack or kind, a date in the future) is not sent again; anything else is sent again at the next cycle.
     */
    static Signal signal(ObjectProvider<LoadService> loads, LoadForwarding forwarding, Principal self) {
        return l -> {
            LoadService s = loads.getIfAvailable();
            if (s == null) {
                throw new IllegalStateException("the data-load service is not ready");
            }
            String files = String.join(", ", l.files());
            String source = "inbox: " + files;
            LoadService.Announcement a = new LoadService.Announcement(l.kind(), l.date().toString(), null, LoadRecord.READY, l.rows(), l.rejected(),
                    source.length() > 200 ? source.substring(0, 197) + "..." : source, l.batch(), cut("loaded from the data inbox: " + files, 1000));
            try {
                if (!s.announce(l.pack(), a, BY).duplicate()) {
                    forwarding.forward(l.pack(), a, self);
                }
            } catch (DrishtiException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        };
    }

    /** Busy protection's answer for a bulk load the server starts itself: null to go on, else why not now (memory, the disk's floor). */
    static Supplier<String> busy(ObjectProvider<AdmissionGate> gate) {
        return () -> {
            AdmissionGate g = gate.getIfAvailable();
            if (g == null) {
                return null;
            }
            try {
                g.admit(Work.BULK_LOAD, "", "data inbox cycle");
                return null;
            } catch (BusyRefusal e) {
                return e.because();
            }
        };
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
