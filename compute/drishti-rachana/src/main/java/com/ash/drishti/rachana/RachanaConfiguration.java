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
package com.ash.drishti.rachana;

import com.ash.rachana.format.Formats;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-rachana}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RachanaProperties.class, LibraryProperties.class, com.ash.drishti.rachana.about.AboutProperties.class})
public class RachanaConfiguration {

    /**
     * The one compiler holder every reader takes: always the current instance's compiler, so a module started later is callable at once
     * and a module stopped is gone at once ({@link LiveCompiler}).
     */
    @Bean
    public LiveCompiler elCompiler(RachanaProperties props, LibraryProperties libraryProps) {
        DrishtiRachana.libraries(DrishtiLibraries.of(libraryProps));           // the Rachana libraries: bundled, folders, the lock file
        DrishtiRachana.configure(DrishtiRachana.optionsOf(props), null);       // the drishti profile's instance, from the settings (8.3b)
        return LiveCompiler.current();
    }

    /** The packs' about text (About this page, layer 1). */
    @Bean
    public com.ash.drishti.rachana.about.AboutCatalog aboutCatalog(com.ash.drishti.rachana.about.AboutProperties props, LiveCompiler elCompiler) {
        return new com.ash.drishti.rachana.about.AboutCatalog(props, elCompiler);
    }

    @Bean
    public Formats formats(RachanaProperties props) {
        return DrishtiRachana.formats();
    }

    /** Packs changed: the format tables read the bundled formats, each pack's formats now, then the site file (a bad file refuses the reload). */
    @Bean
    public com.ash.drishti.common.LiveSettings formatsReload() {
        return candidate -> DrishtiRachana.prepareFormats(org.springframework.boot.context.properties.bind.Binder.get(candidate)
                .bind("drishti.rachana.pack-formats-files", org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                .orElse(List.of()));
    }

    /**
     * The Sutras, with the Drishti UI kit ({@code drishti.ui-kit.dir}, RACHANA_REFERENCE.md) set first: its fragments load whatever packs are
     * enabled, so any screen may use them.
     */
    @Bean(destroyMethod = "close")
    public SutraRegistry sutraRegistry(RachanaProperties props, LiveCompiler elCompiler,
            @org.springframework.beans.factory.annotation.Value("${drishti.ui-kit.dir:${drishti.config.dir}/ui-kit}") String uiKitDir,
            @org.springframework.beans.factory.annotation.Value("${drishti.packs.dir:${drishti.config.dir}/packs}") String packsDir) {
        java.nio.file.Path kit = UiKitDir.resolve(uiKitDir, packsDir);         // else beside the packs folder (another working folder)
        if (!java.nio.file.Files.isDirectory(kit.resolve("fragments"))) {
            org.slf4j.LoggerFactory.getLogger(RachanaConfiguration.class).warn("the Drishti UI kit has no fragments folder at {} (drishti.ui-kit.dir, "
                    + "DRISHTI_UI_KIT_DIR): screens that use its fragments do not draw", kit);
        }
        DrishtiRachana.blocks().kit(kit);
        return new SutraRegistry(props, elCompiler);
    }

    /**
     * The under-construction registry (docs/admin/UNDER_CONSTRUCTION.md): {@code drishti.under-construction.file}, else
     * {@code under-construction.yaml} beside the packs folder; none when neither exists, or when the setting is {@code none}.
     */
    @Bean
    public UnderConstruction underConstruction(
            @org.springframework.beans.factory.annotation.Value("${drishti.under-construction.file:}") String file,
            @org.springframework.beans.factory.annotation.Value("${drishti.packs.dir:${drishti.config.dir}/packs}") String packsDir) {
        if ("none".equals(file)) {
            return UnderConstruction.none();                          // nothing registered (a test of the pages as their Sutras say)
        }
        java.nio.file.Path p = file == null || file.isBlank() ? null : java.nio.file.Path.of(file);
        if (p == null || !java.nio.file.Files.isRegularFile(p)) {
            java.nio.file.Path parent = java.nio.file.Path.of(packsDir).toAbsolutePath().normalize().getParent();
            java.nio.file.Path beside = parent == null ? null : parent.resolve("under-construction.yaml");
            p = beside != null && java.nio.file.Files.isRegularFile(beside) ? beside : p;
        }
        return UnderConstruction.load(p);
    }

    @Bean
    public SutraMatcher sutraMatcher(SutraRegistry registry, LiveCompiler elCompiler, Formats formats, UnderConstruction underConstruction) {
        return new SutraMatcher(registry, elCompiler, formats, underConstruction);
    }
}
