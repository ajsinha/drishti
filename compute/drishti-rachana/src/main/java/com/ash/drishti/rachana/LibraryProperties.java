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

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.rachana.libraries.*}: where Drishti's Rachana instance finds Rachana's fragment libraries (docs/guides/RACHANA_REFERENCE.md,
 * "Rachana libraries").
 *
 * @param bundled whether the libraries carried by the Rachana jars ({@code core}, {@code finance}) are offered (default true)
 * @param dirs folders holding more libraries, each an unpacked library folder per name ({@code <dir>/<name>/}) or {@code <name>-<version>.rlib}
 *     archives; every entry is absolute or anchored to a named root ({@code ${home}}, {@code ${config}}, {@code ${data}}), a bare relative
 *     value is under the install folder
 * @param lockFile Rachana's lock file ({@code rachana.lock}) that pins each library's version and hash; default {@code <config>/rachana.lock}
 *     when that file exists, else the libraries are used unpinned
 */
@ConfigurationProperties("drishti.rachana.libraries")
public record LibraryProperties(Boolean bundled, List<String> dirs, String lockFile) {

    public LibraryProperties {
        bundled = bundled == null ? Boolean.TRUE : bundled;
        dirs = dirs == null ? List.of() : List.copyOf(dirs);
        lockFile = lockFile == null || lockFile.isBlank() ? null : lockFile.strip();
    }

    /** The defaults: the bundled libraries, no more folders, no lock file named. */
    public static LibraryProperties defaults() {
        return new LibraryProperties(null, null, null);
    }
}
