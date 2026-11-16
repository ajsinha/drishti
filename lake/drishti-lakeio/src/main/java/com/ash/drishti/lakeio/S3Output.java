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
package com.ash.drishti.lakeio;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * A new S3 object written as a stream: a file smaller than one part is a single PUT at {@link #close()}; a larger one a
 * multipart upload of fixed-size parts, sent as each fills (so at most one part is held in memory), completed at close
 * and aborted when a write fails. The object appears under its key only once complete. Used by one thread.
 */
final class S3Output extends OutputStream {

    private final S3Client client;
    private final S3Storage.Location location;
    private final byte[] part;
    private int filled;
    private String uploadId;
    private final List<CompletedPart> parts = new ArrayList<>();
    private boolean closed;

    S3Output(S3Client client, S3Storage.Location location, int partBytes) {
        this.client = client;
        this.location = location;
        this.part = new byte[partBytes];
    }

    @Override
    public void write(int b) throws IOException {
        if (filled == part.length) {
            flushPart();
        }
        part[filled++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int at = off;
        int left = len;
        while (left > 0) {
            if (filled == part.length) {
                flushPart();
            }
            int n = Math.min(left, part.length - filled);
            System.arraycopy(b, at, part, filled, n);
            filled += n;
            at += n;
            left -= n;
        }
    }

    private void flushPart() throws IOException {
        try {
            if (uploadId == null) {
                uploadId = client.createMultipartUpload(r -> r.bucket(location.bucket()).key(location.key())).uploadId();
            }
            int number = parts.size() + 1;
            String etag = client.uploadPart(r -> r.bucket(location.bucket()).key(location.key()).uploadId(uploadId).partNumber(number),
                    RequestBody.fromBytes(java.util.Arrays.copyOf(part, filled))).eTag();
            parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
            filled = 0;
        } catch (S3Exception e) {
            abort();
            throw new IOException("S3 " + e.statusCode() + " writing " + location.uri(location.key()) + ": " + e.getMessage(), e);
        }
    }

    private void abort() {
        if (uploadId != null) {
            try {
                client.abortMultipartUpload(r -> r.bucket(location.bucket()).key(location.key()).uploadId(uploadId));
            } catch (S3Exception e) {
                // the bucket's lifecycle rule for incomplete uploads removes it
            }
            uploadId = null;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (uploadId == null) {
                client.putObject(r -> r.bucket(location.bucket()).key(location.key()), RequestBody.fromBytes(java.util.Arrays.copyOf(part, filled)));
                return;
            }
            if (filled > 0) {
                flushPart();
            }
            client.completeMultipartUpload(r -> r.bucket(location.bucket()).key(location.key()).uploadId(uploadId)
                    .multipartUpload(m -> m.parts(parts)));
        } catch (S3Exception e) {
            abort();
            throw new IOException("S3 " + e.statusCode() + " writing " + location.uri(location.key()) + ": " + e.getMessage(), e);
        }
    }
}
