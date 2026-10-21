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
package com.ash.drishti.server.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.server.bi.runs.RunProblem;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The render engine and its service (UI_BOARD.md 6 and 7, phase P3) over the seed chips and the 3.1 {@code book-trades} board, with suppliers that answer
 * per person: trace order, a pin change repainting only the details, one failing chip, every limit's code, the audit row, embed tokens and masked-id tokens.
 */
class RenderServiceTest {

    static final Path BOARD = Path.of("../drishti-rachana/src/test/resources/ui/book-trades.board.yaml");

    final List<String[]> audit = new CopyOnWriteArrayList<>();
    final List<BoardSuppliers.Ask> asked = new CopyOnWriteArrayList<>();
    volatile CountDownLatch hold;
    ExecutorService exec;
    ChipStore chips;
    BoardStore boards;
    IdTokens tokens;
    String text;

    final UiAccess access = new UiAccess() {
        public boolean mayView(Principal p) {
            return true;
        }

        public boolean maySave(Principal p) {
            return !p.user().startsWith("viewer");
        }

        public boolean mayAuthor(Principal p) {
            return false;
        }

        public boolean mayReview(Principal p) {
            return false;
        }

        public boolean mayCertify(Principal p) {
            return false;
        }

        public boolean isAdmin(Principal p) {
            return false;
        }

        public String workspaceRole(String workspace, Principal p) {
            return null;
        }

        @Override
        public void audit(Principal p, String action, String subject, String detail) {
            audit.add(new String[] {p.user(), action, subject, detail});
        }
    };

