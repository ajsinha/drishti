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
package org.helixlab.genomics;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The VCF reader: a kernel connector extension (the plugin API's {@link SourcePlugin}) that serves the kinds {@code variant} and
 * {@code sample} from VCF files. It ships inside the module, not in the site's {@code lib/} folder: it reads a format only this module's
 * kinds use, and it must go with the module (switched off or removed, the kernel stops routing it and its class loader is collected).
 * The kernel starts it with two settings: {@code module-dir} (the installed module: its synthetic samples under {@code data/samples/})
 * and {@code data-dir} (the module's own data folder: a site drops its VCF files in {@code vcf/}).
 */
public final class VcfConnector implements SourcePlugin {

    static final String NAME = "genomics-vcf";

    private final GenomicsData data;

    public VcfConnector(GenomicsData data) {
        this.data = data;
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(NAME, "0.1.0", Set.of(GenomicsKinds.VARIANT, GenomicsKinds.SAMPLE), SourceCapabilities.FETCH_ONLY);
    }

    @Override
    public void start(SourceContext context) {
        Map<String, String> s = context.settings();
        List<Path> folders = new ArrayList<>();
        if (s.get("module-dir") != null) {
            folders.add(Path.of(s.get("module-dir")).resolve("data").resolve("samples"));
        }
        if (s.get("data-dir") != null) {
            folders.add(Path.of(s.get("data-dir")).resolve("vcf"));
        }
        if (s.get("dir") != null) {
            folders.add(Path.of(s.get("dir")));
        }
        data.load(folders);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) {
        GenomicsData.Snapshot now = data.now();
        if (GenomicsKinds.VARIANT.equals(ref.kind())) {
            return Optional.ofNullable(now.variants().get(ref.id())).map(v -> doc(ref, GenomicsData.variantRow(v)));
        }
        if (GenomicsKinds.SAMPLE.equals(ref.kind())) {
            return Optional.ofNullable(now.samples().get(ref.id())).map(x -> doc(ref, data.sampleRow(x)));
        }
        return Optional.empty();
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        GenomicsData.Snapshot now = data.now();
        if (GenomicsKinds.VARIANT.equals(kind)) {
            now.variants().values().forEach(v -> sink.accept(doc(new EntityRef(kind, v.id()), GenomicsData.variantRow(v))));
            return true;
        }
        if (GenomicsKinds.SAMPLE.equals(kind)) {
            now.samples().values().forEach(x -> sink.accept(doc(new EntityRef(kind, x.id()), data.sampleRow(x))));
            return true;
        }
        return false;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        String q = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        List<EntityHit> out = new ArrayList<>();
        if (GenomicsKinds.VARIANT.equals(kind)) {
            data.now().variants().values().stream()
                    .filter(v -> v.id().toUpperCase(Locale.ROOT).contains(q) || v.info().getOrDefault("GENE", "").toUpperCase(Locale.ROOT).contains(q))
                    .limit(limit).forEach(v -> out.add(new EntityHit(new EntityRef(kind, v.id()), v.id(), v.info().getOrDefault("GENE", "") + " " + v.chrom() + ":" + v.pos())));
        }
        return out;
    }

    @Override
    public String health() {
        GenomicsData.Snapshot now = data.now();
        return now.variants().size() + " variants, " + now.samples().size() + " samples from " + now.files().size() + " files"
                + (now.problems().isEmpty() ? "" : "; " + now.problems().size() + " lines skipped");
    }

    @Override
    public void close() {
        data.clear();
    }

    private static EntityDocument doc(EntityRef ref, Map<String, Object> row) {
        return new EntityDocument(ref, DataNode.of(row), new Provenance(NAME, 1, Instant.now(), false, null));
    }
}
