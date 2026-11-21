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
package com.ash.drishti.testroots;

import com.ash.lake.tck.TestRoots;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's recorded answers that the console's tests read (docs/architecture/THIN_CONSOLE_DESIGN.md 5.2, "Recorded answers"), under
 * {@code drishti-console/tests/answers/}: written by a Java test from the server's own code, read by the console's stand-ins. One direction
 * only: the server makes them, the console reads them. Run a recording test with {@code -Ddrishti.record=true} to write its files again;
 * otherwise a file that differs from what the server's code makes now is stale and the test fails, so a console test never reads an answer
 * the server no longer gives. A file not there yet is written.
 *
 * <pre>
 * RecordedAnswers rec = RecordedAnswers.under(Path.of(TestRoots.repo()), "landings");
 * rec.keep("market-data.json", json);           // written, or compared
 * rec.assertCurrent();                           // fails naming every stale file
 * </pre>
 */
public final class RecordedAnswers {

    /** The folder of every recorded answer, relative to the repository. */
    public static final String FOLDER = "drishti-console/tests/answers";

    private final Path repo;
    private final Path dir;
    private final boolean record;
    private final List<String> stale = new ArrayList<>();

    private RecordedAnswers(Path repo, Path dir, boolean record) {
        this.repo = repo;
        this.dir = dir;
        this.record = record;
    }

    /**
     * The recorded answers in {@code drishti-console/tests/answers/<sub>} ("" for the folder itself) of the repository {@code repo} (the
     * calling test's {@link TestRoots#repo()}).
     */
    public static RecordedAnswers under(Path repo, String sub) {
        Path root = repo.resolve(FOLDER);
        return new RecordedAnswers(repo, sub.isEmpty() ? root : root.resolve(sub), Boolean.getBoolean("drishti.record"));
    }

    /** Whether this run writes the files again ({@code -Ddrishti.record=true}). */
    public boolean recording() {
        return record;
    }

    /**
     * One answer file: written when recording or when it is not there yet; else compared with {@code text} and noted stale when it
     * differs. Line ends are written as {@code \n}; the text must not name a folder of this machine (a recording is the same everywhere).
     */
    public void keep(String name, String text) {
        String body = text.replace("\r\n", "\n");
        body = body.endsWith("\n") ? body : body + "\n";
        if (body.contains(repo.toString())) {
            throw new AssertionError(name + " names a folder of this machine (" + repo + "): a recording is the same on every machine");
        }
        Path out = dir.resolve(name);
        try {
            if (record || !Files.isRegularFile(out)) {
                Files.createDirectories(out.getParent());
                Files.writeString(out, body, StandardCharsets.UTF_8);
            } else if (!Files.readString(out, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(body)) {
                stale.add(repo.relativize(out).toString().replace('\\', '/'));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The files found stale by {@link #keep}. */
    public List<String> stale() {
        return List.copyOf(stale);
    }

    /** Fails naming every stale file and how to write them again. */
    public void assertCurrent() {
        if (!stale.isEmpty()) {
            throw new AssertionError("recorded answers older than the server's code: " + stale
                    + "; run this test with -Ddrishti.record=true and commit the files");
        }
    }
}
