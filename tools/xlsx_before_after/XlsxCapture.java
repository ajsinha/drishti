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
package com.ash.drishti.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Before/after evidence: when DRISHTI_XLSX_CAPTURE names a folder, every workbook a test hands over is written there under a stable name. */
public final class XlsxCapture {
    private static final Map<String, Integer> COUNT = new HashMap<>();

    private XlsxCapture() {}

    public static byte[] save(byte[] xlsx) {
        String dir = System.getenv("DRISHTI_XLSX_CAPTURE");
        if (dir == null || dir.isBlank() || xlsx == null) {
            return xlsx;
        }
        Optional<StackWalker.StackFrame> f = StackWalker.getInstance().walk(s -> s
                .filter(x -> x.getClassName().endsWith("Test") || x.getClassName().contains("Test$")).findFirst());
        String who = f.map(x -> x.getClassName().substring(x.getClassName().lastIndexOf('.') + 1) + "." + x.getMethodName()).orElse("unknown");
        synchronized (COUNT) {
            int n = COUNT.merge(who, 1, Integer::sum);
            try {
                Path d = Path.of(dir);
                Files.createDirectories(d);
                Files.write(d.resolve(who.replaceAll("[^A-Za-z0-9_.$-]", "_") + "#" + n + ".xlsx"), xlsx);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return xlsx;
    }
}