    /** Trades of FX-LDN-01 per person: ann has MTM masked, hana sees T-1 only, mia's TRADE_ID is masked; bob's flows fail. */
    final BoardSuppliers suppliers = (who, ask) -> {
        asked.add(ask);
        if (hold != null) {
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String u = who.user();
        String name = ask.supply().name();
        if (name.equals("trading/trade-search")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                if (u.equals("hana") && i > 1) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("TRADE_ID", u.equals("mia") ? BoardRender.MASK : "T-" + i);
                r.put("TRADE_DATE", "2026-10-0" + i);
                r.put("COUNTERPARTY_NAME", "CP-" + i);
                r.put("NOTIONAL", 1000.0 * i);
                r.put("MTM_USD", u.equals("ann") ? BoardRender.MASK : 10.0 * i);
                rows.add(r);
                refs.add("T-" + i);
            }
            Set<String> masked = u.equals("ann") ? Set.of("MTM_USD") : u.equals("mia") ? Set.of("TRADE_ID") : Set.of();
            return new BoardSuppliers.Rows(List.copyOf(rows.get(0).keySet()), rows, refs, masked, rows.size(), null, false, null);
        }
        if (name.equals("trading/trade")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("TRADE_ID", ask.id());
            r.put("DEAL_NUMBER", "D-" + ask.id());
            r.put("NOTIONAL", 5.0);
            return BoardSuppliers.Rows.of(List.copyOf(r.keySet()), List.of(r), Set.of());
        }
        if (u.equals("bob")) {
            throw new DrishtiException(ErrorCode.QUERY_MASKED, "the flows are not for bob");
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("cashflows.PAY_DATE", "2026-11-01");
        f.put("cashflows.CCY", "USD");
        f.put("FLOW_AMOUNT", 12.5);
        f.put("cashflows.TRADE_ID", ask.filters().getOrDefault("cashflows.TRADE_ID", "T-1"));
        return BoardSuppliers.Rows.of(List.copyOf(f.keySet()), List.of(f), Set.of());
    };

    @BeforeEach
    void setUp() throws Exception {
        exec = Executors.newVirtualThreadPerTaskExecutor();
        Path root = Files.createDirectories(Path.of("target", "ui-render-tests", UUID.randomUUID().toString()));
        FileContentStore store = new FileContentStore(root.resolve("store"));
        UiSettings settings = new UiSettings(65536, 40, 120, 500, 3, List.of(), Path.of("../config/ui/chips"));
        chips = new ChipStore(store, access, settings.chipsDir());
        boards = new BoardStore(store, chips, access, settings);
        tokens = new IdTokens(Clock.systemUTC(), Duration.ofMinutes(5));
        text = Files.readString(BOARD);
    }

    @AfterEach
    void tearDown() {
        if (hold != null) {
            hold.countDown();
        }
        exec.shutdownNow();
    }

    RenderService service(RenderSettings s) {
        return new RenderService(chips, boards, access, new UiSettings(s.bytes(), s.chips(), s.traces(), 500, 3, List.of(), Path.of("../config/ui/chips")),
                s, suppliers, tokens, exec, null);
    }

    RenderService service() {
        return service(RenderSettings.defaults());
    }

    /** A collected stream. */
    static final class Events {
        final List<Object[]> all = new CopyOnWriteArrayList<>();

        synchronized void add(String name, Object data) {
            all.add(new Object[] {name, data});
        }

        List<String> names() {
            return all.stream().map(e -> (String) e[0]).toList();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chips(String inst) {
            return all.stream().filter(e -> e[0].equals("chip") && inst.equals(((Map<String, Object>) e[1]).get("instance")))
                    .map(e -> (Map<String, Object>) e[1]).toList();
        }

        @SuppressWarnings("unchecked")
        int indexOf(String inst) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i)[0].equals("chip") && inst.equals(((Map<String, Object>) all.get(i)[1]).get("instance"))) {
                    return i;
                }
            }
            return -1;
        }
    }

    record Rendered(RenderService.Started started, Events events) {}

    Rendered render(RenderService svc, String user) throws Exception {
        Events ev = new Events();
        RenderService.Started s = svc.dynamic(new Principal(user, List.of("author")), text, Map.of(), "desktop", ev::add, null);
        s.done().get(20, TimeUnit.SECONDS);
        return new Rendered(s, ev);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> vm(Map<String, Object> chipEvent) {
        return (Map<String, Object>) chipEvent.get("viewmodel");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> chipEvent) {
        return (List<Map<String, Object>>) ((Map<String, Object>) vm(chipEvent).get("pins")).get("rows");
    }

    @Test
    void theLayoutComesFirstThenEachChipTheDetailAfterItsMasterThenDone() throws Exception {
        Rendered r = render(service(), "rita");
        List<String> names = r.events.names();
        assertThat(names.subList(0, 2)).containsExactly("render", "layout");
        assertThat(names.get(names.size() - 1)).isEqualTo("done");
        assertThat(r.events.indexOf("ticket")).isGreaterThan(r.events.indexOf("trades"));
        assertThat(r.events.indexOf("flows")).isGreaterThan(r.events.indexOf("trades"));
        assertThat(r.events.chips("trades").get(0)).containsEntry("state", "data");
        assertThat(rows(r.events.chips("trades").get(0))).hasSize(3);
        assertThat(r.events.chips("ticket").get(0)).containsEntry("state", "waiting");          // nothing selected yet
        // the master's query took the board parameter's default
        assertThat(asked.stream().filter(a -> a.supply().name().equals("trading/trade-search")).findFirst().orElseThrow().query().toString())
                .contains("FX-LDN-01");
    }

    @Test
    void aSelectionRepaintsOnlyTheDetailsWithTheSelectedTrade() throws Exception {
        Rendered r = render(service(), "rita");
        int before = r.events.all.size();
        BoardRender.Outcome o = r.started.render().pin("trades", "selected", "T-2").get(10, TimeUnit.SECONDS);
        assertThat(o.chips()).isEqualTo(2);
        List<Object[]> after = r.events.all.subList(before, r.events.all.size());
        assertThat(after.stream().map(e -> e[0]).toList()).containsExactly("chip", "chip", "done");
        assertThat(r.events.chips("trades")).hasSize(1);                                        // the master is not painted again
        Map<String, Object> ticket = r.events.chips("ticket").get(1);
        assertThat(ticket).containsEntry("state", "data");
        assertThat(((Map<?, ?>) ((Map<?, ?>) vm(ticket).get("pins")).get("doc")).get("id")).isEqualTo("T-2");
        assertThat(rows(r.events.chips("flows").get(1)).get(0)).containsEntry("trade", "T-2");
        assertThat(asked).anyMatch(a -> a.supply().name().equals("trading-risk") && "T-2".equals(a.filters().get("cashflows.TRADE_ID")));
    }

    @Test
    void masksStayMaskedAndRestrictedRowsAreAbsent() throws Exception {
        RenderService svc = service();
        List<Map<String, Object>> ann = rows(render(svc, "ann").events.chips("trades").get(0));
        assertThat(ann).allSatisfy(row -> assertThat(row.get("measures").toString()).contains("MTM_USD, value=" + BoardRender.MASK));
        List<Map<String, Object>> hana = rows(render(svc, "hana").events.chips("trades").get(0));
        assertThat(hana).hasSize(1);
        assertThat(hana.get(0)).containsEntry("id", "T-1");
    }

    @Test
    void aMaskedIdTravelsAsATokenOnlyItsPersonResolves() throws Exception {
        Rendered r = render(service(), "mia");
        Map<String, Object> trades = r.events.chips("trades").get(0);
        List<?> keys = (List<?>) vm(trades).get("keys");
        assertThat(keys).allMatch(IdTokens::isToken);
        assertThat(rows(trades).get(0)).containsEntry("id", BoardRender.MASK);
        String token = (String) keys.get(1);
        assertThat(token).doesNotContain("T-2");
        r.started.render().pin("trades", "selected", token).get(10, TimeUnit.SECONDS);
        assertThat(asked).anyMatch(a -> a.supply().name().equals("trading/trade") && "T-2".equals(a.id()));
        assertThatThrownBy(() -> tokens.resolve("rita", token)).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.UI_FORBIDDEN));
    }

    @Test
    void oneFailingChipLeavesTheOthersPaintedWithItsProblem() throws Exception {
        Rendered r = render(service(), "bob");
        Map<String, Object> flows = r.events.chips("flows").get(0);
        assertThat(flows).containsEntry("state", "error").containsEntry("code", ErrorCode.QUERY_MASKED.code());
        assertThat(r.events.chips("trades").get(0)).containsEntry("state", "data");
        assertThat(r.started.done().get().errors()).isEqualTo(1);
    }

    @Test
    void aSlowSupplierIsTheChipsTimeoutNotTheBoards() throws Exception {
        RenderSettings d = RenderSettings.defaults();
        RenderSettings fast = new RenderSettings(d.bytes(), d.chips(), d.traces(), d.moduleDepth(), d.rowsPerChip(), Duration.ofMillis(200),
                d.boardBudget(), d.dynamicPerMinute(), d.dynamicAtOnce(), d.sessions(), d.idle(), d.reuse(), d.keepDynamic());
        hold = new CountDownLatch(1);
        Rendered r = render(service(fast), "rita");
        assertThat(r.events.chips("trades").get(0)).containsEntry("code", ErrorCode.UI_SUPPLIER_TIMEOUT.code());
    }

    @Test
    void everyLimitAnswersItsCode() throws Exception {
        RenderSettings d = RenderSettings.defaults();
        // chips per board and bytes: DRS-2075
        code(() -> render(service(d.with(30, 4, 8, 2)), "rita"), ErrorCode.UI_TOO_LARGE);
        RenderSettings tiny = new RenderSettings(100, d.chips(), d.traces(), d.moduleDepth(), d.rowsPerChip(), d.chipBudget(), d.boardBudget(),
                d.dynamicPerMinute(), d.dynamicAtOnce(), d.sessions(), d.idle(), d.reuse(), d.keepDynamic());
        code(() -> render(service(tiny), "rita"), ErrorCode.UI_TOO_LARGE);
        // renders a minute: DRS-2076 with Retry-After
        RenderService perMinute = service(d.with(2, 4, 8, 40));
        render(perMinute, "rita");
        render(perMinute, "rita");
        assertThatThrownBy(() -> render(perMinute, "rita")).isInstanceOfSatisfying(RunProblem.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.UI_RATE_LIMITED);
            assertThat(e.retryAfterSeconds()).isPositive();
        });
        render(perMinute, "rob");                                                                // per person
        // at once: DRS-2076
        RenderService atOnce = service(d.with(30, 1, 8, 40));
        hold = new CountDownLatch(1);
        atOnce.dynamic(new Principal("rita", List.of("author")), text, Map.of(), "desktop", (n, x) -> {}, null);
        code(() -> atOnce.dynamic(new Principal("rita", List.of("author")), text, Map.of(), "desktop", (n, x) -> {}, null), ErrorCode.UI_RATE_LIMITED);
        hold.countDown();
        hold = null;
        // sessions per person: the oldest ends, DRS-2079 on it
        RenderService sessions = service(d.with(30, 4, 1, 40));
        Rendered first = render(sessions, "rita");
        render(sessions, "rita");
        assertThat(first.events.names()).contains("ended");
        code(() -> sessions.pin(new Principal("rita", List.of()), first.started.render().id(), "trades", "selected", "T-1"), ErrorCode.UI_RENDER_ENDED);
        // ended by its owner; another person may not touch it
        RenderService svc = service();
        Rendered mine = render(svc, "rita");
        code(() -> svc.pin(new Principal("rob", List.of()), mine.started.render().id(), "trades", "selected", "T-1"), ErrorCode.UI_FORBIDDEN);
        svc.end(new Principal("rita", List.of()), mine.started.render().id());
        code(() -> svc.pin(new Principal("rita", List.of()), mine.started.render().id(), "trades", "selected", "T-1"), ErrorCode.UI_RENDER_ENDED);
        // the power: DRS-2074; the check: DRS-2071 with the problems
        code(() -> render(svc, "viewer-vic"), ErrorCode.UI_FORBIDDEN);
        text = text.replace("to:   ticket.id", "to:   ticket.nope");
        assertThatThrownBy(() -> render(svc, "rita")).isInstanceOfSatisfying(RunProblem.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.UI_CHECK_FAILED);
            assertThat(e.properties().get("problems").toString()).contains("BC-06");
        });
    }

    @Test
    void theAuditRowNamesTheBoardChipsAndSuppliersAndNoValue() throws Exception {
        render(service(), "rita");
        assertThat(audit).hasSize(1);
        String[] row = audit.get(0);
        assertThat(row[0]).isEqualTo("rita");
        assertThat(row[1]).isEqualTo("ui.render.dynamic");
        assertThat(row[2]).startsWith("dynamic:sha256:").isEqualTo("dynamic:sha256:" + RenderService.sha(text));
        assertThat(row[3]).contains("trades=filterable-table@2").contains("connector:trading/trade-search").contains("model:trading-risk").contains("failed=0");
        for (String value : List.of("T-1", "CP-1", "1000", "FX-LDN-01", "12.5", "USD")) {
            assertThat(String.join("|", row)).doesNotContain(value);
        }
    }

    @Test
    void anEmbedTokenRendersSavedBoardsOnlyIn270() throws Exception {
        RenderService svc = service();
        Principal embedded = new Principal("rita", List.of("author"), "host-app");
        code(() -> svc.dynamic(embedded, text, Map.of(), "desktop", (n, x) -> {}, null), ErrorCode.UI_FORBIDDEN);
        BoardStore.Ref ref = new BoardStore.Ref(BoardStore.USER, "rita", "book-trades");
        boards.save(ref, text, null, new Principal("rita", List.of("author")));
        Events ev = new Events();
        RenderService.Started s = svc.saved(embedded, ref, Map.of(), "phone", ev::add, null);
        s.done().get(20, TimeUnit.SECONDS);
        assertThat(ev.chips("trades").get(0)).containsEntry("state", "data");
        assertThat(((Map<?, ?>) ev.all.get(1)[1]).get("order")).isEqualTo(List.of("trades", "ticket", "flows"));
        assertThat(audit.get(audit.size() - 1)[1]).isEqualTo("ui.render");
        assertThat(audit.get(audit.size() - 1)[2]).isEqualTo("user:rita:book-trades");
        // a board one may not open is DRS-2073
        code(() -> svc.saved(new Principal("rob", List.of("author")), ref, Map.of(), "desktop", (n, x) -> {}, null), ErrorCode.UI_BOARD_NOT_FOUND);
    }

    @Test
    void aBoardParameterReachesTheSupplyQuery() throws Exception {
        Events ev = new Events();
        service().dynamic(new Principal("rita", List.of("author")), text, Map.of("book", "RATES-NY-02"), "desktop", ev::add, null)
                .done().get(20, TimeUnit.SECONDS);
        assertThat(asked.stream().filter(a -> a.supply().kind() == BoardDoc.SupplyKind.CONNECTOR).findFirst().orElseThrow().query().toString())
                .contains("RATES-NY-02");
    }

    interface Call {
        void run() throws Exception;
    }

    static void code(Call c, ErrorCode expected) {
        assertThatThrownBy(c::run).isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
