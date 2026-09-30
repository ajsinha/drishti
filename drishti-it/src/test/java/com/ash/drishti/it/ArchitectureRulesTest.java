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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Structural rules: one-way module dependencies, a Spring-free plugin SPI, constructor injection. */
@AnalyzeClasses(packages = "com.ash.drishti", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule apiIsSpringFree = noClasses()
            .that().resideInAPackage("com.ash.drishti.api..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    @ArchTest
    static final ArchRule apiDependsOnNothingInternal = noClasses()
            .that().resideInAPackage("com.ash.drishti.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.ash.drishti.common..", "com.ash.drishti.sutra..", "com.ash.drishti.engine..",
                    "com.ash.drishti.server..");

    @ArchTest
    static final ArchRule lowerLayersDoNotSeeEngine = noClasses()
            .that().resideInAnyPackage("com.ash.drishti.common..", "com.ash.drishti.sutra..",
                    "com.ash.drishti.inference..", "com.ash.drishti.graph..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.engine..", "com.ash.drishti.server..");

    @ArchTest
    static final ArchRule engineDoesNotSeeServer = noClasses()
            .that().resideInAPackage("com.ash.drishti.engine..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.server..");

    @ArchTest
    static final ArchRule controllersOnlyInServer = noClasses()
            .that().resideOutsideOfPackage("com.ash.drishti.server..")
            .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noSerializable = noClasses()
            .that().areNotEnums().and().areNotAssignableTo(Throwable.class)
            .should().implement(java.io.Serializable.class)
            .allowEmptyShould(true);
}
