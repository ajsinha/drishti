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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.LoadGuard;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.plugin.iceberg.IcebergLayout.Row;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tables of a data domain as {@link IcebergLoader} and {@link IcebergMaintenance} write them, through the engine
 * the settings (or {@code --engine}) choose: {@code native} (drishti-iceberg) or {@code library} (Apache Iceberg
 * Java). Both write {@link IcebergLayout}; a table written by one is read and maintained by the other.
 */
interface LayoutLake extends Closeable {

    /** One kind's table, for writing. */
    interface LayoutTable {
        String name();

        /** Writes one day's rows (sorted by id) into files; nothing is committed. The files are the engine's own type. */
        List<?> writeDay(LocalDate date, Iterator<Row> sorted, int fileRows);

        long rows(List<?> files);

        long bytes(List<?> files);

        /** Makes each day's files that day's data, in one commit. */
        void commitDays(Map<LocalDate, List<?>> days);

        /** Adds the files to the table in one append commit (nothing replaced: the official results' tables, 2.9.5). */
        void append(List<?> files, Map<String, String> summary);

        /** Sets table properties (one commit), keeping the others. */
        void properties(Map<String, String> props);

        /** The current snapshot's sequence number (0 when there is none). */
        long sequenceNumber();

        default int keepDays(int keep, LoadGuard guard) {
            return keepDays(keep, guard, null);
        }

        /** Keeps the newest {@code keep} business days, never dropping one on or after {@code floor} (null: no floor). */
        int keepDays(int keep, LoadGuard guard, LocalDate floor);

        void expireSnapshots(long hours);

        void rewriteManifests();

        int relayout(boolean force, Path spillDir, long budget);

        /** Adds promoted columns ({@code paths}), typed by the newest day's documents. */
        void addColumns(List<String> paths);

        int fileRows();

        void refresh();
    }

    /** The kind's table, created in the layout if it does not exist; promoted paths it lacks are added. */
    default LayoutTable open(String kind, Map<String, Boolean> promoted, long rowGroupBytes, int fileRows) {
        return open(kind, promoted, java.util.Set.of(), rowGroupBytes, fileRows);
    }

    /** {@link #open(String, Map, long, int)} with vector columns ({@code list<double>}: a P&amp;L vector), added when missing too. */
    LayoutTable open(String kind, Map<String, Boolean> promoted, java.util.Set<String> vectors, long rowGroupBytes, int fileRows);

    /** The kind's table, or empty. */
    Optional<LayoutTable> load(String kind);

    List<String> kinds() throws IOException;

    IcebergEngine engine();

    @Override
    void close() throws IOException;

    static LayoutLake of(Map<String, String> settings) {
        return IcebergEngine.of(settings) == IcebergEngine.NATIVE ? new Native(Catalogs.open(settings)) : new Library(IcebergLake.of(settings));
    }

    /** Apache Iceberg Java (the loader's and maintenance's code before drishti-iceberg). */
    record Library(IcebergLake lake) implements LayoutLake {

        record Kind(IcebergLake lake, String kind, org.apache.iceberg.Table t) implements LayoutTable {
            @Override
            public String name() {
                return t.name();
            }

            @Override
            public List<?> writeDay(LocalDate date, Iterator<Row> sorted, int fileRows) {
                return IcebergLayout.writeDay(t, date, sorted, fileRows);
            }

            @Override
            public long rows(List<?> files) {
                return files.stream().mapToLong(f -> ((org.apache.iceberg.DataFile) f).recordCount()).sum();
            }

            @Override
            public long bytes(List<?> files) {
                return files.stream().mapToLong(f -> ((org.apache.iceberg.DataFile) f).fileSizeInBytes()).sum();
            }

            @Override
            @SuppressWarnings("unchecked")
            public void commitDays(Map<LocalDate, List<?>> days) {
                IcebergLayout.commitDays(t, (Map<LocalDate, List<org.apache.iceberg.DataFile>>) (Map<?, ?>) days);
            }

            @Override
            public int keepDays(int keep, LoadGuard guard, LocalDate floor) {
                return IcebergMaintenance.keepDays(t, keep, guard, floor);
            }

            @Override
            public void append(List<?> files, Map<String, String> summary) {
                org.apache.iceberg.AppendFiles a = t.newAppend();
                files.forEach(f -> a.appendFile((org.apache.iceberg.DataFile) f));
                summary.forEach(a::set);
                a.commit();
            }

            @Override
            public void properties(Map<String, String> props) {
                org.apache.iceberg.UpdateProperties u = t.updateProperties();
                props.forEach(u::set);
                u.commit();
            }

            @Override
            public long sequenceNumber() {
                t.refresh();
                return t.currentSnapshot() == null ? 0 : t.currentSnapshot().sequenceNumber();
            }

            @Override
            public void expireSnapshots(long hours) {
                IcebergMaintenance.expireSnapshots(t, hours);
            }

