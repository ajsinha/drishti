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
package com.ash.drishti.net.agreement;

/**
 * One answer or none (docs/architecture/CLUSTER_I5_DESIGN.md section 3): who owns a one-runner part ({@link Leases}) and the small versioned
 * register of the cluster's configuration ({@link Register}). Agreement needs a majority or a referee: without one, a change is refused with
 * {@link AgreementUnavailableException} (the server answers it as {@code 503 DRS-5060}); reads, forwarding and engine calls never wait for it.
 *
 * <p>Providers: {@link LocalAgreement} (one node, and the unit tests), the database mode, the ratis mode (module {@code drishti-net-ratis},
 * mutual TLS required) and first-live. Every provider passes the same contract suite ({@code AgreementContract}), so none can drift. The
 * libraries behind a provider are never imported outside it (ArchitectureRulesTest).
 */
public interface Agreement extends AutoCloseable {

    /** How this cluster agrees (section 2.3); {@code auto} picks one of these and says why ({@link AgreementHealth#why()}). */
    enum Mode {
        /** One node: nothing to agree; the register is this process. */
        LOCAL,
        /** Leases and the register in two tables of the shared identity database, expiry on the database's clock. */
        DATABASE,
        /** A Raft group of three or five voters (a witness counts), mutual TLS required. */
        RATIS,
        /** Two voters, no database, no witness: no agreement, the first live candidate holds; Health amber. */
        FIRST_LIVE;

        /** The name as written in the settings and shown: {@code local}, {@code database}, {@code ratis}, {@code first-live}. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    Mode mode();

    /** Whether it can decide now, the leader and term, the voters and why this mode; cheap enough for Health and Admin. */
    AgreementHealth health();

    /** This node's view of the leases: who holds each one-runner part, renewed while this node runs. */
    Leases leases();

    /** The cluster's small versioned register (placement, switches, active module versions, the key epoch, end-of-day runs). */
    Register register();

    /** Stops renewing this node's leases (they expire for the others after their time-to-live) and closes the provider. */
    @Override
    void close();
}
