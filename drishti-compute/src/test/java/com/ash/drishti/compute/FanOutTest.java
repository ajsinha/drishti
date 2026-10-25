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
package com.ash.drishti.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.compute.approval.ApprovalStore;
import com.ash.drishti.compute.approval.Approvals;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.cluster.PeerTransport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One request fanned out over many workers on several nodes and gathered in order (docs/architecture/PYTHON_COMPUTE.md section 7.6): the
 * shape of pricing a batch of trades over 509 scenario days. A second compute service stands for another Drishti*net node.
 */
class FanOutTest {

    @TempDir
    Path dir;

    /** A "node" reached in-process: its own compute service, its own workers. */
    record InProcessPeer(String node, ComputeService service, AtomicInteger calls, boolean down) implements PeerTransport {
        @Override
        public List<PeerLoad> peers(String pool) {
            PeerLoad l = service.load(pool);
            return List.of(new PeerLoad(node, l.workers(), l.busy(), l.queued()));
        }

        @Override
        public CompletableFuture<ComputeResult> run(String n, ComputeRequest request) {
            calls.incrementAndGet();
            if (down) {
                return CompletableFuture.failedFuture(new ComputeException(ComputeException.Code.UNAVAILABLE, n + " is not answering"));
            }
            return service.submit(request, ProgressSink.NONE).result();
        }
    }

    private static ArrowTable trades(int n) {
        long[] ids = new long[n];
        double[] pnl = new double[n * 4];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            for (int k = 0; k < 4; k++) {
                pnl[i * 4 + k] = i + k * 0.25;
            }
        }
        return ArrowTable.builder().longs("TRADE_ID", ids).vectors("PNL", 4, pnl).build();
    }

    @Test
    void aBatchIsSplitAcrossTheWorkersOfTwoNodesAndGatheredInOrder() {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ interpreter to run workers");
        String mode = TestCompute.WINDOWS ? "warm" : "fork";
        try (ComputeService remote = TestCompute.service(TestCompute.settings(dir.resolve("b"), TestCompute.onePool("p", 0, 2, mode, null)), dir)) {
            InProcessPeer peer = new InProcessPeer("node-b", remote, new AtomicInteger(), false);
            ComputeSettings s = TestCompute.settings(dir.resolve("a"), TestCompute.onePool("p", 0, 3, mode, null));
            try (ComputeService local = new ComputeService(s, "node-a", dir, new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0), peer)
                    .start()) {
                List<Map<String, Object>> progress = new CopyOnWriteArrayList<>();
                ComputeRequest req = ComputeRequest.on("p", "echo", "asha").engine(TestCompute.VECTORS).input("trades", trades(2_000))
                        .input("market", ArrowTable.builder().doubles("SPOT", 101.5).build()).cls(RequestClass.BATCH).build();
                ComputeHandle h = local.fanOut(req, "trades", 100, (id, d) -> progress.add(d));
                ComputeResult r = h.await(Duration.ofSeconds(120));
                ArrowTable back = r.table("trades");
                assertThat(back.rows()).isEqualTo(2_000);
                for (int i = 0; i < 2_000; i += 97) {
                    assertThat(back.longs("TRADE_ID")[i]).isEqualTo(i);              // gathered in chunk order
                    assertThat(back.vector("PNL", i)).containsExactly(i, i + 0.25, i + 0.5, i + 0.75);
                }
                assertThat(r.table("market").rows()).isEqualTo(20);                  // the whole input went with each of the 20 chunks
                assertThat(r.rows()).hasSize(20);
                assertThat(r.meta()).containsEntry("chunks", 20);
                @SuppressWarnings("unchecked")
                Map<String, Integer> nodes = (Map<String, Integer>) r.meta().get("nodes");
                assertThat(nodes).containsKeys("local", "node-b");
                assertThat(peer.calls().get()).isGreaterThan(0);
                assertThat(((Number) r.meta().get("workers")).intValue()).isGreaterThan(1);
                assertThat(progress).hasSize(20);
                assertThat(progress.get(19)).containsEntry("done", 20);
            }
        }
    }

    @Test
    void chunksSentToANodeThatIsDownRunHere() {
        assumeTrue(TestCompute.python() != null, "no Python 3.10+ interpreter to run workers");
        String mode = TestCompute.WINDOWS ? "warm" : "fork";
        try (ComputeService remote = TestCompute.service(TestCompute.settings(dir.resolve("b"), TestCompute.onePool("p", 0, 2, mode, null)), dir)) {
            InProcessPeer dead = new InProcessPeer("node-b", remote, new AtomicInteger(), true);
            ComputeSettings s = TestCompute.settings(dir.resolve("a"), TestCompute.onePool("p", 0, 2, mode, null));
            try (ComputeService local = new ComputeService(s, "node-a", dir, new Approvals(new ApprovalStore.Memory(), null, Clock.systemUTC(), 0), dead)
                    .start()) {
                ComputeResult r = local.fanOut(ComputeRequest.on("p", "echo", "asha").engine(TestCompute.VECTORS).input("trades", trades(500)).build(),
                        "trades", 50, ProgressSink.NONE).await(Duration.ofSeconds(120));
                assertThat(r.table("trades").rows()).isEqualTo(500);
                assertThat(dead.calls().get()).isGreaterThan(0);
                @SuppressWarnings("unchecked")
                Map<String, Integer> nodes = (Map<String, Integer>) r.meta().get("nodes");
                assertThat(nodes).containsOnlyKeys("local");
            }
        }
    }
}
