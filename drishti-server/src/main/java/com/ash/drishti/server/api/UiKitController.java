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

import com.ash.drishti.rachana.parse.SutraBlocks;
import com.ash.drishti.server.uikit.UiKit;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Drishti UI kit, read-only (SUTRA_NEXT.md, "The Drishti UI kit"):
 * <ul>
 *   <li>{@code GET /api/v1/ui-kit}: the kit's fragments with their docstrings, the enabled packs' own (namespaced), every asset with
 *       its versioned URL, and the brand (the site's override over the kit's {@code brand.yaml}) with a URL for each logo and icon;</li>
 *   <li>{@code GET /api/v1/ui-kit/assets/{path}}: one asset, with a strong ETag (its SHA-256) and a 304 for a matching
 *       {@code If-None-Match}; a URL carrying the content hash ({@code ?v=}, as the listing gives it) is cached for a year
 *       ({@code immutable}), any other for five minutes. A path that is not a plain relative path inside the folder is a 404.</li>
 * </ul>
 * {@code GET /api/v1/sutras/library} stays as the fragments-only alias.
 */
@RestController
@RequestMapping("/api/v1/ui-kit")
public class UiKitController {

    static final String ASSETS = "/api/v1/ui-kit/assets/";

    private final UiKit kit;
    private final SutraLibraryController library;

    public UiKitController(UiKit kit, SutraLibraryController library) {
        this.kit = kit;
        this.library = library;
    }

    @GetMapping
    public Map<String, Object> summary() {
        List<Map<String, Object>> fragments = new ArrayList<>();
        List<Map<String, Object>> packFragments = new ArrayList<>();
        for (SutraBlocks.Block b : SutraBlocks.blocks()) {
            if (b.fragment()) {
                (SutraBlocks.fromKit(b) ? fragments : packFragments).add(library.describe(b, false));
            }
        }
        List<Map<String, Object>> assets = new ArrayList<>();
        Map<String, String> urls = new LinkedHashMap<>();
        for (UiKit.Asset a : kit.assets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", a.path());
            m.put("kind", a.path().contains("/") ? a.path().substring(0, a.path().indexOf('/')) : "");
            m.put("type", a.contentType());
            m.put("size", a.size());
            m.put("hash", a.hash());
            m.put("origin", a.origin());
            m.put("url", UiKit.url(a));
            assets.add(m);
            urls.put(a.path(), UiKit.url(a));
        }
        Map<String, Object> brand = kit.brand();
        Map<String, String> brandUrls = new LinkedHashMap<>();         // logos.full -> its versioned URL
        for (String group : List.of("logos", "icons")) {
            if (brand.get(group) instanceof Map<?, ?> refs) {
                refs.forEach((k, v) -> {
                    if (v != null && urls.containsKey(v.toString())) {
                        brandUrls.put(group + "." + k, urls.get(v.toString()));
                    }
                });
            }
        }
        brand.put("urls", brandUrls);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fragments", fragments);
        out.put("packFragments", packFragments);
        out.put("assets", assets);
        out.put("brand", brand);
        out.put("streaming", false);                                   // Gen UI streaming of the kit: later (SUTRA_NEXT.md)
        return out;
    }

    @GetMapping("/assets/**")
    public ResponseEntity<Resource> asset(HttpServletRequest request, @RequestParam(name = "v", required = false) String version,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        String uri = request.getRequestURI();
        int at = uri.indexOf(ASSETS);
        String rel = at < 0 ? "" : URLDecoder.decode(uri.substring(at + ASSETS.length()), StandardCharsets.UTF_8);
        UiKit.Asset a = kit.asset(rel).orElse(null);
        if (a == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String etag = "\"" + a.hash() + "\"";
        CacheControl cache = version != null && a.hash().startsWith(version) && version.length() >= 8
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().mustRevalidate();
        if (ifNoneMatch != null && (ifNoneMatch.trim().equals("*") || List.of(ifNoneMatch.split(",")).stream()
                .map(String::trim).map(t -> t.startsWith("W/") ? t.substring(2) : t).anyMatch(etag::equals))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).contentType(MediaType.parseMediaType(a.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox")   // an SVG runs no script
                .body(new FileSystemResource(a.file()));
    }
}
