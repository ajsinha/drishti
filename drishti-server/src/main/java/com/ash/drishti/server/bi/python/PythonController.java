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
package com.ash.drishti.server.bi.python;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.transform.TransformProperties;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Python items and every Python run (docs/guides/BI_GUIDE.md › Python in BI; docs/architecture/PYTHON_COMPUTE.md section 19), on the server's compute
 * pool {@code python} ({@link PythonRuns}). The rows each run reads are read here, as the caller ({@link PythonFrames}); nothing is stored but the audit.
 *
 * <pre>
 * GET  /api/v1/bi/python/runtime     whether Python runs here, and why not; the pool, the pinned packages, the counters
 * POST /api/v1/bi/python/validate    {yaml}: a manifest's problems and the manifest (needs bi)
 * POST /api/v1/bi/python/check       {yaml, tests?}: the manifest's problems, the source's (parsed on the pool, not run) and the item's own tests run
 * POST /api/v1/bi/python/visual      {report, page, visual, state}: one python visual of a report, run over its rows as the caller
 * POST /api/v1/bi/python/cell        {source, extract, extracts?: [{name, ...spec}], params?}: one Notebook cell (needs bi-author)
 * POST /api/v1/bi/python/steps       {name, source, params?, inputs: [{name, input}], asOf?}: a Transform's step-by-step run (needs bi-author)
 * POST /api/v1/bi/python/profile     {extract | input, limit?}: a frame's profile (no author code)
 * GET  /api/v1/bi/python/{ref}       one Python item with its manifest and source, for a caller who may read it
 * </pre>
 *
 * A failure is the problem of its DRS code (9070 to 9076); a run an author asked for carries the author's own {@code traceback}.
 */
