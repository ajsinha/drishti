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
package com.ash.drishti.server.api;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.SampleChecker;
import com.ash.drishti.engine.design.SampleChecker.Input;
import com.ash.drishti.engine.design.SampleChecker.Matrix;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * The server's side of {@link SampleChecker}: renders a Sutra against samples with the caller's field masks and open rights.
 * A sample that is a document is previewed as pasted (designing is open to every signed-in user; the kind is a label); a
 * sample that is a reference is read again through the sources, and a kind the caller may not open is {@code noAccess}.
 * Studio's test, the Build workbench's check and auto-design's pruning all come through the one checker.
 */
@Component
public class SampleCheckService {

    private final SampleChecker checker = new SampleChecker();
    private final ViewPipeline pipeline;
    private final Entitlements entitlements;
    private final JsonCodec codec;

    public SampleCheckService(ViewPipeline pipeline, Entitlements entitlements, JsonCodec codec) {
        this.pipeline = pipeline;
        this.entitlements = entitlements;
        this.codec = codec;
    }

    /**
     * @param sutra the checked Sutra
     * @param kind the entity kind a document sample stands for
     * @param inputs the samples: {@link Input#document()} or {@link Input#ref()}
     */
    public Matrix check(Sutra sutra, String kind, List<Input> inputs, Principal who) {
        Predicate<String> mayOpen = k -> entitlements.mayOpen(who, k);
        return checker.check(sutra.panels().stream().map(Panel::id).toList(), inputs, in -> render(sutra, kind, in, who, mayOpen));
    }

    private ViewModel render(Sutra sutra, String kind, Input in, Principal who, Predicate<String> mayOpen) {
        if (in.ref() != null) {
            if (!mayOpen.test(in.ref().kind())) {
                throw new SampleChecker.NoAccess(Entitlements.DENIED + ": you may not open " + in.ref().kind() + " entities");
            }
            return entitlements.restrict(who, pipeline.preview(sutra, in.ref(), AsOf.LATEST, entitlements.redactor(who), mayOpen));
        }
        EntityDocument doc = new EntityDocument(EntityRef.of(kind, "SAMPLE"), codec.read(in.document().toString()),
                new Provenance("builder sample JSON", 0, Instant.now(), false));
        return entitlements.restrict(who, pipeline.preview(Optional.of(sutra), doc, entitlements.redactor(who), mayOpen));
    }
}
