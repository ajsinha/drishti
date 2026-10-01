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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.RoleDefinition;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.RoleCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roles (Admin → Roles): every role with what it allows, and the ones administrators define. Built-in roles come from
 * configuration and packs and are read-only here. Each role lists the kinds it opens and six powers: raw JSON,
 * authoring Sutras, approving them, administering, Calc, and customising layouts. Changes take effect at the next request; all are audited.
 */
@RestController
@RequestMapping("/api/v1/admin/role-definitions")
public class RoleController {

    /** A role as an administrator writes it. */
    public record RoleRequest(String description, List<String> kinds, Boolean raw, Boolean author, Boolean approve, Boolean admin, Boolean calc,
            Boolean layout) {}

    private final RoleCatalog roles;
    private final UserService users;
    private final Entitlements entitlements;

    public RoleController(RoleCatalog roles, UserService users, Entitlements entitlements) {
        this.roles = roles;
        this.users = users;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<Map<String, Object>> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        Map<String, Long> holders = new java.util.HashMap<>();
        users.list(null).forEach(u -> u.roles().forEach(r -> holders.merge(r, 1L, Long::sum)));
        return roles.all().stream().map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", r.name());
            m.put("description", r.description());
            m.put("kinds", r.kinds());
            m.put("raw", r.raw());
            m.put("author", r.author());
            m.put("approve", r.approve());
            m.put("admin", r.admin());
            m.put("calc", r.calc());
            m.put("layout", r.layout());
            m.put("builtIn", r.builtIn());
            m.put("updatedAt", r.updatedAt());
            m.put("updatedBy", r.updatedBy());
            m.put("users", holders.getOrDefault(r.name(), 0L));
            return m;
        }).toList();
    }

    @GetMapping("/{name}")
    public RoleDefinition one(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return roles.find(name).orElseThrow(() -> new DrishtiException(ErrorCode.ROLE_NOT_FOUND, "no role '" + name + "'"));
    }

    @PutMapping("/{name}")
    public RoleDefinition save(@PathVariable String name, @RequestBody RoleRequest r, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        List<String> kinds = r.kinds() == null ? List.of() : r.kinds().stream().map(String::trim).filter(k -> !k.isEmpty()).distinct().toList();
        return roles.save(new RoleDefinition(name, r.description(), kinds, Boolean.TRUE.equals(r.raw()), Boolean.TRUE.equals(r.author()),
                Boolean.TRUE.equals(r.approve()), Boolean.TRUE.equals(r.admin()), Boolean.TRUE.equals(r.calc()), !Boolean.FALSE.equals(r.layout()), false, null, ""), p.user());
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        if (!roles.delete(name, p.user(), users)) {
            throw new DrishtiException(ErrorCode.ROLE_NOT_FOUND, "no role '" + name + "'");
        }
    }
}