@RestController
@RequestMapping("/api/v1/bi/python")
public class PythonController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CELL_ROWS = 50_000;
    private static final int CELL_CHARS = 20_000;
    private static final int STEP_ROWS = 1_000;

    private final PythonItems items;
    private final PythonRuns runs;
    private final PythonFrames frames;
    private final PythonTestRuns tests;
    private final BiService bi;
    private final BiReportService reports;
    private final TransformProperties transform;

    public PythonController(PythonItems items, PythonRuns runs, PythonFrames frames, PythonTestRuns tests, BiService bi, BiReportService reports,
            TransformProperties transform) {
        this.items = items;
        this.runs = runs;
        this.frames = frames;
        this.tests = tests;
        this.bi = bi;
        this.reports = reports;
        this.transform = transform;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Map<String, Object> out = new LinkedHashMap<>(runs.status());
        out.put("mayRun", bi.hasPower(p, BiAccess.AUTHOR));
        return noStore(out);
    }

    /** Body {@code {yaml}}: every problem in a Python item's manifest, and the manifest as parsed; needs {@code bi}. */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(items.validate(p, String.valueOf(body.getOrDefault("yaml", ""))));
    }

    /** Body {@code {yaml, tests?}}: the manifest's checks, the source's (Python's own parser, on the pool) and, unless {@code tests: false}, the item's tests. */
    @PostMapping("/check")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> answer = new LinkedHashMap<>(items.validate(p, String.valueOf(body.getOrDefault("yaml", ""))));
        List<Map<String, Object>> problems = new ArrayList<>((List<Map<String, Object>>) answer.get("problems"));
        Map<String, Object> m = (Map<String, Object>) answer.get("manifest");
        if (m != null) {
            problems.addAll(runs.checkSource(p, m));
        }
        boolean valid = problems.stream().noneMatch(x -> "error".equals(x.get("level")));
        answer.put("problems", problems);
        answer.put("valid", valid);
        answer.put("packages", runs.packages());
        if (valid && m != null && m.get("tests") instanceof List<?> l && !l.isEmpty() && !Boolean.FALSE.equals(body.get("tests"))) {
            answer.put("tests", tests.run(p, m));
        }
        return noStore(answer);
    }

    /** Body {@code {report, page, visual, state}}: one python visual, run over the visual's rows as the caller. */
    @PostMapping("/visual")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> visual(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String report = body.path("report").asText("");
        String page = body.path("page").asText("");
        String visual = body.path("visual").asText("");
        if (report.isBlank() || page.isBlank() || visual.isBlank()) {
            throw new DrishtiException(ErrorCode.PYTHON_INVALID, "send {report, page, visual, state}");
        }
        Map<String, Object> item = reports.pythonOf(p, report, null, page, visual);
        Map<String, Object> measure = (Map<String, Object>) item.get("measure");
        Map<String, Object> manifest = new LinkedHashMap<>((Map<String, Object>) item.get("manifest"));
        Map<String, Object> how = new LinkedHashMap<>();
        how.put("frame", "frame.arrow");
        if (measure != null) {
            List<String> inputs = (List<String>) measure.getOrDefault("inputs", manifest.getOrDefault("inputs", List.of()));
            manifest.put("inputs", inputs.stream().map(PythonController::lastPart).toList());
            how.put("group_by", ((List<String>) measure.getOrDefault("by", List.of())).stream().map(PythonController::lastPart).toList());
        }
        PythonFrames.Read rows = frames.read(p, JSON.createObjectNode().put("report", report).put("page", page).put("visual", visual)
                .set("state", body.has("state") ? body.get("state") : JSON.createObjectNode()), Integer.MAX_VALUE);
        PythonRuns.Answer a = runs.run(p, String.valueOf(manifest.get("interface")), manifest, (Map<String, Object>) item.getOrDefault("params", Map.of()),
                new PythonRuns.Inputs(Map.of("frame.arrow", rows.arrow()), how), bi.hasPower(p, BiAccess.AUTHOR), "visual " + visual + " of " + report);
        Map<String, Object> out = new LinkedHashMap<>(a.body());
        out.put("measure", measure == null ? null : measure.get("name"));
        out.put("item", item.get("id"));
        out.put("version", item.get("version"));
        out.put("target", "server");
        out.put("inputRows", rows.rows());
        out.put("masked", rows.masked());
        return noStore(out);
    }

    /** Body {@code {source, extract, extracts?, params?}}: one Notebook cell over {@code df} (the extract) and {@code extract("<name>")}; needs bi-author. */
    @PostMapping("/cell")
    public ResponseEntity<Map<String, Object>> cell(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        String source = body.path("source").asText("");
        if (source.isBlank() || source.length() > CELL_CHARS) {
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "a cell holds 1 to " + CELL_CHARS + " characters");
        }
        int limit = Math.max(1, Math.min(body.path("limit").canConvertToInt() ? body.path("limit").asInt() : CELL_ROWS, CELL_ROWS));
        PythonFrames.Read main = frames.read(p, body.get("extract"), limit);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("frame.arrow", main.arrow());
        Map<String, Object> named = new LinkedHashMap<>();
        List<Map<String, Object>> extras = new ArrayList<>();
        Set<String> masked = new LinkedHashSet<>(main.masked());
        int n = 0;
        for (JsonNode e : body.path("extracts")) {
            PythonFrames.Read r = frames.read(p, e, limit);
            String file = "ex_" + n++ + ".arrow";
            files.put(file, r.arrow());
            String name = e.path("name").asText(file);
            named.put(name, file);
            if (e.hasNonNull("dataset")) {
                named.putIfAbsent(e.path("dataset").asText(), file);           // an extract is read by its name or by its dataset (the first of a dataset)
            }
            masked.addAll(r.masked());
            extras.add(Map.of("name", name, "rows", r.rows(), "masked", r.masked()));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", "notebook-cell");
        manifest.put("interface", "cell");
        manifest.put("source", source);
        manifest.put("packages", cellPackages(source));
        manifest.put("timeout", body.path("timeout").canConvertToInt() ? body.path("timeout").asInt() : 30);
        manifest.put("memory", body.path("memory").canConvertToInt() ? body.path("memory").asInt() : 1024);
        Map<String, Object> how = new LinkedHashMap<>();
        how.put("frame", "frame.arrow");
        how.put("extracts", named);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.path("params").isObject() ? JSON.convertValue(body.get("params"), Map.class) : Map.of();
        PythonRuns.Answer a = runs.run(p, "cell", manifest, params, new PythonRuns.Inputs(files, how), true, "notebook cell");
        Map<String, Object> out = new LinkedHashMap<>(a.body());
        out.put("inputRows", main.rows());
        out.put("masked", new ArrayList<>(masked));
        out.put("target", "server");
        out.put("truncatedInput", main.cut());
        out.put("extracts", extras);
        return noStore(out);
    }

    /** The allow-listed packages a cell imports (pandas always): what its manifest lists. */
    List<String> cellPackages(String source) {
        List<String> allowed = runs.packages();
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*(?:import|from)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(source);
        while (m.find()) {
            if (allowed.contains(m.group(1)) && !found.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        if (!found.contains("pandas") && allowed.contains("pandas")) {
            found.add("pandas");
        }
        return found;
    }

    /**
     * Body {@code {name, source, params?, inputs: [{name, input}], asOf?, timeout?, memory?}}: a Transform's step-by-step run (the Transform editor): each input
     * read as the caller (cut to the preview rows), then {@code run} once on the pool; each step's frame described. Needs bi-author.
     */
    @PostMapping("/steps")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> steps(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        int limit = Math.max(1, Math.min(body.path("limit").canConvertToInt() ? body.path("limit").asInt() : STEP_ROWS, transform.maxInputRows()));
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> fileNames = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> read = new ArrayList<>();
        long t0 = System.nanoTime();
        int n = 0;
        for (JsonNode i : body.path("inputs")) {
            com.fasterxml.jackson.databind.node.ObjectNode ask = JSON.createObjectNode();
            ask.set("input", i.get("input"));
            if (body.hasNonNull("asOf")) {
                ask.put("asOf", body.path("asOf").asText());
            }
            String name = i.path("name").asText("input_" + (n + 1));
            PythonFrames.Read r;
            try {
                r = frames.read(p, ask, limit);
            } catch (DrishtiException e) {
                throw new DrishtiException(e.errorCode(), "the input '" + name + "': " + e.getMessage().substring(e.errorCode().code().length()).strip());
            }
            String file = "in_" + n++ + ".arrow";
            files.put(file, r.arrow());
            fileNames.add(file);
            names.add(name);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("masked", r.masked());
            info.put("model", r.model());
            info.put("asOf", r.asOf());
            info.put("cut", r.rows() >= limit);
            read.add(info);
        }
        long askedMs = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", body.path("name").asText("transform"));
        manifest.put("interface", "steps");
        manifest.put("entry", "run");
        manifest.put("inputs", names);
        manifest.put("packages", runs.packages().stream().filter(x -> x.equals("pandas") || x.equals("numpy")).toList());
        manifest.put("source", body.path("source").asText(""));
        manifest.put("timeout", body.path("timeout").canConvertToInt() ? body.path("timeout").asInt() : 30);
        manifest.put("memory", body.path("memory").canConvertToInt() ? body.path("memory").asInt() : 512);
        Map<String, Object> params = body.path("params").isObject() ? JSON.convertValue(body.get("params"), Map.class) : Map.of();
        Map<String, Object> how = new LinkedHashMap<>();
        how.put("frames", fileNames);
        how.put("names", names);
        PythonRuns.Answer a = runs.run(p, "steps", manifest, params, new PythonRuns.Inputs(files, how), true, "steps of " + manifest.get("name"));
        Map<String, Object> out = new LinkedHashMap<>(a.body());
        if (out.get("inputs") instanceof List<?> l) {
            for (int i = 0; i < l.size() && i < read.size(); i++) {
                ((Map<String, Object>) l.get(i)).putAll(read.get(i));
            }
        }
        out.put("askedMs", askedMs);
        out.put("previewRows", limit);
        return noStore(out);
    }

    /** Body {@code {extract | input, limit?}}: the profile of a frame read as the caller (per column its type, nulls, distinct values, top values...). */
    @PostMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        int limit = Math.max(1, Math.min(body.path("limit").canConvertToInt() ? body.path("limit").asInt() : CELL_ROWS, CELL_ROWS));
        JsonNode spec = body.hasNonNull("input") ? JSON.createObjectNode().set("input", body.get("input")) : body.get("extract");
        PythonFrames.Read r = frames.read(p, spec, limit);
        Map<String, Object> manifest = Map.of("name", "profile", "interface", "profile", "source", "", "packages", List.of(), "timeout", 20, "memory", 512);
        PythonRuns.Answer a = runs.run(p, "profile", manifest, Map.of(), PythonRuns.Inputs.one(r.arrow()), false, "profile");
        Map<String, Object> out = new LinkedHashMap<>(a.body());
        out.put("inputRows", r.rows());
        out.put("masked", r.masked());
        out.put("truncatedInput", r.cut());
        return noStore(out);
    }

    /** One Python item (a catalogue id, a shipped file name or a title) with its manifest and source, for a caller who may read it. */
    @GetMapping("/{ref}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return noStore(items.describe(p, ref, false));
    }

    private static String lastPart(String column) {
        return column.contains(".") ? column.substring(column.indexOf('.') + 1) : column;
    }
}
