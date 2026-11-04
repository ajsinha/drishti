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

import com.ash.drishti.rachana.parse.SutraParser;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses Sutra files with the Rachana parser and prints every problem, no server and no Maven (a light check of a changed Sutra while a
 * release drill holds the machine). Run as a single-file program with the drishti-rachana jar and its libraries on the class path:
 *
 * <pre>
 *   java -cp "&lt;unpacked server jar&gt;/BOOT-INF/lib/*" tools/dev/SutraCheck.java config/packs/.../netting-set.v1.sutra.yaml
 * </pre>
 */
public final class SutraCheck {

    private SutraCheck() {}

    public static void main(String[] args) throws Exception {
        int bad = 0;
        for (String a : args) {
            Path p = Path.of(a);
            try {
                new SutraParser().parse(Files.readString(p), p.getFileName().toString(), p.getParent().getFileName().toString());
                System.out.println("ok   " + a);
            } catch (RuntimeException e) {
                bad++;
                System.out.println("FAIL " + a + ": " + e.getMessage());
            }
        }
        System.exit(bad == 0 ? 0 : 1);
    }
}
