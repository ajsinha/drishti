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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Banking's sources build as their own unit (modules/banking, KERNEL_AND_MODULES.md section 8.10) against the kernel API and a recorded
 * list of the kernel internals they still reach: {@code modules/banking/kernel-internals.txt}, one package per line. The list stays true
 * both ways: a package banking starts to reach must be added on purpose (in review), and a package it no longer reaches must be removed
 * (the list only shrinks towards the kernel API alone, which banking needs to become a {@code .dmod} that passes conformance check C1).
 */
class BankingReachTest {

    private static final Path BANKING = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern REF = Pattern.compile("\\bcom\\.ash\\.drishti\\.((?:[a-z0-9_]+\\.)*[a-z0-9_]+)\\.[A-Z]");

    @Test
    void theKernelInternalsBankingReachesAreExactlyTheRecordedList() throws IOException {
        Set<String> reached = new TreeSet<>();
        for (String part : new String[] {"banking-compute", "banking-service"}) {
            try (Stream<Path> files = Files.walk(BANKING.resolve(part).resolve("src/main/java"))) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    for (String line : Files.readAllLines(f)) {
                        String t = line.trim();
                        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) {
                            continue;                                       // Javadoc and comments name classes without reaching them
                        }
                        Matcher m = REF.matcher(line);
                        while (m.find()) {
                            String pkg = "com.ash.drishti." + m.group(1);
                            if (!pkg.startsWith("com.ash.drishti.kernel.api") && !pkg.startsWith("com.ash.drishti.banking")) {
                                reached.add(pkg);
                            }
                        }
                    }
                }
            }
        }
        Set<String> recorded = new TreeSet<>();
        for (String line : Files.readAllLines(BANKING.resolve("kernel-internals.txt"))) {
            // a "# bytecode" or "# library" line is for the package's allowance (what the compiled classes reach), not the sources
            if (!line.isBlank() && !line.startsWith("#") && !line.contains("#")) {
                recorded.add(line.trim());
            }
        }
        Set<String> added = new TreeSet<>(reached);
        added.removeAll(recorded);
        Set<String> gone = new TreeSet<>(recorded);
        gone.removeAll(reached);
        assertThat(added).as("banking reaches kernel internals that are not in modules/banking/kernel-internals.txt: use the kernel API "
                + "(drishti-kernel-api) instead, or add the package to the list on purpose").isEmpty();
        assertThat(gone).as("banking no longer reaches these packages: remove them from modules/banking/kernel-internals.txt "
                + "(the list only shrinks)").isEmpty();
    }
}
