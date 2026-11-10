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
package com.ash.drishti.sdk;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** What every goal knows of the project. */
abstract class ModuleMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** The module's files in the {@code .dmod} layout (module.yaml first). */
    @Parameter(property = "drishti.dmod.source", defaultValue = "${project.basedir}/src/dmod")
    File source;

    /** The module's id; its manifest must say the same. */
    @Parameter(property = "drishti.module.id", defaultValue = "${project.artifactId}")
    String moduleId;

    Path target() {
        return Path.of(project.getBuild().getDirectory());
    }
}
