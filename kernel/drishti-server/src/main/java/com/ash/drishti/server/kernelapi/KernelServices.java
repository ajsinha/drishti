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
import com.ash.drishti.kernel.api.module.PersonCache;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Access;
import com.ash.drishti.kernel.api.services.BusinessDates;
import com.ash.drishti.kernel.api.services.History;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Lenses;
import com.ash.drishti.kernel.api.services.LiveQueries;
import com.ash.drishti.kernel.api.services.Operations;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.kernel.api.services.PersonSettings;
import com.ash.drishti.kernel.api.services.Pivots;
import com.ash.drishti.kernel.api.services.Records;
import com.ash.drishti.module.runtime.KernelPersonCache;
import com.ash.drishti.server.api.AsOfResolver;
import com.ash.drishti.server.edition.EditionContext;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.TokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The kernel's typed services, in process (KERNEL_AND_MODULES.md section 2.3): each answers what the REST path it replaces answers for
 * the same person, by the same body the controller calls, through the same doors ({@link KernelDoors}). {@link #person()} copies the
 * person of the request in hand; every service takes that person explicitly.
 */
@Component
public class KernelServices implements Kernel {

    private final com.ash.drishti.engine.time.BusinessDates calendar;
    private final Entitlements entitlements;
    private final ObjectProvider<com.ash.drishti.identity.UserService> users;
    private final KernelRecords records;
    private final KernelHistory history;
    private final KernelDates dates;
    private final KernelSettings settings;
    private final KernelLiveQueries live;
    private final KernelPivots pivots;
    private final KernelLenses lenses;
    private final KernelAccess access;
    private final KernelOperations operations;

    public KernelServices(com.ash.drishti.engine.time.BusinessDates calendar, Entitlements entitlements,
            ObjectProvider<com.ash.drishti.identity.UserService> users, KernelRecords records, KernelHistory history, KernelDates dates,
            KernelSettings settings, KernelLiveQueries live, KernelPivots pivots, KernelLenses lenses, KernelAccess access,
            KernelOperations operations) {
        this.calendar = calendar;
        this.entitlements = entitlements;
        this.users = users;
        this.records = records;
        this.history = history;
        this.dates = dates;
        this.settings = settings;
        this.live = live;
        this.pivots = pivots;
        this.lenses = lenses;
        this.access = access;
        this.operations = operations;
    }

    @Override
    public Person person() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) {
            throw new IllegalStateException("a person is the person of a request: there is no request on this thread");
        }
        HttpServletRequest req = a.getRequest();
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : Principal.anonymous("anonymous");
        AsOf asOf = AsOfResolver.read(req.getHeader(AsOfResolver.HEADER), req.getParameter("asOf"), req.getHeader(AsOfResolver.KNOWN_AT_HEADER),
                req.getParameter("knownAt"), calendar);
        java.util.List<String> scopes = req.getAttribute(TokenFilter.GRANT_ATTRIBUTE) instanceof TokenFilter.Grant g ? g.scopes() : null;
        return person(p, req.getHeader(EditionContext.HEADER), asOf, scopes);
    }

    /**
     * The person of an operation another node forwarded (KERNEL_AND_MODULES.md section 2.3): the identity its verified on-behalf-of claims
     * carry, and the lens, date box, known-at and token scopes the calling node's person had.
     */
    RequestPerson person(Principal p, String lens, String dateBox, String knownAt, java.util.List<String> scopes) {
        return person(p, lens, AsOfResolver.read(dateBox, null, knownAt, null, calendar), scopes);
    }

    private RequestPerson person(Principal p, String lens, AsOf asOf, java.util.List<String> scopes) {
        String display = "";
        com.ash.drishti.identity.UserService us = users.getIfAvailable();
        if (us != null) {
            display = us.find(p.user()).map(u -> u.displayName() == null ? "" : u.displayName()).orElse("");
        }
        return new RequestPerson(p, display, entitlements.isAdmin(p), lens, asOf, scopes, calendar);
    }

    @Override
    public Records records() {
        return records;
    }

    @Override
    public History history() {
        return history;
    }

    @Override
    public BusinessDates dates() {
        return dates;
    }

    @Override
    public PersonSettings settings() {
        return settings;
    }

    @Override
    public LiveQueries live() {
        return live;
    }

    @Override
    public Pivots pivots() {
        return pivots;
    }

    @Override
    public Lenses lenses() {
        return lenses;
    }

    @Override
    public Access access() {
        return access;
    }

    @Override
    public Operations operations() {
        return operations;
    }

    @Override
    public <V> PersonCache<V> cache(int size, long ttlMillis) {
        return new KernelPersonCache<>(size, ttlMillis);
    }
}
