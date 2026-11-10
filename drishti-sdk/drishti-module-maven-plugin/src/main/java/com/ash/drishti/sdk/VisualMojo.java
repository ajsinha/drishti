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

import com.ash.drishti.module.tool.VisualTemplate;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * {@code mvn drishti:visual -Dname=lollipop [-Dtitle="Variant lollipop"]}: the custom-visual template. Adds the visual
 * {@code <module>.<name>} to the project: the module's Elements library (a kind of drishti-chart's registry, built from its primitives)
 * and its {@code visuals:} entry in {@code console/plugin.yaml} (DEVELOPER_GUIDE.md, "A module's own visual").
 */
@Mojo(name = "visual", requiresProject = true, threadSafe = true)
public final class VisualMojo extends ModuleMojo {

    /** The visual's name: lower-case letters, digits and dashes; the kind is {@code <module>.<name>}. */
    @Parameter(property = "name", required = true)
    String name;

    /** Its title in the gallery (default: the name). */
    @Parameter(property = "title")
    String title;

    @Override
    public void execute() throws MojoFailureException {
        try {
            for (Path p : VisualTemplate.add(source.toPath(), moduleId, name, title)) {
                getLog().info("wrote " + project.getBasedir().toPath().relativize(p));
            }
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
    }
}
