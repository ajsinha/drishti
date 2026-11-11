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
                    "com.ash.drishti.common..", "com.ash.drishti.rachana..", "com.ash.drishti.engine..",
                    "com.ash.drishti.server..");

    @ArchTest
    static final ArchRule lowerLayersDoNotSeeEngine = noClasses()
            .that().resideInAnyPackage("com.ash.drishti.common..", "com.ash.drishti.rachana..",
                    "com.ash.drishti.inference..", "com.ash.drishti.graph..", "com.ash.drishti.identity..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.engine..", "com.ash.drishti.server..");

    @ArchTest
    static final ArchRule engineDoesNotSeeServer = noClasses()
            .that().resideInAPackage("com.ash.drishti.engine..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.server..");

    /** The AI layer depends one way: on the SPI and common only, never on the engine, the identity layer or the server. */
    @ArchTest
    static final ArchRule aiLayerDependsOneWay = noClasses()
            .that().resideInAPackage("com.ash.drishti.ai..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.engine..", "com.ash.drishti.server..",
                    "com.ash.drishti.identity..", "com.ash.drishti.rachana..", "com.ash.drishti.packs..");

    /** Drishti*net's core (membership, the cluster key, digests, routing) knows nothing of Spring, the engine or the server: the server wires it in. */
    @ArchTest
    static final ArchRule netCoreDependsOneWay = noClasses()
            .that().resideInAPackage("com.ash.drishti.net..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.engine..", "com.ash.drishti.server..", "org.springframework..",
                    "com.ash.drishti.identity..", "com.ash.drishti.rachana..", "com.ash.drishti.packs..");

    /**
     * The table layer (drishti-tables, docs/architecture/ARCHITECTURE.md §28 Q30) depends one way: on Rachana (paths and expressions) and the pivot engine
     * (the function catalogue, the compiler), never on BI or anything else of the server, and it is plain Java (the server wires it in).
     * BI depends on the layer, never the reverse.
     */
    @ArchTest
    static final ArchRule tablesDependOnRachanaAndTheEngineNeverOnBi = noClasses()
            .that().resideInAPackage("com.ash.drishti.tables..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.server..", "com.ash.drishti.engine..", "com.ash.drishti.compute..",
                    "com.ash.drishti.identity..", "com.ash.drishti.packs..", "com.ash.drishti.ai..", "org.springframework..")
            .because("the table layer is beneath BI: BI reads tables and models, the layer never reads BI (docs/architecture/ARCHITECTURE.md §28)");

    /** Nothing beneath the table layer sees it: Rachana, the pivot engine, the SPI and common know nothing of tables. */
    @ArchTest
    static final ArchRule nothingBeneathTheTableLayerSeesIt = noClasses()
            .that().resideInAnyPackage("com.ash.drishti.rachana..", "com.ash.drishti.bicompute..", "com.ash.drishti.api..", "com.ash.drishti.common..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.tables..")
            .because("the layer depends on Rachana and the engine, never the reverse");

    /** The in-memory lake is plain Java: no Spring, nothing of the server (the server wires it in, server.memlake). */
    @ArchTest
    static final ArchRule memoryLakeIsPlainJava = noClasses()
            .that().resideInAPackage("com.ash.drishti.memlake..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.server..", "org.springframework..");

    /** The data inbox is plain Java and never names the server (DATA_LAKE_GUIDE.md): the server wires it in, server.dataload. */
    @ArchTest
    static final ArchRule dataInboxIsPlainJava = noClasses()
            .that().resideInAPackage("com.ash.drishti.dataload..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.server..", "org.springframework..");

    /**
     * The data inbox never writes a table itself: only drishti-lakeload does (its loader, started as a child process). No lake engine,
     * no table format, no storage of its own.
     */
    @ArchTest
    static final ArchRule dataInboxWritesNoTable = noClasses()
            .that().resideInAPackage("com.ash.drishti.dataload..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.deltalake..", "com.ash.drishti.iceberg..", "com.ash.drishti.lakeio..",
                    "com.ash.drishti.memlake..", "io.delta..", "org.apache.iceberg..", "org.apache.parquet..", "org.apache.arrow..");

    /** The lake engines and connectors reach mem:// through lakeio's StorageProviders, never through the in-memory lake's module. */
    @ArchTest
    static final ArchRule lakeReadersDoNotSeeTheMemoryLake = noClasses()
            .that().resideInAnyPackage("com.ash.drishti.lakeio..", "com.ash.drishti.deltalake..", "com.ash.drishti.iceberg..",
                    "com.ash.drishti.plugin.delta..", "com.ash.drishti.plugin.iceberg..", "com.ash.drishti.api..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.memlake..");

    /**
     * The native BLAS binding is named in one class (HIGH_PERFORMANCE_NATIVE.md section 6.3): every caller uses LinearAlgebra, so the binding
     * can change there alone and nothing else can reach native code around GuardedBlas's checks.
     */
    @ArchTest
    static final ArchRule onlyNativeBlasNamesTheBinding = noClasses()
            .that().doNotHaveFullyQualifiedName("com.ash.drishti.compute.linalg.NativeBlas")
            .should().dependOnClassesThat().resideInAnyPackage("org.bytedeco..", "dev.ludovic.netlib..");

    @ArchTest
    static final ArchRule controllersOnlyInServer = noClasses()
            .that().resideOutsideOfPackages("com.ash.drishti.server..", "com.ash.drishti.banking..")
            .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .allowEmptyShould(true);

    // ------------------------------------------------------------------------------------------------ domain modules (DOMAIN_MODULES.md section 4)

    /** The core never imports a domain: nothing outside a domain's packages depends on one inside them (the SPIs go the other way). */
    @ArchTest
    static final ArchRule coreNeverImportsADomain = noClasses()
            .that().resideOutsideOfPackages("com.ash.drishti.banking..", "com.ash.drishti.economy..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.banking..", "com.ash.drishti.economy..")
            .because("the core knows no industry: a domain registers with the core through its SPIs (docs/architecture/DOMAIN_MODULES.md)");

    /** Domains never import each other. */
    @ArchTest
    static final ArchRule bankingDoesNotSeeAnotherDomain = noClasses()
            .that().resideInAPackage("com.ash.drishti.banking..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.economy..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule economyDoesNotSeeAnotherDomain = noClasses()
            .that().resideInAPackage("com.ash.drishti.economy..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ash.drishti.banking..")
            .allowEmptyShould(true);

    /** A domain's compute is pure maths: no Spring, nothing of the server or of the domain's services (drishti-compute-banking). */
    @ArchTest
    static final ArchRule domainComputeIsPlainJava = noClasses()
            .that().resideInAPackage("com.ash.drishti.banking.compute..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.ash.drishti.server..", "com.ash.drishti.banking.var..",
                    "com.ash.drishti.banking.exposure..", "com.ash.drishti.banking.frtb..", "com.ash.drishti.banking.simm..", "com.ash.drishti.banking.official..");

    /** Only drishti-registry reads the access model (ACCESS_MODEL.md section 6): every other class asks the AccessRegistry. */
    @ArchTest
    static final ArchRule onlyTheRegistryReadsTheAccessModel = noClasses()
            .that().resideOutsideOfPackage("com.ash.drishti.registry..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.ash.drishti.registry.YamlAccessStore")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.ash.drishti.registry.AccessModel")
            .because("the access model (module manifests, config/modules, config/access) has one reader: a database store and a remote registry "
                    + "replace it behind the same interfaces");

    /** The registry is plain Java: nothing of Drishti, no Spring (it becomes a service of its own later). */
    @ArchTest
    static final ArchRule registryIsPlainJava = noClasses()
            .that().resideInAPackage("com.ash.drishti.registry..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.ash.drishti.server..", "com.ash.drishti.common..",
                    "com.ash.drishti.engine..", "com.ash.drishti.packs..");

    @ArchTest
    static final ArchRule noFieldInjection = fields()
            .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noSerializable = noClasses()
            .that().areNotEnums().and().areNotAssignableTo(Throwable.class)
            .and().areNotAnnotatedWith(jakarta.persistence.Embeddable.class)   // JPA requires composite keys to be Serializable
            .and().areNotAssignableTo("org.apache.iceberg.io.FileIO")            // Iceberg requires its FileIO (and the client supplier it holds) to be Serializable
            .and().areNotAssignableTo("org.apache.iceberg.util.SerializableSupplier")
            .should().implement(java.io.Serializable.class)
            .allowEmptyShould(true);

    /** Every temporary file or folder of the server is made by TempFiles (one per-process folder, cleaned up), never by the JDK directly. */
    @ArchTest
    static final ArchRule temporaryFilesComeFromTempFiles = noClasses()
            .that().doNotHaveFullyQualifiedName("com.ash.drishti.common.TempFiles")
            .and().resideOutsideOfPackage("com.ash.drishti.testkit..")
            // the standalone module tool (java -jar drishti-module-tool.jar) runs outside any server and its data folder: the system temp is its place
            .and().resideOutsideOfPackage("com.ash.drishti.module.tool..")
            .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.base.DescribedPredicate.describe("is Files.createTempFile, Files.createTempDirectory or File.createTempFile",
                            (com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget t) -> (t.getOwner().isEquivalentTo(java.nio.file.Files.class)
                                    || t.getOwner().isEquivalentTo(java.io.File.class)) && t.getName().startsWith("createTemp"))));
}