            @Override
            public void rewriteManifests() {
                IcebergMaintenance.rewriteManifests(t);
            }

            @Override
            public int relayout(boolean force, Path spillDir, long budget) {
                return IcebergMaintenance.relayout(t, force, spillDir, budget);
            }

            @Override
            public void addColumns(List<String> paths) {
                IcebergMaintenance.addColumns(lake, kind, t, paths);
            }

            @Override
            public int fileRows() {
                return IcebergLayout.fileRows(t);
            }

            @Override
            public void refresh() {
                t.refresh();
            }
        }

        @Override
        public LayoutTable open(String kind, Map<String, Boolean> promoted, java.util.Set<String> vectors, long rowGroupBytes, int fileRows) {
            return new Kind(lake, kind, IcebergLayout.open(lake, kind, promoted, vectors, rowGroupBytes, fileRows));
        }

        @Override
        public Optional<LayoutTable> load(String kind) {
            return lake.load(kind).map(t -> new Kind(lake, kind, t));
        }

        @Override
        public List<String> kinds() throws IOException {
            return lake.kinds();
        }

        @Override
        public IcebergEngine engine() {
            return IcebergEngine.LIBRARY;
        }

        @Override
        public void close() throws IOException {
            lake.close();
        }
    }

    /** drishti-iceberg. */
    record Native(Catalog catalog) implements LayoutLake {

        record Kind(Catalog catalog, String kind, com.ash.drishti.iceberg.Table t) implements LayoutTable {
            @Override
            public String name() {
                return t.name();
            }

            @Override
            public List<?> writeDay(LocalDate date, Iterator<Row> sorted, int fileRows) {
                return NativeLayout.writeDay(t, date, sorted, fileRows);
            }

            @Override
            public long rows(List<?> files) {
                return files.stream().mapToLong(f -> ((com.ash.drishti.iceberg.manifest.DataFile) f).recordCount()).sum();
            }

            @Override
            public long bytes(List<?> files) {
                return files.stream().mapToLong(f -> ((com.ash.drishti.iceberg.manifest.DataFile) f).fileSizeInBytes()).sum();
            }

            @Override
            @SuppressWarnings("unchecked")
            public void commitDays(Map<LocalDate, List<?>> days) {
                NativeLayout.commitDays(t, (Map<LocalDate, List<com.ash.drishti.iceberg.manifest.DataFile>>) (Map<?, ?>) days);
            }

            @Override
            public int keepDays(int keep, LoadGuard guard, LocalDate floor) {
                return NativeMaintenance.keepDays(t, keep, guard, floor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void append(List<?> files, Map<String, String> summary) {
                com.ash.drishti.iceberg.write.Operations.append(t, (List<com.ash.drishti.iceberg.manifest.DataFile>) files, summary);
            }

            @Override
            public void properties(Map<String, String> props) {
                com.ash.drishti.iceberg.write.Operations.setProperties(t, props);
            }

            @Override
            public long sequenceNumber() {
                t.refresh();
                return t.currentSnapshot().map(com.ash.drishti.iceberg.meta.Snapshot::sequenceNumber).orElse(0L);
            }

            @Override
            public void expireSnapshots(long hours) {
                NativeMaintenance.expireSnapshots(t, hours);
            }

            @Override
            public void rewriteManifests() {
                NativeMaintenance.rewriteManifests(t);
            }

            @Override
            public int relayout(boolean force, Path spillDir, long budget) {
                return NativeMaintenance.relayout(t, force, spillDir, budget);
            }

            @Override
            public void addColumns(List<String> paths) {
                Map<String, Boolean> cols = new java.util.LinkedHashMap<>();
                paths.forEach(p -> cols.put(p.trim(), false));
                NativeMaintenance.inferTypes(t, cols);
                NativeLayout.open(catalog, kind, cols, IcebergLayout.DEFAULT_ROW_GROUP_BYTES, NativeLayout.fileRows(t));
                t.refresh();
            }

            @Override
            public int fileRows() {
                return NativeLayout.fileRows(t);
            }

            @Override
            public void refresh() {
                t.refresh();
            }
        }

        @Override
        public LayoutTable open(String kind, Map<String, Boolean> promoted, java.util.Set<String> vectors, long rowGroupBytes, int fileRows) {
            return new Kind(catalog, kind, NativeLayout.open(catalog, kind, promoted, vectors, rowGroupBytes, fileRows));
        }

        @Override
        public Optional<LayoutTable> load(String kind) {
            try {
                return catalog.load(kind).map(t -> new Kind(catalog, kind, t));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public List<String> kinds() throws IOException {
            return catalog.tables();
        }

        @Override
        public IcebergEngine engine() {
            return IcebergEngine.NATIVE;
        }

        @Override
        public void close() {
            catalog.close();
        }
    }
}
