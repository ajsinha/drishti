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
package com.ash.drishti.server.cube;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.ArrowAnswers;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The shared cube core's API (MR_CUBE_GUIDE.md section 10), as the person (every figure the cube's source computes for exactly that set of
 * members; a shared view opens with the reader's entitlements):
 * <pre>
 *   GET  /api/v1/cube                                      the cubes running here and the core's settings
 *   GET  /api/v1/cube/{cube}/describe?scope=&amp;date=          the measures and the dimensions a view may use
 *   POST /api/v1/cube/{cube}/query                          a view → cells, row and column totals, the grand total, diversification,
 *        a provenance id (Accept: application/vnd.apache.arrow.stream: the cells and totals as one Arrow table)
 *   GET  /api/v1/cube/{cube}/query?text=MCUBE ...|view=&lt;owner/name&gt;   the same for a typed view or a saved one (Power Query, Tableau)
 *   POST /api/v1/cube/{cube}/cell                           {view, row, column}: one cell's figures, its histogram and tail, its drill
 *   POST /api/v1/cube/{cube}/export?format=csv|xlsx|arrow   the view exported (xlsx: SUM formulas for additive measures, values said so)
 *   GET  /api/v1/cube/provenance/{id}?cell=r|c              an answer's provenance, and one cell's
 *   GET  /api/v1/cube/views · POST /api/v1/cube/views · GET /api/v1/cube/views/{owner}/{name} · DELETE /api/v1/cube/views/{name}
 *   GET  /api/v1/cube/views/{owner}/{name}/data?format=json|csv|xlsx|arrow   a saved view answered now, as the reader (Power Query)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/cube")
public class CubeController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final CubeEngine engine;
    private final CubeViews views;

    public CubeController(CubeEngine engine, CubeViews views) {
        this.engine = engine;
        this.views = views;
    }

    @GetMapping
    public Map<String, Object> cubes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cubes", engine.cubes());
        m.put("maxCells", engine.props().maxCells());
        m.put("busyCells", engine.props().busyCells());
        m.put("cacheMb", engine.props().cacheMb());
        m.put("mrEnabled", engine.props().mr().enabled());
        return m;
    }

    @GetMapping("/{cube}/describe")
    public Map<String, Object> describe(@PathVariable String cube, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String date, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return engine.describe(p, new CubeView(cube, scope, List.of(), List.of(), List.of(), Map.of(), CubeView.date(date), Map.of(), null, 0));
    }

    private CubeView view(String cube, Map<String, Object> body, Principal p) {
        if (body != null && body.get("id") instanceof String id && !id.isBlank() && body.get("scope") == null) {
            return views.open(p, id);
        }
        return CubeView.of(body, cube);
    }

    @PostMapping("/{cube}/query")
    public ResponseEntity<Object> query(@PathVariable String cube, @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> a = engine.query(p, view(cube, body, p));
        if (ArrowAnswers.wanted(accept)) {
            return ArrowAnswers.negotiate(accept, CubeExport.forArrow(a), "records", "records");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(a);
    }

    @GetMapping("/{cube}/query")
    public ResponseEntity<Object> queryGet(@PathVariable String cube, @RequestParam(required = false) String text,
            @RequestParam(required = false) String view, @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        CubeView v = view != null && !view.isBlank() ? views.open(p, view) : CubeView.parse(text == null ? "" : text, cube);
        Map<String, Object> a = engine.query(p, v);
        if (ArrowAnswers.wanted(accept)) {
            return ArrowAnswers.negotiate(accept, CubeExport.forArrow(a), "records", "records");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(a);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{cube}/cell")
    public Map<String, Object> cell(@PathVariable String cube, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object v = body.get("view");
        CubeView view = view(cube, v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), p);
        return engine.cell(p, view, str(body.get("row")), str(body.get("column")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @PostMapping("/{cube}/export")
    public ResponseEntity<?> export(@PathVariable String cube, @RequestParam(defaultValue = "csv") String format, @RequestBody Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return exported(engine.query(p, view(cube, body, p)), format, p);
    }

    private ResponseEntity<?> exported(Map<String, Object> a, String format, Principal p) {
        String name = "cube_" + String.valueOf(a.get("text")).replaceAll("[^A-Za-z0-9_.~-]+", "-").replaceAll("^-+|-+$", "") + "_" + a.get("date");
        name = name.length() > 120 ? name.substring(0, 120) : name;
        switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> {
                return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".csv\"").header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(CubeExport.csv(a));
            }
            case "arrow" -> {
                byte[] bytes = ArrowAnswers.encode(CubeExport.forArrow(a), "records", "records").orElseThrow(() -> new DrishtiException(
                        ErrorCode.BAD_REQUEST, "the view's cells cannot be written as Arrow; ask for csv"));
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(ArrowAnswers.MEDIA))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".arrows\"").body(bytes);
            }
            case "xlsx" -> {
                String user = p == null ? "" : p.user();
                String now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString();
                StreamingResponseBody out = os -> CubeExport.xlsx(a, os, user, now);
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".xlsx\"").header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(out);
            }
            case "json" -> {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(a);
            }
            default -> throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, "format is csv, xlsx, arrow or json, not " + format);
        }
    }

    @GetMapping("/provenance/{id}")
    public Map<String, Object> provenance(@PathVariable String id, @RequestParam(required = false) String cell) {
        return engine.provenance(id, cell);
    }

    // ---------------------------------------------------------------------------------------------------------------- saved views

    @GetMapping("/views")
    public List<Map<String, Object>> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.list(p);
    }

    @PostMapping("/views")
    public Map<String, Object> save(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String cube = body.get("cube") == null ? "mr" : String.valueOf(body.get("cube"));
        engine.source(cube);                                             // a view of a cube that is off is not saved
        return views.save(p, body, cube);
    }

    @GetMapping("/views/{owner}/{name}")
    public Map<String, Object> get(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return views.get(p, owner + "/" + name).orElseThrow(() -> new DrishtiException(ErrorCode.CUBE_VIEW_NOT_FOUND,
                "no saved cube view " + owner + "/" + name + " that you may open"));
    }

    @GetMapping("/views/{owner}/{name}/data")
    public ResponseEntity<?> data(@PathVariable String owner, @PathVariable String name, @RequestParam(defaultValue = "json") String format,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return exported(engine.query(p, views.open(p, owner + "/" + name)), format, p);
    }

    @DeleteMapping("/views/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!views.delete(p, name)) {
            throw new DrishtiException(ErrorCode.CUBE_VIEW_NOT_FOUND, "you have no saved cube view " + name);
        }
    }

    /** A cube's own endpoint when that cube is off on this server (or a word it does not have): said in words. */
    @RequestMapping("/{cube}/{what}")
    public void other(@PathVariable String cube, @PathVariable String what) {
        engine.source(cube);
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the " + cube.toUpperCase(Locale.ROOT) + " cube has no endpoint " + what);
    }
}
