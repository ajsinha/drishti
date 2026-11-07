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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.server.PinnedBusinessDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Totals over every part, whatever {@code max-parts} (2.9.7, VAR_GUIDE.md section 27a). A scope whose parts are positions, more of them than
 * {@code max-parts} (2 here): the headline counts every position and its {@code sumStandalone} and {@code diversification} are over all of
 * them; the contribution table lists the 2 largest ({@code truncated}, {@code total}, {@code shown}) and its {@code sumStandalone},
 * {@code sumComponent} and {@code diversification} are over all of them too. The scope's VaR and ES are its own, untouched by the parts.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-headline-parts/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.root=target/var-headline-parts/official",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.currencies[0]=USD", "drishti.var.max-parts=2",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarHeadlinePartsTest {

    static final Path BASE = Path.of("target", "var-headline-parts").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarHierarchyFixture.DAY;

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            VarHierarchyFixture.write(LAKE);
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), VarShardClusterTest.riskLake("delta", LAKE, BASE));
        } catch (Exception e) {
            throw new IllegalStateException("the lake cannot be written: " + e, e);
        }
    }

    @Autowired VarService svc;
    @Autowired VarViews views;

    @Test
    void theTotalsAreOverEveryPositionWhateverMaxParts() {
        VarBook b = svc.date(DAY).book();
        List<String> ids = new ArrayList<>();
        for (int p = 0; p < b.positionCount(); p++) {
            ids.add(b.positionId(p));
        }
        assertThat(ids).as("more positions than max-parts").hasSizeGreaterThan(2);
        String scope = "POS:" + String.join(",", ids);
        Map<String, Object> head = views.headline(null, scope, DAY);
        Map<String, Object> contrib = views.contributions(null, scope, DAY, "position", null);
        VarService.Scoped s = svc.scoped(null, scope, DAY, VarDate.USD);
        assertThat(head.get("var")).isEqualTo(VarViews.round(s.measures().var()));
        assertThat(head.get("es")).isEqualTo(VarViews.round(s.measures().es()));
        assertThat(head.get("childKind")).isEqualTo("position");
        assertThat(head.get("children")).as("every position, not max-parts").isEqualTo(ids.size());
        assertThat(contrib.get("truncated")).isEqualTo(true);
        assertThat(contrib.get("total")).isEqualTo(ids.size());
        assertThat(contrib.get("shown")).isEqualTo(2);
        assertThat((List<?>) contrib.get("rows")).hasSize(2);
        // the reference: every position's full measures and component, summed
        VarCalc calc = views.calc(s);
        double alone = 0;
        double comp = 0;
        for (int p = 0; p < b.positionCount(); p++) {
            double[] v = b.positionVector(p);
            alone += calc.measures(v).var();
            comp += com.ash.drishti.banking.compute.var.VarMath.component(v, s.measures().tail(), s.measures().scale());
        }
        assertThat(((Number) head.get("sumStandalone")).doubleValue()).isCloseTo(alone, org.assertj.core.api.Assertions.within(1.0));
        assertThat(head.get("sumStandalone")).isEqualTo(contrib.get("sumStandalone"));
        assertThat(head.get("diversification")).isEqualTo(contrib.get("diversification"));
        assertThat(((Number) contrib.get("sumComponent")).doubleValue()).isCloseTo(comp, org.assertj.core.api.Assertions.within(1.0));
        assertThat(((Number) contrib.get("sumComponent")).doubleValue()).as("the components of all the positions add up to the VaR")
                .isCloseTo(s.measures().var(), org.assertj.core.api.Assertions.within(1.0));
        // the listed rows alone sum to less: the totals are not theirs
        double listed = 0;
        for (VarViews.Part pt : views.parts(s, "position", null)) {
            listed += calc.measures(pt.vector()).var();
        }
        assertThat(listed).isLessThan(alone);
    }

    /**
     * The cached whole positions' standalone VaRs are the direct computation's to the bit, computed once per book and lens; a scope holding
     * a position in part measures that one on the fly, and its total is the listed parts' (2 positions: within max-parts) to the bit.
     */
    @Test
    void theCachedStandalonesAreTheDirectOnesAndAPartHeldPositionIsMeasuredNow() {
        VarBook b = svc.date(DAY).book();
        VarService.Scoped bank = svc.scoped(null, "NODE:MGMT~BANK", DAY, VarDate.USD);
        VarCalc calc = views.calc(bank);
        int[] all = java.util.stream.IntStream.range(0, b.positionCount()).toArray();
        java.util.concurrent.atomic.AtomicLongArray cached = svc.partTotals().wholes(bank, calc, views.lens(bank), svc.sums(), all);
        long made = svc.partTotals().computed();
        for (int p = 0; p < b.positionCount(); p++) {
            assertThat(cached.get(p)).as(b.positionId(p)).isEqualTo(Double.doubleToRawLongBits(calc.measures(b.positionVector(p)).var()));
        }
        assertThat(svc.partTotals().wholes(bank, calc, views.lens(bank), svc.sums(), all)).isSameAs(cached);
        assertThat(svc.partTotals().computed()).as("each position computed once per book and lens").isEqualTo(made);
        String scope = "TRD:T1,T3";                                       // T1 is half of the position of T1 and T2
        VarService.Scoped s = svc.scoped(null, scope, DAY, VarDate.USD);
        Map<String, Object> head = views.headline(null, scope, DAY);
        assertThat(svc.partTotals().computed()).as("no second computation for the same book and lens").isEqualTo(made);
        List<VarViews.Part> listed = views.parts(s, "position", null);
        assertThat(listed).hasSize(2).anyMatch(pt -> Boolean.TRUE.equals(pt.attrs().get("partial")));
        double direct = 0;
        for (VarViews.Part pt : listed) {
            direct += calc.measures(pt.vector()).var();
        }
        assertThat(head.get("children")).isEqualTo(2);
        assertThat(head.get("sumStandalone")).isEqualTo(VarViews.round(direct));
    }
}
