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
// QA helper (scratch): reads one partition of a Delta table through Kernel's full scan (no predicate) and through
// the plugin's one-entity path (DeltaTable.doc via reflection), to see whether a deletion-vector delete is honoured.
// usage: java -cp <cp> DvProbe.java <table path> <yyyy-MM-dd> <id>...
import io.delta.kernel.Scan;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.util.HashSet;
import java.util.Set;

public class DvProbe {
    public static void main(String[] a) throws Exception {
        Class<?> ne = Class.forName("com.ash.drishti.deltalake.NativeEngine");
        try (AutoCloseable c = (AutoCloseable) ne.getMethod("create").invoke(null)) {
            Engine engine = (Engine) c;
            var snap = Table.forPath(engine, a[0]).getLatestSnapshot(engine);
            Scan scan = snap.getScanBuilder().withReadSchema(new StructType().add("id", StringType.STRING)).build();
            Row state = scan.getScanState(engine);
            System.out.println("version " + snap.getVersion() + " physical read schema " + ScanStateRow.getPhysicalDataReadSchema(state));
            Set<String> seen = new HashSet<>();
            int rows = 0;
            try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
                while (files.hasNext()) {
                    try (CloseableIterator<Row> it = files.next().getRows()) {
                        while (it.hasNext()) {
                            Row f = it.next();
                            if (!a[1].equals(InternalScanFileUtils.getPartitionValues(f).get("business_date"))) {
                                continue;
                            }
                            System.out.println("file " + InternalScanFileUtils.getAddFileStatus(f).getPath() + " dv="
                                    + InternalScanFileUtils.getDeletionVectorDescriptorFromRow(f));
                            FileStatus fs = InternalScanFileUtils.getAddFileStatus(f);
                            StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
                            try (var raw = engine.getParquetHandler().readParquetFiles(io.delta.kernel.internal.util.Utils.singletonCloseableIterator(fs), physical, java.util.Optional.empty());
                                 var data = Scan.transformPhysicalData(engine, state, f, raw.map(r -> r.getData()))) {
                                while (data.hasNext()) {
                                    FilteredColumnarBatch b = data.next();
                                    try (CloseableIterator<Row> rs = b.getRows()) {
                                        while (rs.hasNext()) {
                                            seen.add(rs.next().getString(0));
                                            rows++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("full scan rows " + rows);
            for (int i = 2; i < a.length; i++) {
                System.out.println("  " + a[i] + " present in full scan: " + seen.contains(a[i]));
            }
        }
    }
}
