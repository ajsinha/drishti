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
package com.ash.drishti.banking.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.ScopeExpr;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.server.dist.ExportDoc;
import com.ash.drishti.server.dist.XlsxWriter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The basket sheet's API (VAR_GUIDE.md sections 3.5 and 11): <i>Construct your basket</i> is one sheet like Excel, a column per kind of
 * part; the console sends its columns as lists and renders what comes back. Every check and every figure is the server's.
 *
 * <pre>
 *   POST /api/v1/risk/var/basket/sheet {wholeBank, op: [...], TRD: [...], POS: [...], PORT: [...], BOOK: [...], DESK: [...], LL: [...], CPTY: [...]}
 *        every cell checked (found, or not found with the reason in words; a minus that takes nothing away, a + under the whole bank)
 *        and the basket's live VaR, ES, sum of the parts, diversification and trades
 *   GET  /api/v1/risk/var/basket/sheet?scope=      a scope laid into the sheet: a saved basket column by column (with whether the viewer may
 *        edit it), a portfolio's definition as a copy (a portfolio is the bank's), an ad-hoc scope's terms
 *   POST /api/v1/risk/var/basket/sheet.xlsx {sheet, name}   the sheet as a workbook: the Basket sheet (Op and the columns) and its checks
 * </pre>
 *
 * Each takes the business date ({@code asOf}) and the reporting currency ({@code ccy}) as every VaR answer does.
 */
@RestController
@RequestMapping("/api/v1/risk/var/basket")
public class VarBasketController {

    private final VarService svc;
    private final VarViews views;
    private final Entitlements entitlements;
    private final VarBasketCheck checks;

    VarBasketController(VarService svc, VarViews views, Entitlements entitlements) {
        this.svc = svc;
        this.views = views;
        this.entitlements = entitlements;
        this.checks = new VarBasketCheck(svc);
    }

    private static LocalDate date(AsOf asOf) {
        return asOf == null ? null : asOf.businessDate();
    }

    /** The sheet in a body: the sheet itself, or {@code {"sheet": {...}}}. */
    private VarBasketSheet sheet(Map<String, Object> body) {
        Object s = body != null && body.get("sheet") instanceof Map<?, ?> m ? m : body;
        return VarBasketSheet.read(s, svc.words().keySet());
    }

    /** Checks every cell of the sheet and computes the basket it composes, as the caller. */
    @PostMapping("/sheet")
    public Map<String, Object> check(@RequestBody Map<String, Object> body, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        return checks.check(p, sheet(body), date(asOf), ccy);
    }

