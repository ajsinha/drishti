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
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A structured search as a person may see it: the body of {@code GET /api/v1/search}, called by {@link SearchController} and by the
 * kernel's typed {@code Records.search} alike (KERNEL_AND_MODULES.md section 2.3), so the two answer the same. The kind must be open to
 * the person and on in the edition; their row rule joins the condition; each document is evaluated as they may see it (redacted).
 */
@Component
public class Searches {

    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final SearchAnswers answers;
    private com.ash.drishti.server.edition.EditionCommands edition = com.ash.drishti.server.edition.EditionCommands.NONE;
    private ObjectProvider<com.ash.drishti.server.net.SearchScatter> scatter;

    public Searches(StructuredSearch search, Entitlements entitlements, SearchAnswers answers) {
        this.search = search;
        this.entitlements = entitlements;
        this.answers = answers;
    }

    /** The edition's command allow-list (LENSES.md › The design); none: every command shown. */
    @Autowired(required = false)
    public void setEdition(com.ash.drishti.server.edition.EditionCommands edition) {
        this.edition = edition;
    }

    /** Drishti*net's scatter-gather (absent in a test that makes this class itself). */
    @Autowired(required = false)
    void scatter(ObjectProvider<com.ash.drishti.server.net.SearchScatter> s) {
        this.scatter = s;
    }

    /** The search on the person's date: this node's share and, in a cluster, every peer's that holds other data of the kind, merged. */
    public Map<String, Object> search(String q, AsOf asOf, Principal principal) {
        SearchQuery query = SearchQuery.pick(q);
        Map<String, Object> out = share(q, asOf, principal);
        // Drishti*net: the same search on every peer that holds other data of the kind (shards, not replicas), merged as one answer
        com.ash.drishti.server.net.SearchScatter sc = scatter == null ? null : scatter.getIfAvailable();
        if (sc == null) {
            out.remove(com.ash.drishti.server.net.SearchScatter.MATCHED_IDS);
            return out;
        }
        return sc.complete(out, (String) out.get("kind"), q, asOf, principal, query.descending(), query.limit(), entitlements.isAdmin(principal));
    }

    /**
     * The search answered from this node's own data, with the ids of every match ({@code matchedIds}, up to
     * {@link StructuredSearch#MATCHED_IDS}; absent when there are more) so that a merge across nodes counts an entity two nodes matched
     * once. A peer's share of a scattered search ({@code GET /api/v1/net/search}).
     */
    public Map<String, Object> share(String q, AsOf asOf, Principal principal) {
        SearchQuery query = SearchQuery.pick(q);           // TRD where …, TRD MX-200000, TRD productType=Revolver, TRD
        String kind = search.kindOf(query);
        entitlements.requireOpen(principal, kind);
        edition.requireKind(kind);
        // the person's row rule on the kind joins the condition (KERNEL_AND_MODULES.md section 10): a search finds only the records they may see
        java.util.Optional<com.ash.drishti.engine.source.RowPredicate> rule = entitlements.rowRule(principal, kind);
        if (rule.isPresent()) {
            String c = query.condition();
            query = new SearchQuery(query.head(), c == null || c.isBlank() ? rule.get().el() : "(" + c + ") && (" + rule.get().el() + ")",
                    query.orderBy(), query.descending(), query.limit(), query.fields(), query.idPattern());
        }
        StructuredSearch.Result r = search.run(query, asOf, data -> entitlements.redact(principal, data));
        Map<String, Object> out = answers.summary(q, query, r, asOf);
        out.put("rows", r.rows());
        if (r.listsMatches()) {
            out.put(com.ash.drishti.server.net.SearchScatter.MATCHED_IDS, r.matchedIds());
        }
        return out;
    }
}
