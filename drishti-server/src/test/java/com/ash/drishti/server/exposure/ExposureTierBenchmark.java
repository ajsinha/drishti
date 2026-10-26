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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureHotFile;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.compute.ccr.ExposureScopes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The exposure engine's tiered store at a measurable size (EXPOSURE_GUIDE.md section 27.4), modelled on VaR's {@code VarTierBenchmark}:
 * {@code T} synthetic trades in netting sets of about 10 (one set of up to 1,000 trades, counterparties of 20 sets, half the sets under
 * a margined CSA with a 10-day look-back), {@code P} paths and {@code G} grid dates (half primary, each with its look-back point), held in
 * memory as float32 (the lake's rows), then one business date loaded as the server does (each row added into its set's value matrix,
 * collateral, E and N, the hot file written and mapped). Not run by default; it writes one hot file under
 * {@code drishti.ccr.bench.dir} and deletes it at the end.
 *
 * <pre>
 *   measured this way:  ./mvnw -o test -pl drishti-server -Dtest=ExposureTierBenchmark -Dsurefire.failIfNoSpecifiedTests=false
 *       -Ddrishti.ccr.bench.trades=10000 -Ddrishti.ccr.bench.paths=200 -Ddrishti.ccr.bench.grid=24 -DargLine=-Xmx4g
 *   (optional) -Ddrishti.ccr.bench.trades-per-set=10 -Ddrishti.ccr.bench.sets-per-cpty=20 -Ddrishti.ccr.bench.big-set=1000
 *       -Ddrishti.ccr.bench.dir=/local/ccr-bench
 * </pre>
 *
 * Prints one line per measure: the load (rows into the value matrices; collateral, E, N and the hot file), reopening the date from
 * its file, the scopes (the bank, a counterparty of 20 sets, one set; the counterparty uncollateralised and at 99%), contributions by
 * netting set and by trade (the trades' paths from memory: the wanted rows only, and the whole date's rows streamed once as the
 * server reads the lake), the exact pre-deal recomputation of a 1,000-trade set in Java (what the Python engine does per candidate),
 * the proxy (addon), the heap of the open date and the disk.
 */
@EnabledIfSystemProperty(named = "drishti.ccr.bench.trades", matches = "\\d+")
class ExposureTierBenchmark {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    private static final String[] CLASSES = {"Rates", "FX", "Credit", "Equity", "Commodity"};

    private static double ms(long t0) {
        return (System.nanoTime() - t0) / 1e6;
    }