    /** A scope laid into the sheet: a saved basket, a portfolio (as a copy), or an ad-hoc scope's terms. */
    @GetMapping("/sheet")
    public Map<String, Object> open(@RequestParam String scope, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        java.util.Set<String> words = svc.words().keySet();
        ScopeExpr e;
        try {
            e = ScopeExpr.parse(scope, words);
        } catch (IllegalArgumentException x) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, x.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> unplaced = new ArrayList<>();
        VarBasketSheet sheet;
        if (e instanceof ScopeExpr.Ref r && r.word().equals("BASKET")) {
            Map<String, Object> b = svc.saved().basket(p, r.arg())
                    .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + r.arg() + " you may read"));
            sheet = b.get("sheet") != null ? VarBasketSheet.read(b.get("sheet"), words)
                    : VarBasketSheet.fromTerms(views.selection().terms(p, String.valueOf(b.get("scope"))), words, unplaced);
            Map<String, Object> m = new LinkedHashMap<>();
            for (String f : List.of("id", "name", "owner", "version", "sharedWith", "updatedAt", "editedBy")) {
                m.put(f, b.get(f));
            }
            boolean own = String.valueOf(b.get("owner")).equals(p.user()) || String.valueOf(b.get("id")).startsWith(ContentDocs.name(p.user()) + "/");
            boolean official = Boolean.TRUE.equals(b.get("official"));
            m.put("fromSheet", b.get("sheet") != null);
            m.put("editable", !official && (own || entitlements.isAdmin(p)));
            m.put("own", own);
            out.put("basket", m);
        } else if (e instanceof ScopeExpr.Ref r && r.word().equals("PORT")) {
            VarDate.Portfolio pf = svc.portfolios(svc.date(date(asOf))).get(r.arg());
            if (pf == null) {
                throw new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_UNKNOWN, "no system basket " + r.arg() + " on the date: PORT <GO> lists them");
            }
            List<String> left = new ArrayList<>();
            VarBasketSheet copy = null;
            try {
                copy = VarBasketSheet.fromTerms(views.selection().terms(p, pf.scope()), words, left);
            } catch (IllegalArgumentException | DrishtiException x) {
                left.add(pf.scope());
            }
            // a portfolio its parts cannot be laid out as (a rule, a node of another kind): the copy holds the portfolio itself
            sheet = copy != null && left.isEmpty() ? copy : VarBasketSheet.fromTerms(List.of(new VarSelection.Term("+", r)), words, unplaced);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pf.id());
            m.put("name", pf.name());
            m.put("type", pf.type());
            boolean manage = entitlements.holds(p, VarSystemBasketEdit.PERMISSION);
            m.put("note", "a system basket is the bank's (VAR_GUIDE.md section 5.7): this is a copy you save as your own basket"
                    + (manage ? "; to change the system basket itself, fill in System basket below and save it" : ""));
            m.put("mayManage", manage);
            svc.saved().system().get(pf.id()).ifPresent(b -> m.put("systemBasket", b.toMap()));
            out.put("portfolio", m);
        } else {
            sheet = VarBasketSheet.fromTerms(views.selection().terms(p, scope), words, unplaced);
        }
        out.put("columns", checks.columns(svc.words()));
        out.put("sheet", sheet.toMap());
        out.put("unplaced", unplaced);
        if (!unplaced.isEmpty()) {
            out.put("notice", "the sheet has no column for " + String.join(", ", unplaced) + ": left out of the sheet");
        }
        return out;
    }

    /** The sheet as a workbook: the Basket sheet as laid out (Op and the columns, headed by kind), then the cells that need a word. */
    @PostMapping("/sheet.xlsx")
    public ResponseEntity<byte[]> workbook(@RequestBody Map<String, Object> body, @RequestParam(required = false) String ccy, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireOpen(p, svc.props().kinds().vector());
        VarBasketSheet sheet = sheet(body);
        Map<String, Object> answer = checks.check(p, sheet, date(asOf), ccy);
        Map<String, Object> m = sheet.toMap();
        List<String> heads = new ArrayList<>(List.of("Op"));
        List<String> keys = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        for (Map<String, Object> c : cols) {
            heads.add(c.get("title") + " (" + c.get("key") + ")");
            keys.add(String.valueOf(c.get("key")));
        }
        List<List<Object>> rows = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> ops = (List<String>) m.get(VarBasketSheet.OP);
        for (int r = 0; r < sheet.rows(); r++) {
            List<Object> row = new ArrayList<>();
            row.add("-".equals(ops.get(r)) ? "−" : "+");
            for (String k : keys) {
                @SuppressWarnings("unchecked")
                List<String> v = (List<String>) m.get(k);
                row.add(v == null || r >= v.size() ? "" : v.get(r));
            }
            rows.add(row);
        }
        List<List<Object>> flagged = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) answer.get("cells");
        for (Map<String, Object> c : cells) {
            flagged.add(List.of(((Number) c.get("row")).longValue() + 1, String.valueOf(c.get("column")), String.valueOf(c.get("text")),
                    String.valueOf(c.get("status")), String.valueOf(c.get("reason"))));
        }
        String name = body != null && body.get("name") != null ? String.valueOf(body.get("name")) : "basket";
        List<String> notes = new ArrayList<>();
        notes.add("Start from the whole bank: " + (sheet.wholeBank() ? "yes" : "no"));
        notes.add("VaR " + answer.get("var") + " · ES " + answer.get("es") + " · trades " + answer.get("trades") + " · " + answer.get("currency")
                + " on " + answer.get("businessDate"));
        notes.add("Paste the Basket sheet's columns back into Construct your basket to open it again.");
        ExportDoc doc = new ExportDoc("Basket " + name, "VaR basket, " + answer.get("businessDate"), notes, List.of(
                new ExportDoc.Section("basket", "Basket", ExportDoc.TABLE, heads, rows, null, List.of()),
                new ExportDoc.Section("checks", "Checks", ExportDoc.TABLE, List.of("Row", "Column", "Cell", "Status", "Reason"), flagged,
                        flagged.isEmpty() ? "every cell found" : null, List.of())));
        byte[] bytes = XlsxWriter.write(doc);
        String file = VarSaved.slug(name.isBlank() ? "basket" : name.replaceAll("[^A-Za-z0-9._-]+", "-")) + ".xlsx";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(bytes);
    }
}
