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
package com.ash.drishti.server.deploy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin → Packs → Deploy archive ({@code drishti.packs.deploy.*}).
 *
 * @param maxArchiveMb largest archive accepted (413 above it)
 * @param maxUnpackedMb most the archive may unpack to (a zip bomb stops here)
 * @param maxFiles most files in one archive
 * @param keepVersions previous versions kept per pack for a rollback
 * @param requireSignature refuse an archive that is not signed by a trusted publisher (drishti.packs.registry.trusted-keys)
 * @param requireManifest refuse an archive without MANIFEST.json (the checksums are the point of an archive)
 * @param stagingMinutes how long a verified upload waits for its confirmation
 * @param historyFile where deployments and rollbacks are recorded (JSON lines)
 * @param probeDates business dates listed per kind by Test connection
 * @param probeTimeoutSeconds how long Test connection waits for a source
 */
@ConfigurationProperties("drishti.packs.deploy")
public record DeployProperties(Integer maxArchiveMb, Integer maxUnpackedMb, Integer maxFiles, Integer keepVersions, Boolean requireSignature,
        Boolean requireManifest, Integer stagingMinutes, String historyFile, Integer probeDates, Integer probeTimeoutSeconds) {

    public DeployProperties {
        maxArchiveMb = maxArchiveMb == null ? 50 : maxArchiveMb;
        maxUnpackedMb = maxUnpackedMb == null ? 200 : maxUnpackedMb;
        maxFiles = maxFiles == null ? 10_000 : maxFiles;
        keepVersions = keepVersions == null ? 5 : Math.max(1, keepVersions);
        requireSignature = requireSignature != null && requireSignature;
        requireManifest = requireManifest == null || requireManifest;
        stagingMinutes = stagingMinutes == null ? 30 : stagingMinutes;
        historyFile = historyFile == null || historyFile.isBlank() ? "./data/packs/deploy-history.jsonl" : historyFile;
        probeDates = probeDates == null ? 3 : probeDates;
        probeTimeoutSeconds = probeTimeoutSeconds == null ? 30 : probeTimeoutSeconds;
    }
}
