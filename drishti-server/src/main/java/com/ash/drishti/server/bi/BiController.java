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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business intelligence, phase 1 (docs/architecture/RUPAKA.md): the BI shell's API. Every call is answered from the caller's identity by
 * {@link BiService}; there is no admin path because the powers decide (a role with bi-admin, not only the admin role). Personal API tokens
 * reach the writes through the scopes {@code bi:write}, {@code bi:approve} and {@code bi:admin} (API_GUIDE).
 */
@RestController
@RequestMapping("/api/v1/bi")
public class BiController {

    private final BiService bi;

    public BiController(BiService bi) {
        this.bi = bi;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.me(p);
    }

    @GetMapping("/home")
    public Map<String, Object> home(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.home(p);
    }

    @GetMapping("/catalogue")
    public Map<String, Object> catalogue(@RequestParam Map<String, String> query, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.catalogue(p, query);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.settings(p);
    }

    @PutMapping("/settings")
    public Map<String, Object> setSettings(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.setSettings(p, body);
    }

    // -- workspaces ---------------------------------------------------------------------------------------------------
    @GetMapping("/workspaces")
    public List<Map<String, Object>> workspaces(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.workspaces(p);
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createWorkspace(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.createWorkspace(p, body);
    }

    @GetMapping("/workspaces/{id}")
    public Map<String, Object> workspace(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.workspace(p, id);
    }

    @PutMapping("/workspaces/{id}")
    public Map<String, Object> updateWorkspace(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.updateWorkspace(p, id, body);
    }

    @DeleteMapping("/workspaces/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkspace(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.deleteWorkspace(p, id);
    }

    @PutMapping("/workspaces/{id}/members/{user}")
    public Map<String, Object> setMember(@PathVariable String id, @PathVariable String user, @RequestBody Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.setMember(p, id, user, String.valueOf(body.get("role")));
    }

    @DeleteMapping("/workspaces/{id}/members/{user}")
    public Map<String, Object> removeMember(@PathVariable String id, @PathVariable String user, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.removeMember(p, id, user);
    }

    @GetMapping("/workspaces/{id}/items")
    public List<Map<String, Object>> items(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.items(p, id);
    }

    @PostMapping("/workspaces/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createItem(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.createItem(p, id, body);
    }

    // -- items ----------------------------------------------------------------------------------------------------------
    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.item(p, id, false);
    }

    /** The published version of an item as its readers get it (for an author viewing a draft); DRS-9001 when never published. */
    @GetMapping("/items/{id}/published")
    public Map<String, Object> published(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.publishedView(p, id);
    }

    /** Opens the item for the caller: counts a view and puts it under Recent. */
    @PostMapping("/items/{id}/open")
    public Map<String, Object> open(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.item(p, id, true);
    }

    @PutMapping("/items/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.updateItem(p, id, body);
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.deleteItem(p, id);
    }

    @PostMapping("/items/{id}/move")
    public Map<String, Object> move(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.moveItem(p, id, String.valueOf(body.get("workspace")));
    }

    @PostMapping("/items/{id}/propose")
    public Map<String, Object> propose(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.propose(p, id, note(body), force(body));
    }

    @PostMapping("/items/{id}/withdraw")
    public Map<String, Object> withdraw(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.withdraw(p, id);
    }

    @PostMapping("/items/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.approve(p, id, note(body), force(body), renames(body));
    }

    @PostMapping("/items/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.reject(p, id, note(body));
    }

    @PostMapping("/items/{id}/publish")
    public Map<String, Object> publish(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.publish(p, id, note(body), force(body), renames(body));
    }

    @PostMapping("/items/{id}/endorse")
    public Map<String, Object> endorse(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.endorse(p, id, String.valueOf(body.get("level")));
    }

    @PutMapping("/items/{id}/favourite")
    public Map<String, Object> favourite(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.favourite(p, id, !Boolean.FALSE.equals(body.get("on")));
    }

    @GetMapping("/items/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.versions(p, id);
    }

    @GetMapping("/items/{id}/versions/{n}")
    public Map<String, Object> version(@PathVariable String id, @PathVariable int n, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.version(p, id, n);
    }

    @PostMapping("/items/{id}/versions/{n}/restore")
    public Map<String, Object> restore(@PathVariable String id, @PathVariable int n, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return bi.restore(p, id, n);
    }

    private static boolean renames(Map<String, Object> body) {
        return body != null && Boolean.TRUE.equals(body.get("applyRenames"));
    }

    private static boolean force(Map<String, Object> body) {
        return body != null && Boolean.TRUE.equals(body.get("force"));
    }

    private static String note(Map<String, Object> body) {
        Object n = body == null ? null : body.containsKey("note") ? body.get("note") : body.get("reason");
        return n == null ? "" : String.valueOf(n);
    }
}
