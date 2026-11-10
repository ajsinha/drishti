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
package com.ash.drishti.server.modules.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto-deploy, as Tomcat's {@code webapps/} (docs/guides/ADMIN_GUIDE.md, Admin → Modules): a {@code .dmod} dropped into a module folder
 * ({@code drishti.modules.path}, default {@code <data folder>/modules}) is stored (its hash list, the fifteen conformance checks and the
 * origin rules), planned and applied through the module deployment, as an administrator would; when its file is removed, the module is
 * removed the same way (410, its tombstone kept). Every step is audited as {@code autodeploy}. On by default for a single node; off by
 * default in a cluster, where plan → apply by two administrators is the way ({@code drishti.modules.autodeploy}).
 */
public final class ModuleAutoDeploy {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleAutoDeploy.class);
    static final String PLANNER = "autodeploy";
    static final String APPLIER = "autodeploy-apply";

    /** A package this watch deployed: its module, version and the bytes' hash. */
    record Deployed(String id, String version, String sha) {
    }

    private final List<Path> folders;
    private final ModuleDeployService deploy;
    private final Map<Path, Deployed> deployed = new LinkedHashMap<>();
    private final Map<Path, String> refused = new LinkedHashMap<>();

    public ModuleAutoDeploy(List<Path> folders, ModuleDeployService deploy) {
        this.folders = List.copyOf(folders);
        this.deploy = deploy;
    }

    /** One look at the folders: deploys a new or changed package, removes the module of a package that left. Returns what it did. */
    public synchronized List<String> scan() {
        List<String> did = new ArrayList<>();
        List<Path> now = packages();
        for (Path p : now) {
            try {
                String sha = sha(p);
                Deployed d = deployed.get(p);
                if (d != null && d.sha().equals(sha) || sha.equals(refused.get(p))) {
                    continue;
                }
                ModuleDeployService.Stored s = deploy.upload(Files.readAllBytes(p), PLANNER);
                Map<String, String> active = deploy.activeHere();
                if (!s.version().equals(active.get(s.id()))) {
                    run("install", s.id(), s.version(), "auto-deploy: " + p.getFileName() + " was put in " + p.getParent());
                    did.add("deployed " + s.id() + " " + s.version());
                }
                deployed.put(p, new Deployed(s.id(), s.version(), sha));
                refused.remove(p);
            } catch (IOException | RuntimeException e) {
                LOG.warn("auto-deploy: {} refused: {}", p.getFileName(), e.getMessage());
                try {
                    refused.put(p, sha(p));                // not tried again until its bytes change
                } catch (IOException ignored) {
                    // gone again
                }
                did.add("refused " + p.getFileName() + ": " + e.getMessage());
            }
        }
        for (Map.Entry<Path, Deployed> e : List.copyOf(deployed.entrySet())) {
            if (now.contains(e.getKey())) {
                continue;
            }
            deployed.remove(e.getKey());
            String id = e.getValue().id();
            boolean stillDropped = deployed.values().stream().anyMatch(x -> x.id().equals(id));
            if (!stillDropped && deploy.activeHere().containsKey(id)) {
                try {
                    run("remove", id, null, "auto-deploy: " + e.getKey().getFileName() + " was taken out of " + e.getKey().getParent());
                    did.add("removed " + id);
                } catch (RuntimeException ex) {
                    LOG.warn("auto-deploy: removing {} refused: {}", id, ex.getMessage());
                    did.add("refused removing " + id + ": " + ex.getMessage());
                }
            }
        }
        refused.keySet().retainAll(now);
        did.forEach(d -> LOG.info("auto-deploy: {}", d));
        return did;
    }

    /** The packages this watch deployed, by file name (Health and Admin → Modules). */
    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folders", folders.stream().map(Path::toString).toList());
        Map<String, String> files = new LinkedHashMap<>();
        deployed.forEach((p, d) -> files.put(p.getFileName().toString(), d.id() + " " + d.version()));
        out.put("deployed", files);
        out.put("refused", refused.keySet().stream().map(p -> p.getFileName().toString()).toList());
        return out;
    }

    private void run(String action, String id, String version, String reason) {
        ModuleDeployService.Deployment d = deploy.plan(action, id, version, reason, PLANNER);
        deploy.apply(d.id(), "the auto-deploy watch (drishti.modules.autodeploy)", APPLIER);
    }

    private List<Path> packages() {
        List<Path> out = new ArrayList<>();
        for (Path f : folders) {
            if (!Files.isDirectory(f)) {
                continue;
            }
            try (Stream<Path> s = Files.list(f)) {
                s.filter(p -> p.getFileName().toString().endsWith(".dmod") && Files.isRegularFile(p)).sorted().forEach(out::add);
            } catch (IOException e) {
                LOG.warn("auto-deploy: {} does not read: {}", f, e.getMessage());
            }
        }
        return out;
    }

    private static String sha(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return com.ash.drishti.module.runtime.Dmod.sha256(in);
        }
    }
}
