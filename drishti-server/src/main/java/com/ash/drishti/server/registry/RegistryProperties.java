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
package com.ash.drishti.server.registry;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where signed packs come from ({@code drishti.packs.registry}).
 *
 * @param url the registry: a folder, {@code file:} or {@code https:} URL holding {@code index.json} and the archives;
 *     empty means no registry
 * @param trustedKeys publisher name to Ed25519 public key (base64 of the DER public key, as {@code tools/packreg} prints
 *     it); a pack signed by anyone else is refused
 * @param maxArchiveMb largest archive accepted
 * @param maxUnpackedMb largest unpacked size accepted
 * @param allowHttp accept plain {@code http:} registries (only for testing; signatures still apply)
 */
@ConfigurationProperties("drishti.packs.registry")
public record RegistryProperties(String url, Map<String, String> trustedKeys, Integer maxArchiveMb, Integer maxUnpackedMb, Boolean allowHttp) {

    public RegistryProperties {
        url = url == null ? "" : url.trim();
        trustedKeys = trustedKeys == null ? Map.of() : Map.copyOf(trustedKeys);
        maxArchiveMb = maxArchiveMb == null ? 50 : maxArchiveMb;
        maxUnpackedMb = maxUnpackedMb == null ? 200 : maxUnpackedMb;
        allowHttp = allowHttp != null && allowHttp;
    }
}
