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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * The one folder a test JVM writes into, removed when the JVM ends, so nothing a run makes survives it. Its path is the
 * {@code drishti.test.dir} system property (surefire sets {@code target/test-run/fork<n>}; a run from the IDE gets
 * {@code target/test-run/pid-<pid>}). Test properties name it as {@code ${drishti.test.dir}/...} (the server's own defaults in
 * {@code src/test/resources/application.properties}, the per-class folders of a {@code @SpringBootTest}); test code that seeds a folder
 * before its context starts uses {@link #dir}, and a temporary folder comes from {@link #temp}.
 *
 * <p>Removal waits for the Spring test contexts: every context is recorded as it is made ({@link Contexts}, registered in
 * {@code META-INF/spring.factories}) and the shutdown hook closes each (a close already under way in Spring's own hook is waited for)
 * before the folder is deleted, so no store flushes into a folder being removed.
 */
public final class TestRun {

    /** The property naming the folder (a system property, so {@code ${drishti.test.dir}} resolves in every test's properties). */
    public static final String PROPERTY = "drishti.test.dir";

    private static final List<ConfigurableApplicationContext> CONTEXTS = new CopyOnWriteArrayList<>();
    private static final Path ROOT = start();

    private TestRun() {}

    private static Path start() {
        String set = System.getProperty(PROPERTY);
        Path root = (set == null || set.isBlank() ? Path.of("target", "test-run", "pid-" + ProcessHandle.current().pid()) : Path.of(set))
                .toAbsolutePath().normalize();
        delete(root);                                   // a fork number reused after a killed run starts empty
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.setProperty(PROPERTY, root.toString());
        Runtime.getRuntime().addShutdownHook(new Thread(TestRun::end, "drishti-test-run-cleanup"));
        return root;
    }

    /** The run's folder. */
    public static Path root() {
        return ROOT;
    }

    /** {@code name} under the run's folder, made (with its parents). */
    public static Path dir(String name) {
        try {
            return Files.createDirectories(ROOT.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A new, uniquely named folder under the run's folder (in place of {@code Files.createTempDirectory}). */
    public static Path temp(String prefix) {
        try {
            return com.ash.drishti.common.TempFiles.createDir(ROOT, prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void end() {
        for (ConfigurableApplicationContext c : CONTEXTS) {
            try {
                c.close();
            } catch (RuntimeException e) {
                // a context that fails to close still leaves its folder to remove
            }
        }
        delete(ROOT);
        Path parent = ROOT.getParent();
        try (Stream<Path> s = Files.list(parent)) {
            if (s.findAny().isEmpty()) {
                Files.deleteIfExists(parent);           // target/test-run itself, once the last fork is done
            }
        } catch (IOException e) {
            // another fork still writes there, or it is gone
        }
    }

    private static void delete(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // still open: mvn clean removes it
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // gone already
        }
    }

    /** Records every Spring test context (for the shutdown hook) and gives it {@code drishti.test.dir}; one shared customizer, so caching is unchanged. */
    public static final class Contexts implements ContextCustomizerFactory {

        private static final ContextCustomizer CUSTOMIZER = Contexts::customize;

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
            root();                                     // sets the system property before any context prepares its environment
            return CUSTOMIZER;
        }

        private static void customize(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("drishti-test-run", java.util.Map.of(PROPERTY, ROOT.toString())));
            CONTEXTS.removeIf(ConfigurableApplicationContext::isClosed);      // evicted from Spring's cache: nothing left to wait for
            CONTEXTS.add(context);
        }
    }
}
