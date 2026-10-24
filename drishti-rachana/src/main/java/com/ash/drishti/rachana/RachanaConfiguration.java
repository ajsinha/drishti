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

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-rachana}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RachanaProperties.class, com.ash.drishti.rachana.about.AboutProperties.class})
public class RachanaConfiguration {

    @Bean
    public ElCompiler elCompiler(RachanaProperties props) {
        return new ElCompiler(props.expressionCacheSize(), props.expressionLimits());
    }

    /** The packs' about text (About this page, layer 1). */
    @Bean
    public com.ash.drishti.rachana.about.AboutCatalog aboutCatalog(com.ash.drishti.rachana.about.AboutProperties props, ElCompiler elCompiler) {
        return new com.ash.drishti.rachana.about.AboutCatalog(props, elCompiler);
    }

    @Bean
    public Formats formats(RachanaProperties props) {
        return Formats.load(props.formatsFile(), props.packFormatsFiles());
    }

    /**
     * The Sutras, with the Drishti UI kit ({@code drishti.ui-kit.dir}, SUTRA_NEXT.md) set first: its fragments load whatever packs are
     * enabled, so any screen may use them.
     */
    @Bean(destroyMethod = "close")
    public SutraRegistry sutraRegistry(RachanaProperties props, ElCompiler elCompiler,
            @org.springframework.beans.factory.annotation.Value("${drishti.ui-kit.dir:./config/ui-kit}") String uiKitDir,
            @org.springframework.beans.factory.annotation.Value("${drishti.packs.dir:./config/packs}") String packsDir) {
        java.nio.file.Path kit = UiKitDir.resolve(uiKitDir, packsDir);         // else beside the packs folder (another working folder)
        if (!java.nio.file.Files.isDirectory(kit.resolve("fragments"))) {
            org.slf4j.LoggerFactory.getLogger(RachanaConfiguration.class).warn("the Drishti UI kit has no fragments folder at {} (drishti.ui-kit.dir, "
                    + "DRISHTI_UI_KIT_DIR): screens that use its fragments do not draw", kit);
        }
        com.ash.drishti.rachana.parse.SutraBlocks.kit(kit);
        return new SutraRegistry(props, elCompiler);
    }

    @Bean
    public SutraMatcher sutraMatcher(SutraRegistry registry, ElCompiler elCompiler, Formats formats) {
        return new SutraMatcher(registry, elCompiler, formats);
    }
}
