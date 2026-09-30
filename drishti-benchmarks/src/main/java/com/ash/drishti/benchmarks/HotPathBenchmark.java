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
package com.ash.drishti.benchmarks;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.common.ShapeFingerprinter;
import com.ash.drishti.inference.InferenceEngine;
import com.ash.drishti.inference.Rules;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.format.Formats;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Hot paths of the view pipeline. Run with:
 * {@code ./mvnw -q -pl drishti-benchmarks -am package -DskipTests && java -cp "drishti-benchmarks/target/classes:$(cat cp.txt)" org.openjdk.jmh.Main}
 * (see docs/PERFORMANCE.md).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HotPathBenchmark {

    DataNode irs;
    ShapeFingerprinter fingerprinter;
    Expr direction;
    Expr sum;
    EvalContext ctx;
    Formats formats;
    InferenceEngine inference;

    @Setup
    public void setup() throws IOException {
        Path p = Path.of("packs/finance/samples/trade/IRS-48213.json");
        try (InputStream in = Files.newInputStream(p)) {
            irs = new JsonCodec().read(in);
        }
        fingerprinter = new ShapeFingerprinter();
        ElCompiler el = new ElCompiler();
        direction = el.compile("$.direction == 'PAY_FIXED' ? 'Pay fixed' : 'Receive fixed'");
        sum = el.compile("sum($.legs[0].cashflows, 'pv')");
        formats = Formats.load(null, java.util.List.of("packs/finance/config/formats.yaml"));
        ctx = EvalContext.of(irs, formats);
        inference = new InferenceEngine(Semantics.defaults(), Rules.builtIn());
    }

    @Benchmark
    public Object fingerprint() {
        return fingerprinter.fingerprint(irs);
    }

    @Benchmark
    public Object evalTernary() {
        return direction.eval(ctx);
    }

    @Benchmark
    public Object evalSum() {
        return sum.eval(ctx);
    }

    @Benchmark
    public String formatSigned() {
        return formats.format("signed2", -1962430.56);
    }

    @Benchmark
    public Object inferColdLayout() {
        return inference.infer(irs, "trade");
    }
}
