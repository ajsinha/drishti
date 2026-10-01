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
package com.ash.drishti.deltalake;

import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.defaults.internal.parquet.ParquetFileReader.BatchReadSupport;
import io.delta.kernel.defaults.internal.parquet.ParquetFileReader.RowRecordCollector;
import io.delta.kernel.defaults.internal.parquet.ParquetFilterUtils;
import io.delta.kernel.exceptions.KernelEngineException;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.MetadataColumnSpec;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.api.InitContext;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.schema.MessageType;

/**
 * One Parquet file read as Kernel column batches, without Hadoop: the footer is read once through the engine's own
 * {@link InputFile}, the predicate Kernel passes ({@code id = X}, ranges) prunes row groups by their statistics, only
 * the requested columns are decoded, and Kernel's own column readers build the vectors. The row index (for deletion
 * vectors) comes from each row group's position in the file. Used by one thread; opened on first use.
 */
final class ParquetBatches implements CloseableIterator<ColumnarBatch> {

    /** Parquet's options without a Hadoop {@code Configuration}; read-only after construction, so shared. */
    private static final ParquetConfiguration PLAIN = new PlainParquetConfiguration();

    private final NativeFileIO io;
    private final FileStatus file;
    private final StructType schema;
    private final Optional<Predicate> predicate;
    private final int batchSize;
    private final boolean rowIndex;

    private ParquetFileReader reader;
    private MessageColumnIO columnIO;
    private RowRecordCollector collector;
    private RecordReader<Object> records;
    private long leftInGroup;
    private long nextRowIndex;
    private boolean done;

    ParquetBatches(NativeFileIO io, FileStatus file, StructType schema, Optional<Predicate> predicate, int batchSize) {
        if (schema.fields().stream().filter(StructField::isMetadataColumn)
                .anyMatch(c -> c.getMetadataColumnSpec() != MetadataColumnSpec.ROW_INDEX)) {
            throw new IllegalArgumentException("the Parquet reader reads no metadata column but the row index");
        }
        this.io = io;
        this.file = file;
        this.schema = schema;
        this.predicate = predicate;
        this.batchSize = batchSize;
        this.rowIndex = schema.contains(MetadataColumnSpec.ROW_INDEX);
    }

    private static ParquetReadOptions.Builder options() {
        // row groups are pruned by statistics only, as Kernel's own reader does: no per-record filtering (Kernel
        // filters rows itself), no dictionary, bloom or page-index reads (extra I/O for a sorted table)
        return ParquetReadOptions.builder(PLAIN).withCodecFactory(NativeCodecs.INSTANCE).useStatsFilter(true).useDictionaryFilter(false)
                .useBloomFilter(false).useColumnIndexFilter(false).withUseHadoopVectoredIo(false);
    }

    private void open() throws IOException {
        InputFile in = io.newParquetFile(file.getPath(), file.getSize());
        SeekableInputStream stream = in.newStream();
        try {
            ParquetMetadata footer = ParquetFileReader.readFooter(in, options().build(), stream);
            MessageType fileSchema = footer.getFileMetaData().getSchema();
            Optional<FilterPredicate> filter = predicate.flatMap(p -> ParquetFilterUtils.toParquetFilter(fileSchema, p));
            ParquetReadOptions withFilter = options().withRecordFilter(filter.map(FilterCompat::get).orElse(FilterCompat.NOOP)).build();
            reader = ParquetFileReader.open(in, footer, withFilter, stream);      // the reader owns the stream now
            MessageType requested = new BatchReadSupport(batchSize, schema).init(new InitContext(PLAIN, Map.of(), fileSchema))
                    .getRequestedSchema();
            reader.setRequestedSchema(requested);
            collector = new RowRecordCollector(batchSize, schema, fileSchema);
            columnIO = new ColumnIOFactory(footer.getFileMetaData().getCreatedBy()).getColumnIO(requested, fileSchema, true);
        } catch (IOException | RuntimeException e) {
            if (reader == null) {
                stream.close();
            }
            throw e;
        }
    }

    /** Moves to the next row group that has rows; false at the end of the file. */
    private boolean advance() throws IOException {
        while (leftInGroup == 0) {
            PageReadStore pages = reader.readNextRowGroup();
            if (pages == null) {
                return false;
            }
            leftInGroup = pages.getRowCount();
            if (rowIndex) {
                nextRowIndex = pages.getRowIndexOffset().orElseThrow(() -> new IOException("no row index offset in " + file.getPath()));
            }
            records = columnIO.getRecordReader(pages, collector, FilterCompat.NOOP);
        }
        return true;
    }

    @Override
    public boolean hasNext() {
        if (done) {
            return false;
        }
        try {
            if (reader == null) {
                open();
            }
            if (leftInGroup > 0 || advance()) {
                return true;
            }
            done = true;
            return false;
        } catch (IOException e) {
            throw new KernelEngineException("Error reading Parquet file: " + file.getPath(), e);
        }
    }

    @Override
    public ColumnarBatch next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        int rows = 0;
        try {
            while (rows < batchSize && (leftInGroup > 0 || advance())) {
                records.read();
                collector.finalizeCurrentRow(rowIndex ? nextRowIndex : -1);
                nextRowIndex++;
                leftInGroup--;
                rows++;
            }
        } catch (IOException e) {
            throw new KernelEngineException("Error reading Parquet file: " + file.getPath(), e);
        }
        return collector.getDataAsColumnarBatch(rows);
    }

    @Override
    public void close() throws IOException {
        done = true;
        if (reader != null) {
            reader.close();
        }
    }
}
