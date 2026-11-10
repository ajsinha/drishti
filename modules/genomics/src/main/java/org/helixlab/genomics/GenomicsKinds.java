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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.kernel.api.data.KindProvider;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The kinds the module computes from what the VCF reader read: {@code gene} (the gene file's fields with how many variants fall in it)
 * and {@code cohort} (its samples, the variants they carry, the genes those touch). The kernel routes them like any kind.
 */
public final class GenomicsKinds implements KindProvider {

    static final String VARIANT = "variant";
    static final String SAMPLE = "sample";
    static final String GENE = "gene";
    static final String COHORT = "cohort";

    private final GenomicsData data;

    public GenomicsKinds(GenomicsData data) {
        this.data = data;
    }

    @Override
    public String name() {
        return "genomics-kinds";
    }

    @Override
    public Set<String> kinds() {
        return Set.of(GENE, COHORT);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
        if (GENE.equals(ref.kind())) {
            return Optional.ofNullable(data.geneRow(ref.id())).map(r -> doc(ref, r));
        }
        if (COHORT.equals(ref.kind())) {
            return Optional.ofNullable(data.cohorts().get(ref.id())).map(r -> doc(ref, r));
        }
        return Optional.empty();
    }

    @Override
    public boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) {
        if (GENE.equals(kind)) {
            data.now().genes().keySet().stream().sorted().forEach(g -> sink.accept(doc(new EntityRef(kind, g), data.geneRow(g))));
            return true;
        }
        if (COHORT.equals(kind)) {
            data.cohorts().forEach((id, r) -> sink.accept(doc(new EntityRef(kind, id), r)));
            return true;
        }
        return false;
    }

    private EntityDocument doc(EntityRef ref, Map<String, Object> row) {
        return new EntityDocument(ref, DataNode.of(row), new Provenance(name(), 1, Instant.now(), false, null));
    }
}
