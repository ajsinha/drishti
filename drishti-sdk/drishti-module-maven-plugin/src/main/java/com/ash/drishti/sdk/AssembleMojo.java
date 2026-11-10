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

import com.ash.drishti.module.runtime.DmodManifest;
import com.ash.drishti.module.tool.ModuleAssembler;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code assemble}: packs {@code target/dmod/<id>-<version>.dmod} with its hash list and {@code .sha256}. The project's jar is the first of
 * {@code java.jars}; every other jar module.yaml names (more of the module's own in {@code java.jars}, third-party ones in {@code java.lib})
 * is taken from the project's compile and runtime dependencies by its file name ({@code commons-math3-3.6.1.jar}).
 */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresDependencyResolution = ResolutionScope.RUNTIME)
public final class AssembleMojo extends ModuleMojo {

    @Override
    public void execute() {
        File jar = project.getArtifact() == null ? null : project.getArtifact().getFile();
        DmodManifest m = ModuleAssembler.manifest(source.toPath());
        List<Path> more = new ArrayList<>();
        if (m.java() != null && project.getArtifacts() != null) {
            for (Artifact a : project.getArtifacts()) {
                File f = a.getFile();
                if (f != null && (Artifact.SCOPE_COMPILE.equals(a.getScope()) || Artifact.SCOPE_RUNTIME.equals(a.getScope()))
                        && (m.java().jars().indexOf(f.getName()) > 0 || m.java().lib().contains(f.getName()))) {
                    more.add(f.toPath());
                }
            }
        }
        Path target = target();
        Path dmod = ModuleAssembler.assemble(source.toPath(), jar == null ? null : jar.toPath(), more, target.resolve("dmod-stage"), target.resolve("dmod"));
        getLog().info("module package: " + dmod + " (" + (1 + more.size()) + " jars; development build: hashes only, signing comes with the publisher key)");
    }
}
