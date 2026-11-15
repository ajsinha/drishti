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
package com.ash.drishti.net.agreement.ratis;

import com.ash.drishti.net.agreement.AgreementState;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.MD5FileUtil;

/**
 * The replicated state machine of the ratis mode (CLUSTER_I5_DESIGN.md section 3): {@link AgreementState} applied from the Raft log, so
 * every voter holds the same leases and register; snapshots of the whole state every {@code snapshot-every} entries, in the voter's
 * {@code [net.agreement] dir}. Time is the leader's {@code tick} entries, never a replica's clock.
 */
final class AgreementStateMachine extends BaseStateMachine {

    private final AgreementState state = new AgreementState();
    private final ReentrantLock lock = new ReentrantLock();
    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    @Override
    public SimpleStateMachineStorage getStateMachineStorage() {
        return storage;
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        load(storage.loadLatestSnapshot());
    }

    @Override
    public void reinitialize() throws IOException {
        load(storage.loadLatestSnapshot());
    }

    private void load(SingleFileSnapshotInfo snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        File f = snapshot.getFile().getPath().toFile();
        if (!f.isFile()) {
            return;
        }
        MD5Hash md5 = snapshot.getFile().getFileDigest();
        if (md5 != null) {
            MD5FileUtil.verifySavedMD5(f, md5);
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        lock.lock();
        try {
            state.restore(bytes);
            setLastAppliedTermIndex(snapshot.getTermIndex());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long takeSnapshot() {
        TermIndex last;
        byte[] bytes;
        lock.lock();
        try {
            last = getLastAppliedTermIndex();
            bytes = state.snapshot();
        } finally {
            lock.unlock();
        }
        if (last == null) {
            return -1;
        }
        File f = storage.getSnapshotFile(last.getTerm(), last.getIndex());
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), bytes);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("the agreement snapshot " + f + " could not be written", e);
        }
        MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(f);
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(new FileInfo(f.toPath(), md5), last));
        return last.getIndex();
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        LogEntryProto entry = trx.getLogEntry();
        byte[] command = entry.getStateMachineLogEntry().getLogData().toByteArray();
        byte[] answer;
        lock.lock();
        try {
            answer = state.apply(command);
            updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());
        } catch (RuntimeException e) {
            updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());
            return CompletableFuture.failedFuture(e);
        } finally {
            lock.unlock();
        }
        return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(answer)));
    }

    @Override
    public CompletableFuture<Message> query(Message request) {
        byte[] answer;
        lock.lock();
        try {
            answer = state.query(request.getContent().toByteArray());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            lock.unlock();
        }
        return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(answer)));
    }
}
