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
package com.ash.drishti.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The architecture rules that name a module's packages (they moved here from {@code drishti-it} with E1: the kernel's rule project no longer
 * has a module on its class path). The rules read the modules' jars (packaging/dist) as files, so no module is on this project's class path: the servers of its other tests
 * run on the built packages.
 */
class ModuleArchitectureRulesTest {

    /** The classes of the modules' jars (packaging/dist/target/module-jars): imported from the files, never from this project's class path. */
    static final JavaClasses MODULES = imported();

    static JavaClasses imported() {
        try {
            List<java.util.jar.JarFile> files = new java.util.ArrayList<>();
            for (Path p : jars()) {
                files.add(new java.util.jar.JarFile(p.toFile()));
            }
            return new ClassFileImporter().importJars(files);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<Path> jars() {
        Path dir = Path.of(System.getProperty("drishti.module.jars"));
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("the modules' jars are built by packaging/dist (build all): " + dir, e);
        }
    }

    @Test
    void theModulesHaveTheirClasses() {
        assertThat(MODULES.stream().map(c -> c.getPackageName().split("\\."))
                .filter(p -> p.length > 3 && p[2].equals("drishti")).map(p -> p[3]).distinct().toList()).contains("banking", "trading", "marketdata");
    }

    @Test
    void everyRuleHolds() {
        List<ArchRule> rules = List.of(bankingDoesNotSeeAnotherDomain, economyDoesNotSeeAnotherDomain, domainComputeIsPlainJava, coreNeverImportsADomain);
        rules.forEach(r -> r.check(MODULES));
    }

    /** Domains never import each other. */
    static final ArchRule bankingDoesNotSeeAnotherDomain = noClasses()
            .that().resideInAPackage("com.ash.drishti.banking..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.economy..")
            .allowEmptyShould(true);

    static final ArchRule economyDoesNotSeeAnotherDomain = noClasses()
            .that().resideInAPackage("com.ash.drishti.economy..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.banking..")
            .allowEmptyShould(true);

    /** A domain's compute is pure maths: no Spring, nothing of the server or of the domain's services (drishti-compute-banking). */
    static final ArchRule domainComputeIsPlainJava = noClasses()
            .that().resideInAPackage("com.ash.drishti.banking.compute..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.ash.drishti.server..", "com.ash.drishti.banking.var..",
                    "com.ash.drishti.banking.exposure..", "com.ash.drishti.banking.frtb..", "com.ash.drishti.banking.simm..", "com.ash.drishti.banking.official..");

    /** The rule of the core, over the classes of the modules: nothing outside a domain's packages depends on one inside them. */
    static final ArchRule coreNeverImportsADomain = noClasses()
            .that().resideOutsideOfPackages("com.ash.drishti.banking..", "com.ash.drishti.economy..", "com.ash.drishti.trading..", "com.ash.drishti.marketdata..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.banking..", "com.ash.drishti.economy..")
            .because("the core knows no industry: a domain registers with the core through its SPIs (docs/architecture/KERNEL_AND_MODULES.md)")
            .allowEmptyShould(true);
}
