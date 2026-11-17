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
package com.ash.drishti.server.kernelapi;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Person;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The person of a typed kernel call, copied from the request that made it (KERNEL_AND_MODULES.md section 2.3): the identity the token
 * filter set, the display name, the date box and "known at" as the REST paths read them, the lens, and the scopes of a personal API token
 * (null: not a token). Immutable and usable from any thread; nothing of it is kept in a static or a shared cache.
 */
public final class RequestPerson implements Person {

    private final Principal principal;
    private final String display;
    private final boolean admin;
    private final String lens;
    private final AsOf asOf;
    private final List<String> scopes;
    private final BusinessDates dates;

    RequestPerson(Principal principal, String display, boolean admin, String lens, AsOf asOf, List<String> scopes, BusinessDates dates) {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.display = display == null ? "" : display;
        this.admin = admin;
        this.lens = lens == null ? "" : lens.strip();
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.scopes = scopes == null ? null : List.copyOf(scopes);
        this.dates = dates;
    }

    /**
     * The kernel's own person behind {@code p}: a typed call is made only for a person the kernel made ({@code Kernel.person()} or their
     * {@link #onDate}), never for one a module made up.
     *
     * @throws IllegalArgumentException a person the kernel did not make
     */
    static RequestPerson of(Person p) {
        if (p instanceof RequestPerson r) {
            return r;
        }
        throw new IllegalArgumentException("a typed kernel call is made for the person the kernel gave (Kernel.person()), not for "
                + (p == null ? "null" : p.getClass().getName()));
    }

    /** The identity the request carried, for the doors (entitlements, masks, row rules). */
    public Principal principal() {
        return principal;
    }

    /** The scopes of the personal API token the request came with; null when it came with none (a sign-in, or security off). */
    public List<String> scopes() {
        return scopes;
    }

    /** The date box as a request header would say it: the business date, or null for the live date. */
    public String dateBox() {
        return asOf.live() || asOf.businessDate() == null ? null : asOf.businessDate().toString();
    }

    @Override
    public String user() {
        return principal.user();
    }

    @Override
    public String display() {
        return display;
    }

    @Override
    public boolean admin() {
        return admin;
    }

    @Override
    public List<String> roles() {
        return principal.roles();
    }

    @Override
    public String lens() {
        return lens;
    }

    @Override
    public AsOf asOf() {
        return asOf;
    }

    @Override
    public String key() {
        return String.join("\u0001", user(), String.join(",", roles()), lens, dateBox() == null ? "live" : dateBox(),
                asOf.knownAt() == null ? "" : asOf.knownAt().toString());
    }

    @Override
    public Person onDate(LocalDate day) {
        Objects.requireNonNull(day, "day");
        AsOf moved = dates.parse(day.toString(), asOf.knownAt() == null ? null : asOf.knownAt().toString());
        return new RequestPerson(principal, display, admin, lens, moved, scopes, dates);
    }

    @Override
    public String toString() {
        return "Person[" + user() + (lens.isEmpty() ? "" : " lens " + lens) + " " + (dateBox() == null ? "live" : dateBox()) + "]";
    }
}
