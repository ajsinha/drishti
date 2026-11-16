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
package com.ash.drishti.iceberg.meta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.types.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The partition transforms against the spec's own examples ("Appendix B: 32-bit Hash Requirements", "Truncate Transform Details"). */
class TransformTest {

    static int hash(Type.Primitive t, Object v) {
        return Transform.Bucket.hash(t, v);
    }

    static long micros(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1000;
    }

    @Test
    void bucketHashesAreTheSpecs() {
        assertThat(hash(Type.Primitive.INT, 34)).isEqualTo(2017239379);
        assertThat(hash(Type.Primitive.LONG, 34L)).isEqualTo(2017239379);
        assertThat(hash(Type.Primitive.decimal(4, 2), new BigDecimal("14.20"))).isEqualTo(-500754589);
        assertThat(hash(Type.Primitive.DATE, (int) LocalDate.of(2017, 11, 16).toEpochDay())).isEqualTo(-653330422);
        assertThat(hash(Type.Primitive.TIME, LocalTime.of(22, 31, 8).toNanoOfDay() / 1000)).isEqualTo(-662762989);
        assertThat(hash(Type.Primitive.TIMESTAMP, micros(LocalDateTime.of(2017, 11, 16, 22, 31, 8)))).isEqualTo(-2047944441);
        // 2017-11-16T14:31:08-08:00 is 22:31:08 UTC: a timestamptz hashes its UTC microseconds
        assertThat(hash(Type.Primitive.TIMESTAMPTZ, micros(LocalDateTime.of(2017, 11, 16, 22, 31, 8)))).isEqualTo(-2047944441);
        assertThat(hash(Type.Primitive.STRING, "iceberg")).isEqualTo(1210000089);
        assertThat(hash(Type.Primitive.UUID, UUID.fromString("f79c3e09-677c-4bbd-a479-3f349cb785e7"))).isEqualTo(1488055340);
        assertThat(hash(Type.Primitive.fixed(4), HexFormat.of().parseHex("00010203"))).isEqualTo(-188683207);
        assertThat(hash(Type.Primitive.BINARY, HexFormat.of().parseHex("00010203"))).isEqualTo(-188683207);
        assertThat(new Transform.Bucket(16).apply(Type.Primitive.INT, 34)).isEqualTo((2017239379 & Integer.MAX_VALUE) % 16);
    }

    @Test
    void truncateIsTheSpecs() {
        Transform t10 = new Transform.Truncate(10);
        assertThat(t10.apply(Type.Primitive.INT, 1)).isEqualTo(0);
        assertThat(t10.apply(Type.Primitive.INT, -1)).isEqualTo(-10);
        assertThat(t10.apply(Type.Primitive.LONG, 19L)).isEqualTo(10L);
        assertThat(new Transform.Truncate(50).apply(Type.Primitive.decimal(9, 2), new BigDecimal("10.65"))).isEqualTo(new BigDecimal("10.50"));
        assertThat(new Transform.Truncate(3).apply(Type.Primitive.STRING, "iceberg")).isEqualTo("ice");
        assertThat(new Transform.Truncate(2).apply(Type.Primitive.STRING, "😀ab")).isEqualTo("😀a");  // code points
    }

    @Test
    void timeTransformsCountFrom1970() {
        long ts = micros(LocalDateTime.of(2017, 11, 16, 22, 31, 8));
        assertThat(new Transform.Time(Transform.TimeUnit.YEAR).apply(Type.Primitive.TIMESTAMP, ts)).isEqualTo(47);
        assertThat(new Transform.Time(Transform.TimeUnit.MONTH).apply(Type.Primitive.TIMESTAMP, ts)).isEqualTo(47 * 12 + 10);
        assertThat(new Transform.Time(Transform.TimeUnit.DAY).apply(Type.Primitive.TIMESTAMP, ts)).isEqualTo((int) LocalDate.of(2017, 11, 16).toEpochDay());
        assertThat(new Transform.Time(Transform.TimeUnit.HOUR).apply(Type.Primitive.TIMESTAMP, ts)).isEqualTo((int) (ts / 3_600_000_000L));
        assertThat(new Transform.Time(Transform.TimeUnit.DAY).apply(Type.Primitive.TIMESTAMP, -1L)).isEqualTo(-1);   // before 1970 rounds down
        assertThat(new Transform.Time(Transform.TimeUnit.MONTH).apply(Type.Primitive.DATE, (int) LocalDate.of(1969, 12, 31).toEpochDay())).isEqualTo(-1);
    }

    @Test
    void transformsParseAndPrintAsMetadataWritesThem() {
        for (String s : List.of("identity", "bucket[16]", "truncate[4]", "year", "month", "day", "hour", "void")) {
            assertThat(Transform.parse(s).toString()).isEqualTo(s);
        }
        assertThat(Transform.parse("zorder")).isInstanceOf(Transform.Unknown.class);
        assertThat(Transform.parse("zorder").project(Type.Primitive.INT, (Expr.Pred) Expr.eq(1, 3), 0)).isEmpty();
    }

    @Test
    void projectionsAreInclusive() {
        Transform day = new Transform.Time(Transform.TimeUnit.DAY);
        long ts = micros(LocalDateTime.of(2026, 9, 30, 12, 0));
        int d = (int) LocalDate.of(2026, 9, 30).toEpochDay();
        assertThat(day.project(Type.Primitive.TIMESTAMP, (Expr.Pred) Expr.cmp(4, Expr.Op.LT, ts), 0).orElseThrow())
                .isEqualTo(new Expr.Pred(0, Expr.Op.LE, List.of(d)));               // < noon of the 30th: days up to the 30th
        assertThat(new Transform.Bucket(4).project(Type.Primitive.LONG, (Expr.Pred) Expr.cmp(1, Expr.Op.GT, 5L), 0)).isEmpty();
        assertThat(new Transform.Bucket(4).project(Type.Primitive.LONG, (Expr.Pred) Expr.eq(1, 34L), 0).orElseThrow().value())
                .isEqualTo((2017239379 & Integer.MAX_VALUE) % 4);
    }
}
