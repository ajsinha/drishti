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
// QA helper (scratch, not product code): deletes one row of a Delta table in place with a deletion vector, by calling
// the plugin's own test fixture (DeletionVectorFixture.delete) through reflection.
// usage: java -cp <delta plugin test-classes:classes:deps> DvDelete.java <table path> <yyyy-MM-dd> <id>
import java.lang.reflect.Method;
import java.nio.file.Path;

public class DvDelete {
    public static void main(String[] a) throws Exception {
        Class<?> fixture = Class.forName("com.ash.drishti.plugin.delta.DeletionVectorFixture");
        Class<?> engineType = Class.forName("io.delta.kernel.engine.Engine");
        Method delete = fixture.getDeclaredMethod("delete", engineType, Path.class, String.class, String.class);
        delete.setAccessible(true);
        Class<?> ne = Class.forName("com.ash.drishti.deltalake.NativeEngine");
        try (AutoCloseable engine = (AutoCloseable) ne.getMethod("create").invoke(null)) {
            Object v = delete.invoke(null, engine, Path.of(a[0]), a[1], a[2]);
            System.out.println("deleted " + a[2] + " on " + a[1] + " -> version " + v);
        }
    }
}
