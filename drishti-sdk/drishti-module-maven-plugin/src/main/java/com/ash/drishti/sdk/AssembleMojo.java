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
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** {@code assemble}: packs {@code target/dmod/<id>-<version>.dmod} with its hash list and {@code .sha256}. */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class AssembleMojo extends ModuleMojo {

    @Override
    public void execute() {
        File jar = project.getArtifact() == null ? null : project.getArtifact().getFile();
        Path dmod = ModuleAssembler.assemble(source.toPath(), jar == null ? null : jar.toPath(), target());
        getLog().info("module package: " + dmod + " (development build: hashes only, signing comes with the publisher key)");
    }
}