    private static long heapUsed() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double best(Runnable r) {
        r.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r.run();
            best = Math.min(best, ms(t0));
        }
        return best;
    }

    /** G grid dates: G/2 primary dates from 1 week to 10 years, each with a look-back point up to 10 days before it. */
    static ExposureGrid grid(int g) {
        int n = Math.max(1, g / 2);
        List<LocalDate> dates = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<Integer> lb = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < n; i++) {
            double f = n == 1 ? 1 : (double) i / (n - 1);
            int day = Math.max(prev + 2, (int) Math.round(7 + 3643 * f * f));
            int back = Math.max(1, Math.min(10, (day - prev) / 2));
            dates.add(DAY.plusDays(day - back));
            roles.add("LOOKBACK");
            lb.add(dates.size());
            dates.add(DAY.plusDays(day));
            roles.add("PRIMARY");
            lb.add(-1);
            prev = day;
        }
        return ExposureGrid.of(DAY, dates, roles, lb, null);
    }

    /** The synthetic trades: their columns and their paths ({@code G x P} float32, as the lake holds them). */
    static final class Bank {
        final int trades;
        final int p;
        final ExposureGrid g;
        final String[] id;
        final String[] set;
        final String[] cpty;
        final String[] cls;
        final double[] notional;
        final LocalDate[] maturity;
        final float[][] paths;
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, CsaTerms> csa = new HashMap<>();
        final List<String> sets = new ArrayList<>();

        Bank(int trades, int p, ExposureGrid g, int perSet, int setsPerCpty, int bigSet, long seed) {
            this.trades = trades;
            this.p = p;
            this.g = g;
            id = new String[trades];
            set = new String[trades];
            cpty = new String[trades];
            cls = new String[trades];
            notional = new double[trades];
            maturity = new LocalDate[trades];
            paths = new float[trades][];
            Random r = new Random(seed);
            int gs = g.size();
            double[] t = new double[gs];
            for (int k = 0; k < gs; k++) {
                t[k] = g.time(k);
            }
            // five factors (one per asset class), each a Brownian walk on the grid per path
            double[][] w = new double[CLASSES.length][gs * p];
            for (double[] f : w) {
                for (int j = 0; j < p; j++) {
                    double x = 0;
                    double prev = 0;
                    for (int k = 0; k < gs; k++) {
                        x += Math.sqrt(t[k] - prev) * r.nextGaussian();
                        prev = t[k];
                        f[k * p + j] = x;
                    }
                }
            }
            int big = Math.min(bigSet, trades / 10);
            int s = 0;
            int inSet = 0;
            for (int i = 0; i < trades; i++) {
                int size = s == 0 ? big : perSet;
                if (inSet >= size) {
                    s++;
                    inSet = 0;
                }
                inSet++;
                id[i] = "TRD-%07d".formatted(i);
                set[i] = "NS-%06d".formatted(s);
                cpty[i] = "CP-%05d".formatted(s / setsPerCpty);
                int c = r.nextInt(CLASSES.length);
                cls[i] = CLASSES[c];
                notional[i] = 1e6 * (1 + r.nextInt(50));
                double life = 0.25 + r.nextDouble() * 9.75;
                maturity[i] = DAY.plusDays((long) (life * 365));
                double vol = notional[i] * (0.01 + 0.05 * r.nextDouble()) * (r.nextBoolean() ? 1 : -1);
                double m0 = notional[i] * 0.01 * r.nextGaussian();
                float[] m = new float[gs * p];
                for (int k = 0; k < gs; k++) {
                    if (t[k] > life) {
                        break;                                                // zero after maturity
                    }
                    double decay = Math.sqrt(1 - t[k] / life);
                    for (int j = 0; j < p; j++) {
                        m[k * p + j] = (float) (m0 + vol * decay * (w[c][k * p + j] + 0.3 * r.nextGaussian() * Math.sqrt(t[k])));
                    }
                }
                paths[i] = m;
                index.put(id[i], i);
            }
            for (int i = 0; i < trades; i++) {
                if (!csa.containsKey(set[i])) {
                    sets.add(set[i]);
                    boolean margined = (sets.size() & 1) == 0;
                    csa.put(set[i], margined ? new CsaTerms("CSA-" + set[i], 5e6, 5e6, 0.5e6, 0.5e6, 0, 0.1e6, 10, 1, true, 0, 0) : CsaTerms.NONE);
                }
            }
        }

        /** The paths of the wanted trades, the wanted rows only (an index lookup per trade). */
        Map<String, double[]> read(Collection<String> ids) {
            Map<String, double[]> out = new HashMap<>();
            for (String x : ids) {
                float[] m = paths[index.get(x)];
                double[] d = new double[m.length];
                for (int i = 0; i < m.length; i++) {
                    d[i] = m[i];
                }
                out.put(x, d);
            }
            return out;
        }

        /** As the server reads the lake: every row of the date streamed once, those of the wanted trades kept. */
        Map<String, double[]> scan(Collection<String> ids) {
            Set<String> want = new HashSet<>(ids);
            Map<String, double[]> out = new HashMap<>();
            float[] row = new float[p];
            int gs = g.size();
            for (int i = 0; i < trades; i++) {
                for (int k = 0; k < gs; k++) {
                    System.arraycopy(paths[i], k * p, row, 0, p);             // the row decoded
                    if (!want.contains(id[i])) {
                        continue;
                    }
                    double[] m = out.computeIfAbsent(id[i], x -> new double[gs * p]);
                    for (int j = 0; j < p; j++) {
                        m[k * p + j] = row[j];
                    }
                }
            }
            return out;
        }
    }

    @Test
    void exposureStoreAtScale() throws IOException {
        int trades = Integer.getInteger("drishti.ccr.bench.trades");
        int p = Integer.getInteger("drishti.ccr.bench.paths", 200);
        int gsize = Integer.getInteger("drishti.ccr.bench.grid", 24);
        int perSet = Integer.getInteger("drishti.ccr.bench.trades-per-set", 10);
        int setsPerCpty = Integer.getInteger("drishti.ccr.bench.sets-per-cpty", 20);
        int bigSet = Integer.getInteger("drishti.ccr.bench.big-set", 1000);
        Path dir = Path.of(System.getProperty("drishti.ccr.bench.dir", "target/ccr-tier-benchmark")).toAbsolutePath();
        Files.createDirectories(dir);
        ExposureGrid g = grid(gsize);
        int gs = g.size();
        int gp = g.primaryCount();
        long t0 = System.nanoTime();
        Bank bank = new Bank(trades, p, g, perSet, setsPerCpty, bigSet, 7);
        double gen = ms(t0);
        System.out.printf("%nExposure tiered store benchmark: %,d trades in %,d netting sets (%d trades a set, the first %,d), %d sets a counterparty; "
                + "P = %,d paths, G = %d grid dates (%d primary); %d threads; hot file in %s%n", trades, bank.sets.size(), perSet,
                Math.min(bigSet, trades / 10), setsPerCpty, p, gs, gp, Runtime.getRuntime().availableProcessors(), dir);
        System.out.printf("  the trades' paths generated in memory (float32, as the lake holds them): %,.0f MB in %,.0f ms%n",
                (double) trades * gs * p * 4 / 1e6, gen);
        Path file = dir.resolve(DAY + ExposureHotFile.SUFFIX);
        ExposureBook.Reference ref = new ExposureBook.Reference() {
            @Override
            public CsaTerms terms(String set) {
                return bank.csa.getOrDefault(set, CsaTerms.NONE);
            }

            @Override
            public double mtm0(String trade) {
                return Double.NaN;
            }
        };
        long heap0 = heapUsed();
        ExposureBook book = null;
        try {
            // the load: each row (a trade at a grid date) into its set's value matrix, in float64; then the sets' collateral, E, N and the file
            t0 = System.nanoTime();
            ExposureBook.Builder b = new ExposureBook.Builder(g, p, "BENCH-RUN", "BENCH-BATCH", Map.of(), ExposureMath.Model.LAGGED, 0);
            double[] values = new double[p];
            for (int i = 0; i < trades; i++) {
                String[] cols = {bank.cpty[i], "LE-A", "DESK-" + (i % 40), "BOOK-" + (i % 400), bank.cls[i], "SWAP", "BENCH"};
                for (int k = 0; k < gs; k++) {
                    float[] m = bank.paths[i];
                    for (int j = 0; j < p; j++) {
                        values[j] = m[k * p + j];
                    }
                    b.row(bank.id[i], bank.set[i], cols, bank.maturity[i].toString(), bank.notional[i], k, values, p);
                }
            }
            double rows = ms(t0);
            long builderBytes = (long) b.sets() * gs * p * Double.BYTES;
            t0 = System.nanoTime();
            book = b.build(ref, file, "bench", Map.of());
            double build = ms(t0);
            b = null;
            long hotBytes = Files.size(file);
            System.out.printf("  load a date: %,d rows into %,d value matrices %,.0f ms (float64 while loading: %,.0f MB); "
                    + "collateral, E, N and the hot file written and mapped %,.0f ms; hot file %,.1f MB%n", (long) trades * gs, book.sets().size(), rows,
                    builderBytes / 1e6, build, hotBytes / 1e6);
            long heapOpen = heapUsed() - heap0;

            double reopen = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long t1 = System.nanoTime();
                ExposureBook h = ExposureBook.open(file);
                reopen = Math.min(reopen, ms(t1));
                h.close();
            }
            System.out.printf("  reopen the date from its hot file (map it; read its sets and trades): %,.1f ms%n", reopen);

            ExposureBook bk = book;
            ExposureScopes scopes = new ExposureScopes(bk, null);
            ExposureCalc.Params p95 = new ExposureCalc.Params(0.95, ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, new double[] {0.95, 0.99}, 21, 0);
            ExposureCalc.Params none = new ExposureCalc.Params(0.95, ExposureMath.Model.NONE, ExposureMath.Rule.KTH, new double[] {0.95, 0.99}, 21, 0);
            ExposureCalc.Params p99 = new ExposureCalc.Params(0.99, ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, new double[] {0.95, 0.99}, 21, 0);
            ExposureBook.TradePaths direct = bank::read;
            String cpty = "CP-%05d".formatted(1);                               // sets setsPerCpty .. 2 setsPerCpty - 1: ordinary sets
            String one = bank.set[bank.trades - 1];
            String bigId = bank.set[0];
            var bankScope = scopes.resolve("BANK");
            var cptyScope = scopes.resolve("CPTY:" + cpty);
            var setScope = scopes.resolve("NSET:" + one);
            var bigScope = scopes.resolve("NSET:" + bigId);
            int cptySets = (int) bk.sets().stream().filter(s -> s.counterparty().equals(cpty)).count();
            double bankMs = best(() -> ExposureCalc.evaluate(bk, bankScope.trades(), false, p95, direct));
            double cptyMs = best(() -> ExposureCalc.evaluate(bk, cptyScope.trades(), false, p95, direct));
            double setMs = best(() -> ExposureCalc.evaluate(bk, setScope.trades(), false, p95, direct));
            double cptyNone = best(() -> ExposureCalc.evaluate(bk, cptyScope.trades(), false, none, direct));
            double cpty99 = best(() -> ExposureCalc.evaluate(bk, cptyScope.trades(), false, p99, direct));
            double resolve = best(() -> scopes.resolve("CPTY:" + cpty));
            System.out.printf("  scopes (resolve + sum E, N, max(V,0) + statistics): the bank (%,d sets) %,.1f ms; a counterparty of %d sets %,.2f ms; "
                    + "one set %,.2f ms; resolving CPTY: %,.3f ms%n", bk.sets().size(), bankMs, cptySets, cptyMs, setMs, resolve);
            System.out.printf("  the counterparty uncollateralised (each set recomputed from V) %,.2f ms; at q = 99%% %,.2f ms%n", cptyNone, cpty99);

            ExposureCalc.Result cr = ExposureCalc.evaluate(bk, cptyScope.trades(), false, p95, direct);
            double bySet = best(() -> ExposureCalc.contributions(cr, ExposureCalc.parts(cr, "netting-set", direct), "PFE", -1));
            ExposureCalc.Result whole = ExposureCalc.evaluate(bk, bankScope.trades(), false, p95, direct);
            double bankBySetEe = best(() -> ExposureCalc.contributions(whole, ExposureCalc.parts(whole, "netting-set", direct), "EE", -1));
            double bankBySetPfe = best(() -> ExposureCalc.contributions(whole, ExposureCalc.parts(whole, "netting-set", direct), "PFE", -1));
            ExposureCalc.Result br = ExposureCalc.evaluate(bk, bigScope.trades(), false, p95, direct);
            int bigTrades = bk.set(br.pieces.get(0).set).to() - bk.set(br.pieces.get(0).set).from();
            List<String> bigIds = new ArrayList<>();
            br.trades.forEach(t -> bigIds.add(bk.trade(t).id()));
            double readDirect = best(() -> bank.read(bigIds));
            double readScan = best(() -> bank.scan(bigIds));
            Map<String, double[]> held = bank.read(bigIds);
            double byTradeCalc = best(() -> ExposureCalc.contributions(br, ExposureCalc.parts(br, "trade", ids -> held), "PFE", -1));
            double byTradeEe = best(() -> ExposureCalc.contributions(br, ExposureCalc.parts(br, "trade", ids -> held), "EE", -1));
            System.out.printf("  contributions by netting set of the counterparty (%d parts, PFE with each part's incremental): %,.2f ms; of the bank "
                    + "(%,d parts): EE %,.1f ms, PFE with incrementals %,.1f ms%n", cptySets, bySet, bk.sets().size(), bankBySetEe, bankBySetPfe);
            System.out.printf("  contributions by trade of a set of %,d trades: the trades' paths read %,.1f ms (wanted rows only) or %,.1f ms (the date's "
                    + "%,d rows streamed once, as the server reads the lake); the shares and PFE contributions %,.1f ms (EE %,.1f ms)%n", bigTrades,
                    readDirect, readScan, (long) trades * gs, byTradeCalc, byTradeEe);

            // the exact pre-deal method's arithmetic in Java: a candidate's paths added to the 1,000-trade set; its collateral, E, N again
            int bigOrd = bk.setOrdinal(bigId);
            ExposureBook.SetInfo si = bk.set(bigOrd);
            float[] cand = bank.paths[bank.trades / 2];
            double[] times = ExposureCalc.times(g);
            double exact = best(() -> {
                double[] v = bk.value(bigOrd);
                for (int i = 0; i < v.length; i++) {
                    v[i] += cand[i];
                }
                double[][] enc = ExposureMath.exposure(v, g, p, si.csa(), ExposureMath.Model.LAGGED);
                double[] e = br.e.clone();
                double[] n = br.n.clone();
                double[] e0 = bk.exposure(bigOrd);
                for (int i = 0; i < e.length; i++) {
                    e[i] += enc[0][i] - e0[i];
                }
                ExposureMath.statistics(e, n, null, times, p, 1.0, p95.confidences(), 0, ExposureMath.Rule.KTH);
            });
            // the proxy: the scope's statistics (cached by the server) plus the add-on per candidate and primary date
            double zq = ExposureWhatIf.z(0.95);
            double proxyOnly = best(() -> {
                double[] add = new double[gp];
                for (int c = 0; c < 10; c++) {
                    double sf = ExposureWhatIf.SF.get(ExposureWhatIf.factor(CLASSES[c % CLASSES.length]));
                    for (int i = 0; i < gp; i++) {
                        add[i] += Math.max(1e5, 0) + zq * sf * 1e7 * Math.sqrt(Math.min(times[i], 5));
                    }
                }
                assertThat(add[0]).isPositive();
            });
            double proxyCold = best(() -> ExposureCalc.evaluate(bk, cptyScope.trades(), false, p95, direct));
            System.out.printf("  pre-deal, exact (one candidate in the %,d-trade set: V + its paths, collateral, E, the scope's statistics; Java): "
                    + "%,.2f ms%n", bigTrades, exact);
            System.out.printf("  pre-deal, proxy (addon, 10 candidates on a counterparty): %,.3f ms with the scope's answer cached; %,.2f ms with "
                    + "the scope computed%n", proxyOnly, proxyOnly + proxyCold);
            System.out.printf("  heap held by the open date (sets, trades' columns and index; matrices mapped): %,.1f MB; mapped matrices %,.1f MB; "
                    + "disk %,.1f MB a date (V %,.1f, E and N %,.1f)%n", heapOpen / 1e6, bk.matrixBytes() / 1e6, hotBytes / 1e6,
                    (double) bk.sets().size() * gs * p * 4 / 1e6, (double) bk.sets().size() * gp * p * 4 * 2 / 1e6);
            assertThat(cr.stats.peakPfe[0]).isPositive();
        } finally {
            if (book != null) {
                book.close();
            }
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(dir);
        }
    }
}
