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
/**
 * The access registry (docs/architecture/ACCESS_MODEL.md): licence → module → lens → permission, decided in one place.
 *
 * <p><b>Why a module of its own.</b> Every layer asks it (the server at start, every API call, data, the console through the server,
 * assets, Help, the AI tools, exports, embeds, tokens), and later it runs as a service of its own. So it depends on nothing of Drishti and
 * on nothing but Jackson; it is plain Java (the server wires it in, {@code server.modules}); and nothing else reads the access model's files
 * (an architecture test in drishti-it).
 *
 * <ul>
 *   <li>{@link com.ash.drishti.registry.AccessRegistry}: the questions; {@link com.ash.drishti.registry.EmbeddedAccessRegistry} answers them
 *       in process from a cached snapshot (a {@code RemoteAccessRegistry} later, the same interface).</li>
 *   <li>{@link com.ash.drishti.registry.AccessStore}: read, change, watch; {@link com.ash.drishti.registry.YamlAccessStore} over the modules'
 *       shipped manifests, {@code config/modules/} and {@code config/access/} (a database store later, the same interface).</li>
 *   <li>The model: {@link com.ash.drishti.registry.ModuleManifest}, {@link com.ash.drishti.registry.Licence},
 *       {@link com.ash.drishti.registry.Lens}, {@link com.ash.drishti.registry.Role}, {@link com.ash.drishti.registry.Assignment}.</li>
 * </ul>
 */
package com.ash.drishti.registry;
