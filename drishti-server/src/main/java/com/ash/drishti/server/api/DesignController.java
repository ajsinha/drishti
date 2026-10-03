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
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.design.Design;
import com.ash.drishti.engine.design.SampleChecker;
import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.SchemaSampler;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeException;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.identity.design.StoredDesign.SampleInfo;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.design.ops.Op;
import com.ash.drishti.rachana.design.ops.OpApplier;
import com.ash.drishti.rachana.design.ops.OpResult;
import com.ash.drishti.rachana.design.ops.Ops;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.design.DesignRebase;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Build workbench Designs, {@code /api/v1/builder/designs}: a Design is one persisted piece of work (samples, a Sutra, notes)
 * owned by the signed-in user, who alone can reach it (others get 404 {@code DRS-5006}). Open to every signed-in user:
 * designing is not saving. Samples are documents you brought, references to stored entities (re-read each time with the
 * caller's current rights and field masks, never snapshots) or synthetic documents generated from a schema. Limits answer
 * {@code 413 DRS-5005}. Sample contents are never logged, only counts.
 */
@RestController
@RequestMapping("/api/v1/builder/designs")
public class DesignController {

    private static final Logger LOG = LoggerFactory.getLogger(DesignController.class);
    private static final int STRING_MAX_MB = 20;
    private static final Pattern KIND = AutoDesigner.KIND;
    private static final Duration FETCH = Duration.ofSeconds(10);

    private final DesignService designs;
    private final ShapeService shapes;
    private final AutoDesigner designer;
    private final BuilderController builder;
    private final ViewPipeline pipeline;
    private final SutraRegistry sutras;
    private final SourceRouter router;
    private final Entitlements entitlements;
    private final JsonCodec codec;
    private final BuilderProperties limits;
    private final ObjectMapper mapper;
    private final SampleCheckService checks;
    private final OpApplier applier = new OpApplier();
    private final DesignRebase rebase;

    public DesignController(DesignService designs, ShapeService shapes, AutoDesigner designer, BuilderController builder, ViewPipeline pipeline,
            SutraRegistry sutras, SourceRouter router, Entitlements entitlements, JsonCodec codec, SampleCheckService checks, DesignRebase rebase) {
        this.checks = checks;
        this.rebase = rebase;
        this.designs = designs;
        this.shapes = shapes;
        this.designer = designer;
        this.builder = builder;
        this.pipeline = pipeline;
        this.sutras = sutras;
        this.router = router;
        this.entitlements = entitlements;
        this.codec = codec;
        this.limits = shapes.limits();
        this.mapper = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(limits.maxDepth() + 3).maxStringLength(STRING_MAX_MB * 1024 * 1024).build()).build());
    }

    // ---- designs -------------------------------------------------------------------------------------------------

    @GetMapping
    public ObjectNode list(@RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        ObjectNode out = mapper.createObjectNode();
        ArrayNode rows = out.putArray("designs");
        designs.list(who.user()).forEach(s -> rows.add(view(s, false)));
        var p = designs.limits();
        out.putObject("limits").put("maxPerUser", p.maxPerUser()).put("maxSamples", p.maxSamples()).put("maxMb", p.maxMb())
                .put("maxUserMb", p.maxUserMb()).put("scratchHours", p.scratchTtl().toHours()).put("namedDays", p.namedTtl().toDays())
                .put("warnDays", p.warnAfter().toDays()).put("maxScratch", p.maxScratch()).put("maxSutraKb", p.maxSutraKb())
                .put("maxNotesKb", p.maxNotesKb()).put("maxTestsKb", p.maxTestsKb());
        return out;
    }

    /** Body {@code {name, kind, base, sutra, notes}}: all optional. {@code base} ({@code name@version}) without {@code sutra} copies that Sutra's text. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode create(HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        String base = text(b, "base");
        String sutra = text(b, "sutra");
        if (base != null && !base.isBlank() && (sutra == null || sutra.isBlank())) {
            sutra = registrySource(base);
        }
        String kind = kind(text(b, "kind"));
        StoredDesign d = designs.create(who.user(), text(b, "name"), kind, base, sutra, text(b, "notes"));
        LOG.info("design {} created ({})", d.id, d.scratch ? "scratch" : "named");
        return view(designs.summary(d), true);
    }

    @GetMapping("/{id}")
    public ObjectNode read(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return view(designs.summary(designs.open(who.user(), id)), true);
    }

    /** Body {@code {name, kind, notes, sutra, tests}}: what is present is changed. A changed {@code sutra} is a new revision. */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode update(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who)
            throws IOException {
        JsonNode b = body(request);
        List<JsonNode> tests = null;
        if (b.has("tests")) {
            if (!b.get("tests").isArray()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'tests' must be a list");
            }
            tests = new ArrayList<>();
            b.get("tests").forEach(tests::add);
        }
        String kind = b.has("kind") ? kind(text(b, "kind")) : null;
        StoredDesign d = designs.update(who.user(), id, new DesignService.Patch(text(b, "name"), kind, text(b, "notes"), text(b, "sutra"), tests));
        return view(designs.summary(d), true);
    }

    /** {@code DELETE /builder/designs?scratch=true}: deletes all of your scratch (unnamed) designs; answers how many. Without the flag: 400. */
    @DeleteMapping
    public ObjectNode deleteScratch(@RequestParam(required = false) boolean scratch, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        if (!scratch) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send ?scratch=true to delete all your scratch designs; named designs are deleted one by one");
        }
        return mapper.createObjectNode().put("deleted", designs.deleteScratch(who.user()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        designs.delete(who.user(), id);
    }

    /** Body {@code {name}} (optional): a named copy with its samples. */
    @PostMapping(path = "/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode duplicate(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who)
            throws IOException {
        JsonNode b = request.getContentLengthLong() == 0 ? mapper.createObjectNode() : body(request);
        return view(designs.summary(designs.duplicate(who.user(), id, text(b, "name"))), true);
    }

    // ---- samples -------------------------------------------------------------------------------------------------

    /**
     * Body: {@code samples} ({@code [{name, document}]}, as {@code /builder/shape}), and/or {@code refs} ({@code {kind, ids}} or
     * {@code {kind, count}}: stored entities, kept as references), and/or {@code schema} (a plain JSON Schema or a shape.json) with
     * {@code count}: synthetic documents generated from it, labelled synthetic. A sample of an existing name replaces it.
     */
    @PostMapping(path = "/{id}/samples", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode addSamples(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who)
            throws IOException {
        designs.get(who.user(), id);                          // not yours: 404 before anything else is said
        JsonNode b = body(request);
        List<DesignService.NewSample> add = new ArrayList<>();
        List<String> sampled = new ArrayList<>();
        if (b.has("samples")) {
            if (!b.get("samples").isArray()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'samples' must be a list of {name, document}");
            }
            int n = 0;
            for (JsonNode s : b.get("samples")) {
                n++;
                String name = text(s, "name");
                String label = name == null || name.isBlank() ? "sample " + n : "'" + name + "'";
                if (!s.has("document")) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, label + " has no 'document'");
                }
                String json = mapper.writeValueAsString(s.get("document"));
                if (json.length() > limits.maxFileBytes()) {
                    throw new ShapeException(label + " is over the limit of " + limits.maxFileMb() + " MB per document (drishti.builder.max-file-mb)");
                }
                add.add(new DesignService.NewSample(name, StoredDesign.DOCUMENT, null, null, json));
            }
        }
        if (b.has("refs")) {
            addRefs(b.get("refs"), who, add);
        }
        if (b.path("schema").isObject()) {
            int count = Math.max(1, Math.min(b.path("count").asInt(5), Math.min(designs.limits().maxSamples(), limits.maxSamples())));
            SchemaSampler sampler = new SchemaSampler(b.get("schema"), SchemaSampler.Limits.of(limits));
            List<JsonNode> docs = sampler.documents(count);
            sampled.addAll(sampler.problems());
            for (int i = 0; i < docs.size(); i++) {
                add.add(new DesignService.NewSample("synthetic-" + (i + 1), StoredDesign.SYNTHETIC, null, null, mapper.writeValueAsString(docs.get(i))));
            }
        }
        if (add.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send 'samples' ([{name, document}]), 'refs' ({kind, ids|count}) or 'schema'");
        }
        ObjectNode out = view(designs.summary(designs.addSamples(who.user(), id, add)), true);
        if (!sampled.isEmpty()) {
            sampled.forEach(out.putArray("problems")::add);      // what the synthetic sampler clamped (drishti.builder.sample-max-*)
        }
        return out;
    }

    private void addRefs(JsonNode refs, Principal who, List<DesignService.NewSample> add) {
        String kind = text(refs, "kind");
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'refs.kind' is required: the kind of the stored entities");
        }
        entitlements.requireOpen(who, kind);                  // a stored entity is data, not design
        List<String> ids = new ArrayList<>();
        if (refs.path("ids").isArray()) {
            refs.get("ids").forEach(i -> ids.add(i.asText()));
        } else {
            int count = Math.max(1, Math.min(refs.path("count").asInt(5), designs.limits().maxSamples()));
            router.search(kind, "", count, FETCH).forEach(h -> ids.add(h.ref().id()));
            if (ids.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no " + kind + " entities to take");
            }
        }
        for (String eid : ids) {
            if (eid == null || eid.isBlank() || eid.length() > 128) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'refs.ids' are entity ids");
            }
            add.add(new DesignService.NewSample(kind + " " + eid, StoredDesign.REF, kind, eid, null));
        }
    }

    @DeleteMapping("/{id}/samples")
    public ObjectNode removeSample(@PathVariable String id, @RequestParam String name, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        return view(designs.summary(designs.removeSample(who.user(), id, name)), true);
    }

    /** A kept sample document (a brought or synthetic one); a reference has none: open its entity instead. */
    @GetMapping("/{id}/samples/document")
    public JsonNode document(@PathVariable String id, @RequestParam String name, @RequestAttribute(Principal.ATTRIBUTE) Principal who)
            throws IOException {
        SampleInfo info = designs.get(who.user(), id).sample(name);
        if (info == null) {
            throw new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "no sample '" + name + "' in this design");
        }
        if (StoredDesign.REF.equals(info.type())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' is a reference to " + info.refKind() + " " + info.refId()
                    + ": it keeps no document; open the entity");
        }
        return mapper.readTree(designs.sample(who.user(), id, name));
    }

    // ---- shape, preview, auto-design -----------------------------------------------------------------------------

    /** The shape of the Design's samples ({@code {schema, roles, report}} as {@code /builder/shape}), plus what was {@code skipped} and why. */
    @PostMapping("/{id}/shape")
    public ObjectNode shape(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        Resolved r = resolve(who, designs.get(who.user(), id));
        if (r.samples.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design has no readable sample: add files, a schema or stored entities first");
        }
        Shape shape = shapes.infer(r.samples);
        ObjectNode out = mapper.valueToTree(shape);
        out.set("skipped", mapper.valueToTree(r.skipped));
        out.put("samples", r.samples.size());
        return out;
    }

    /**
     * The Design's Sutra against one sample ({@code sample} = its name, default the first). A brought or synthetic sample is previewed
     * as pasted; a reference is read again through the sources with the caller's rights and masks, and a kind the caller may no longer
     * open is {@code 403 DRS-5002} "no access". Links to other kinds are masked and restricted as in Studio.
     */
    @GetMapping("/{id}/preview")
    public Object preview(@PathVariable String id, @RequestParam(required = false) String sample,
            @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        StoredDesign d = designs.get(who.user(), id);
        if (d.sutra == null || d.sutra.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design has no Sutra yet: start from auto-design, an existing Sutra or an empty one");
        }
        SampleInfo info = sample == null || sample.isBlank() ? (d.samples.isEmpty() ? null : d.samples.get(0)) : d.sample(sample);
        if (info == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, sample == null || sample.isBlank() ? "this design has no samples to preview against"
                    : "no sample '" + sample + "' in this design");
        }
        Sutra s = sutras.check(d.sutra);
        java.util.function.Predicate<String> mayOpen = k -> entitlements.mayOpen(who, k);
        if (StoredDesign.REF.equals(info.type())) {
            if (!mayOpen.test(info.refKind())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, Entitlements.DENIED + ": you may not open " + info.refKind() + " entities");
            }
            return entitlements.restrict(who, pipeline.preview(s, EntityRef.of(info.refKind(), info.refId()), AsOf.LATEST, entitlements.redactor(who), mayOpen));
        }
        String json = designs.sample(who.user(), id, info.name());
        EntityDocument doc = new EntityDocument(EntityRef.of(d.kind, "SAMPLE"), codec.read(json),
                new com.ash.drishti.api.Provenance("design sample JSON", 0, java.time.Instant.now(), false));
        return entitlements.restrict(who, pipeline.preview(Optional.of(s), doc, entitlements.redactor(who), mayOpen));
    }

    /** Drafts a Sutra from the Design's samples (as {@code /builder/design}), keeps it as the Design's Sutra (a new revision) and returns the draft with its {@code rev}. */
    @PostMapping("/{id}/autodesign")
    public ObjectNode autodesign(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        StoredDesign d = designs.get(who.user(), id);
        Resolved r = resolve(who, d);
        if (r.samples.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design has no readable sample: add files, a schema or stored entities first");
        }
        Design draft = designer.design(shapes.infer(r.samples), r.samples, d.kind, builder.previewer(who));
        StoredDesign saved = designs.update(who.user(), id, new DesignService.Patch(null, null, null, draft.yaml(), null));
        ObjectNode out = mapper.valueToTree(draft);
        out.put("rev", saved.rev);
        out.set("skipped", mapper.valueToTree(r.skipped));
        LOG.info("design {} auto-designed from {} sample(s), rev {}", id, r.samples.size(), saved.rev);
        return out;
    }

    // ---- operations ------------------------------------------------------------------------------------------------

    /**
     * Body {@code {baseRev, ops, sample?}}: applies the operations (see {@code POST /builder/edit}) to the Design's Sutra and
     * appends the ones that applied to its log. {@code baseRev} is the {@code rev} you built on; if the design has moved on the answer
     * is {@code 409 DRS-5007} and nothing changes. Answers {@code {rev, yaml, problems, applied, preview}}: the preview is of the
     * named {@code sample} (default the first), or absent when the design has none.
     */
    @PostMapping(path = "/{id}/ops", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode ops(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = body(request);
        int baseRev = baseRev(b, true, null);
        List<Op> ops = BuilderController.ops(b);
        OpResult[] result = new OpResult[1];
        StoredDesign d = designs.applyOps(who.user(), id, baseRev, yaml -> {
            OpResult r = applier.apply(yaml, ops);
            result[0] = r;
            java.util.Set<Integer> failed = new java.util.HashSet<>();
            r.problems().forEach(p -> failed.add(p.op()));
            List<Op> done = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                if (!failed.contains(i)) {
                    done.add(ops.get(i));
                }
            }
            return new DesignService.Applied(r.yaml(), Ops.toJson(done));
        });
        return outcome(d, result[0], text(b, "sample"), who);
    }

    /**
     * The Sutra texts the Design's log passes through, oldest first: version 0 is the text before the first step the log still
     * holds, version n the text after step n. {@code current} marks the one the Design is at (undo moves it back). Answers
     * {@code {versions: [{n, at, ops, current}]}}; the texts are read one at a time at {@code /versions/{n}}.
     */
    @GetMapping("/{id}/versions")
    public ObjectNode versions(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        StoredDesign d = designs.get(who.user(), id);
        ObjectNode o = mapper.createObjectNode();
        ArrayNode list = o.putArray("versions");
        list.addObject().put("n", 0).put("at", d.ops.isEmpty() ? d.created : d.ops.get(0).path("at").asLong()).put("ops", "start").put("current", d.opsAt == 0);
        for (int i = 0; i < d.ops.size(); i++) {
            JsonNode e = d.ops.get(i);
            List<String> names = new ArrayList<>();
            e.path("ops").forEach(x -> names.add(x.path("op").asText("?")));
            list.addObject().put("n", i + 1).put("at", e.path("at").asLong()).put("ops", String.join(", ", names)).put("current", d.opsAt == i + 1);
        }
        o.put("opsAt", d.opsAt);
        return o;
    }

    /** One version's Sutra text: {@code {n, yaml}}; {@code 404 DRS-5006} for a number the log does not hold. */
    @GetMapping("/{id}/versions/{n}")
    public ObjectNode version(@PathVariable String id, @PathVariable int n, @RequestAttribute(Principal.ATTRIBUTE) Principal who) {
        StoredDesign d = designs.get(who.user(), id);
        if (n < 0 || n > d.ops.size()) {
            throw new DrishtiException(ErrorCode.DESIGN_NOT_FOUND, "version " + n + " (this design's log holds 0 to " + d.ops.size() + ")");
        }
        String text = d.ops.isEmpty() ? d.sutra : n == 0 ? d.ops.get(0).path("before").asText("") : d.ops.get(n - 1).path("after").asText("");
        return mapper.createObjectNode().put("n", n).put("yaml", text);
    }

    /** Body {@code {baseRev?}}: takes the last step of the log back; {@code 409 DRS-5007} when there is none. Answers as {@code /ops}. */
    @PostMapping(path = "/{id}/undo")
    public ObjectNode undo(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = optionalBody(request);
        return outcome(designs.undo(who.user(), id, baseRev(b, false, designs.get(who.user(), id))), null, null, who);
    }

    /** Body {@code {baseRev?}}: brings back the step undo took. */
    @PostMapping(path = "/{id}/redo")
    public ObjectNode redo(@PathVariable String id, HttpServletRequest request, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        JsonNode b = optionalBody(request);
        return outcome(designs.redo(who.user(), id, baseRev(b, false, designs.get(who.user(), id))), null, null, who);
    }

    /**
     * The Design's Sutra against all its samples: the matrix of {@code POST /builder/check} (every cell ok, empty, error or noAccess,
     * with counts), plus the {@code rev} it checked. A green matrix marks the design {@code checked} until the next edit. References are
     * read again with your rights and masks.
     */
    @PostMapping("/{id}/check")
    public JsonNode check(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal who) throws IOException {
        StoredDesign d = designs.get(who.user(), id);
        if (d.sutra == null || d.sutra.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design has no Sutra yet: start from auto-design, an existing Sutra or an empty one");
        }
        if (d.samples.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design has no samples to check against");
        }
        List<SampleChecker.Input> inputs = new ArrayList<>();
        for (SampleInfo s : d.samples) {
            inputs.add(StoredDesign.REF.equals(s.type()) ? new SampleChecker.Input(s.name(), null, EntityRef.of(s.refKind(), s.refId()))
                    : new SampleChecker.Input(s.name(), mapper.readTree(designs.sample(who.user(), id, s.name())), null));
        }
        SampleChecker.Matrix m = checks.check(sutras.check(d.sutra), d.kind, inputs, who);
        designs.markChecked(who.user(), id, d.rev, m.ok());
        ObjectNode out = mapper.valueToTree(m);
        out.put("rev", d.rev);
        ObjectNode moved = rebase.moved(d);
        if (moved != null) {
            out.set("baseMoved", moved);
        }
        return out;
    }

    private ObjectNode outcome(StoredDesign d, OpResult r, String sample, Principal who) {
        ObjectNode out = mapper.createObjectNode();
        out.put("rev", d.rev).put("yaml", d.sutra).put("status", d.status).put("opsAt", d.opsAt).put("opsCount", d.ops.size());
        out.set("problems", mapper.valueToTree(r == null ? List.of() : r.problems()));
        out.put("applied", r == null ? 0 : r.applied());
        if (!d.samples.isEmpty() && !d.sutra.isBlank()) {
            try {
                out.set("preview", mapper.valueToTree(preview(d.id, sample, who)));
            } catch (DrishtiException | IOException e) {
                out.put("previewError", e.getMessage());
            }
        }
        return out;
    }

    private static int baseRev(JsonNode b, boolean required, StoredDesign current) {
        if (b.path("baseRev").canConvertToInt() && b.get("baseRev").isIntegralNumber()) {
            return b.get("baseRev").asInt();
        }
        if (required || current == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'baseRev' is required: the revision you built on (the 'rev' of the design)");
        }
        return current.rev;
    }

    private JsonNode optionalBody(HttpServletRequest request) throws IOException {
        return request.getContentLengthLong() <= 0 && request.getContentType() == null ? mapper.createObjectNode() : body(request);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private record Skipped(String name, String reason) {}

    private record Resolved(List<Sample> samples, List<Skipped> skipped) {}

    /** The samples as documents: kept ones read, references read again with the caller's rights and masks (unreadable ones are skipped, said why). */
    private Resolved resolve(Principal who, StoredDesign d) {
        List<Sample> out = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (SampleInfo s : d.samples) {
            try {
                if (StoredDesign.REF.equals(s.type())) {
                    if (!entitlements.mayOpen(who, s.refKind())) {
                        skipped.add(new Skipped(s.name(), Entitlements.DENIED));
                        continue;
                    }
                    EntityDocument doc = router.fetch(EntityRef.of(s.refKind(), s.refId())).get(FETCH.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    out.add(new Sample(s.name(), mapper.readTree(codec.toJson(entitlements.redact(who, doc.data())))));
                } else {
                    out.add(new Sample(s.name(), mapper.readTree(designs.sample(who.user(), d.id, s.name()))));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                skipped.add(new Skipped(s.name(), "interrupted"));
            } catch (Exception e) {
                skipped.add(new Skipped(s.name(), "could not be read"));
            }
        }
        return new Resolved(out, skipped);
    }

    private String registrySource(String base) {
        int at = base.lastIndexOf('@');
        try {
            if (at > 0) {
                return sutras.source(base.substring(0, at), Integer.parseInt(base.substring(at + 1)))
                        .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, base));
            }
        } catch (NumberFormatException e) {
            // falls through to the message below
        }
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "'base' is a Sutra as name@version");
    }

    private static String kind(String kind) {
        if (kind != null && !kind.isBlank() && !KIND.matcher(kind.trim()).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'kind' is letters, digits, . _ - (up to 64)");
        }
        return kind;
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.path(field).isTextual() ? n.get(field).asText() : null;
    }

    public ObjectNode view(DesignService.Summary s, boolean full) {
        StoredDesign d = s.design();
        ObjectNode o = mapper.createObjectNode();
        o.put("id", d.id).put("name", d.name).put("scratch", d.scratch).put("kind", d.kind).put("status", d.status).put("rev", d.rev);
        if (d.base != null) {
            o.put("base", d.base);
        }
        if (d.boundFile != null) {
            o.put("boundFile", d.boundFile);
        }
        o.put("shared", d.shareHash != null);
        o.put("created", d.created).put("updated", d.updated).put("expiresAt", s.expiresAt()).put("expiryWarning", s.expiryWarning())
                .put("bytes", s.bytes());
        ArrayNode samples = o.putArray("samples");
        for (SampleInfo i : d.samples) {
            ObjectNode x = samples.addObject();
            x.put("name", i.name()).put("type", i.type()).put("synthetic", StoredDesign.SYNTHETIC.equals(i.type())).put("bytes", i.bytes());
            if (i.refKind() != null) {
                x.putObject("ref").put("kind", i.refKind()).put("id", i.refId());
            }
        }
        if (full) {
            ObjectNode moved = rebase.moved(d);
            if (moved != null) {
                o.set("baseMoved", moved);
            }
            o.put("sutra", d.sutra).put("notes", d.notes);
            o.set("tests", mapper.valueToTree(d.tests));
            o.put("opsAt", d.opsAt);
            ArrayNode log = o.putArray("ops");                // the steps, without the Sutra texts they hold
            d.ops.forEach(e -> log.addObject().put("at", e.path("at").asLong()).set("ops", e.path("ops")));
        }
        return o;
    }

    private JsonNode body(HttpServletRequest request) throws IOException {
        long max = limits.maxTotalBytes();
        if (request.getContentLengthLong() > max) {
            throw new ShapeException("the request is over the limit of " + limits.maxTotalMb() + " MB (drishti.builder.max-total-mb)");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > max) {
                throw new ShapeException("the request is over the limit of " + limits.maxTotalMb() + " MB (drishti.builder.max-total-mb)");
            }
            JsonNode n = mapper.readTree(bytes);
            if (n == null || !n.isObject()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body must be a JSON object");
            }
            return n;
        } catch (StreamConstraintsException e) {
            String why = e.getMessage() == null ? "" : e.getMessage();
            if (why.contains("nesting depth")) {
                throw new ShapeException("a document is nested deeper than " + limits.maxDepth() + " levels (drishti.builder.max-depth)");
            }
            throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "a single text value in the request is too long (the limit is " + STRING_MAX_MB
                    + " MB per value): a Sutra is limited by drishti.builder.designs.max-sutra-kb");
        } catch (JsonParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the body is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
