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
package com.ash.drishti.server;

import com.ash.lake.tck.TestRoots;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * The connectors of a test, the one way a connector is defined: connector files. A set is a folder of files in the repository's
 * {@code testing/connectors/<set>/} (a whole connector, or the keys that change a pack's template, such as {@code enabled: false}); a test
 * copies it, from a static initializer, into its own folder {@code ${drishti.test.dir}/connectors/<set>} before its context starts and names that
 * folder as {@code drishti.sources.connectors-dir}. A setting only the running test knows (a lake's folder) is a placeholder in the file,
 * {@code ${test-connectors.<connector>.<setting>}}, which the test's {@code @DynamicPropertySource} gives.
 *
 * <pre>
 * &#64;SpringBootTest(properties = "drishti.sources.connectors-dir=${drishti.test.dir}/connectors/var-api")
 * class VarApiTest { static { ConnectorFixtures.copy("var-api"); } ... }
 * </pre>
 */
public final class ConnectorFixtures {

    private ConnectorFixtures() {
    }

    /** Copies {@code testing/connectors/<set>} into the run's {@code connectors/<set>} folder and returns it. */
    public static Path copy(String set) {
        Path from = Path.of(TestRoots.repo(), "testing", "connectors", set);
        Path to = TestRun.dir("connectors/" + set);
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).toList()) {
                Files.copy(f, to.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the connector set " + set + " cannot be copied from " + from, e);
        }
        return to;
    }
}
