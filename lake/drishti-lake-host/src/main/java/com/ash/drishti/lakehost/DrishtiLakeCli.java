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
package com.ash.drishti.lakehost;

import com.ash.lake.load.LakeCheck;
import com.ash.lake.load.LakeLoad;
import java.util.Arrays;

/**
 * Drishti Lake's commands with Drishti's runtime ({@link DrishtiLake#runtime()}: its catalog, so the shipped lakes, the packs and the
 * connector templates are seen as the server sees them): {@code load} (the loader, {@code LakeLoad}) and {@code check}
 * ({@code LakeCheck}). The server's loader child process and {@code tools/drishti.py lake} start this class.
 */
public final class DrishtiLakeCli {

    private DrishtiLakeCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: DrishtiLakeCli load|check [the command's arguments]");
            return 2;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "load" -> LakeLoad.run(DrishtiLake.runtime(), rest, System.out);
            case "check" -> LakeCheck.run(DrishtiLake.runtime(), rest, System.out);
            default -> {
                System.err.println("unknown command " + args[0] + ": load or check");
                yield 2;
            }
        };
    }
}
