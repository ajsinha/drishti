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

import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Python items (RUPAKA.md section 20.8): check a manifest, and read an item's manifest and source for the console's runner. Items themselves are ordinary BI items of kind python. */
@RestController
@RequestMapping("/api/v1/bi/python")
public class PythonController {

    private final PythonItems items;

    public PythonController(PythonItems items) {
        this.items = items;
    }

    /** Body {@code {yaml}}: every problem in a Python item's manifest, and the manifest as parsed; needs {@code bi}. */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items.validate(p, String.valueOf(body.getOrDefault("yaml", ""))));
    }

    /** One Python item (a catalogue id, a shipped file name or a title) with its manifest and source, for a caller who may read it. */
    @GetMapping("/{ref}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items.describe(p, ref, false));
    }
}
