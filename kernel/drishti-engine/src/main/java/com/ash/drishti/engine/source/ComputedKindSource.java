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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.kernel.api.data.KindProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The kernel's side of a module's computed kind (docs/architecture/KERNEL_AND_MODULES.md section 5.7): a {@link KindProvider} routed like any
 * source, under the provider's name, serving exactly the kinds it computes (never a connector file's {@code kinds:} nor a binding). Source
 * plugins are kernel only; a module brings providers.
 */
public final class ComputedKindSource implements SourcePlugin {

    private final KindProvider provider;

    public ComputedKindSource(KindProvider provider) {
        this.provider = provider;
    }

    /** A new instance of a discovered plugin for one connector: a computed kind's source wraps a new provider of the same class. */
    static SourcePlugin fresh(SourcePlugin proto) throws ReflectiveOperationException {
        if (proto instanceof ComputedKindSource c) {
            return new ComputedKindSource(c.provider.getClass().getDeclaredConstructor().newInstance());
        }
        return proto.getClass().getDeclaredConstructor().newInstance();
    }

    /** The module's provider behind this source. */
    public KindProvider provider() {
        return provider;
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(provider.name(), "1.0", provider.kinds(), new SourceCapabilities(false, false, true, true));
    }

    @Override
    public void start(SourceContext context) {
        // a computed kind has no settings: its inputs are other kinds, read through the kernel
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        return provider.fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        return provider.fetch(ref, asOf);
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        return provider.documents(kind, asOf, sink);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return provider.search(kind, text, limit, AsOf.LATEST);
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        return provider.search(kind, text, limit, asOf);
    }

    @Override
    public Optional<LocalDate> latestDate(String kind) {
        return provider.latestDate(kind);
    }

    @Override
    public Optional<String> dataVersion(String kind) {
        return provider.dataVersion(kind, null);
    }

    @Override
    public Optional<String> dataVersion(String kind, LocalDate date) {
        return provider.dataVersion(kind, date);
    }

    @Override
    public boolean dependsOn(String kind) {
        return provider.dependsOn(kind);
    }

    @Override
    public Map<String, Object> cacheStats() {
        return provider.stats();
    }

    @Override
    public void close() {
        // the module owns the provider's state
    }
}
