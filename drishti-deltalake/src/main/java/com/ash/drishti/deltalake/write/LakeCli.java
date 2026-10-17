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
package com.ash.drishti.deltalake.write;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code java -jar drishti-server-<v>-exec.jar lake write --manifest FILE}: Drishti's Delta writer for {@code tools/lake/build.py}
 * ({@code drishti.py lake build --engine drishti}). Prints one JSON line per part written ({@code {"partsDone": n}}) and a last
 * line {@code {"done": true, "version", "files", "rows", "removed", "leftOut", "commits": 1}}. Exit 0 when committed, 1 when the
 * write failed (nothing was committed), 2 for a usage error.
 */
public final class LakeCli {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int USAGE = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private LakeCli() {}

    /** True when the command line is this tool's: {@code lake ...}. */
    public static boolean isCli(String[] args) {
        return args.length > 0 && "lake".equals(args[0]);
    }

    /** Runs {@code args} (starting with {@code lake}) and returns the exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> a = List.of(args);
        if (a.size() != 4 || !a.get(1).equals("write") || !a.get(2).equals("--manifest")) {
            err.println("usage: lake write --manifest FILE   (written by tools/lake/build.py; see docs/guides/TOOLS.md)");
            return USAGE;
        }
        Path manifest = Path.of(a.get(3));
        if (!Files.isRegularFile(manifest)) {
            err.println("lake: no manifest at " + manifest);
            return USAGE;
        }
        try {
            LakeManifest m = LakeManifest.read(manifest);
            LakeWriter.Result r = new LakeWriter().write(m, n -> {
                out.println("{\"partsDone\":" + n + "}");
                out.flush();
            });
            ObjectNode done = JSON.createObjectNode().put("done", true).put("version", r.version()).put("files", r.files())
                    .put("rows", r.rows()).put("removed", r.removed()).put("commits", 1);
            r.leftOut().forEach(done.putArray("leftOut")::add);
            out.println(JSON.writeValueAsString(done));
            out.flush();
            return OK;
        } catch (IllegalArgumentException e) {
            err.println("lake: " + e.getMessage());
            return USAGE;
        } catch (IOException | RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            err.println("lake: the write failed, nothing was committed: " + (root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage()));
            return FAILED;
        } catch (OutOfMemoryError e) {
            err.println("lake: out of memory: lower --batch-rows or raise DRISHTI_LAKE_HEAP");
            return FAILED;
        }
    }
}
