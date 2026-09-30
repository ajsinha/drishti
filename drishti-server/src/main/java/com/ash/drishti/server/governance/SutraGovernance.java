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
package com.ash.drishti.server.governance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The review workflow for Sutras (W19). Authors propose; approvers approve (the Sutra goes live) or reject with a
 * reason; authors may withdraw their own. With four-eyes on, nobody approves their own proposal. Approval refuses
 * when the live Sutra changed after the proposal was made, so a reviewer never approves against a stale diff.
 * Every step is audited.
 */
@Service
public class SutraGovernance {

    private final ProposalStore store;
    private final SutraRegistry sutras;
    private final Entitlements entitlements;
    private final GovernanceProperties props;
    private final SecurityProperties security;
    private final UserService users;

    public SutraGovernance(ProposalStore store, SutraRegistry sutras, Entitlements entitlements, GovernanceProperties props,
            SecurityProperties security, UserService users) {
        this.store = store;
        this.sutras = sutras;
        this.entitlements = entitlements;
        this.props = props;
        this.security = security;
        this.users = users;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Validates the Sutra (it must be publishable as it stands) and records it as pending. */
    public Proposal propose(String text, String note, Principal p) {
        requireAuthor(p);
        Sutra s = sutras.check(text);
        String base = sutras.source(s.name(), s.version()).orElse("");
        if (base.equals(text)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, s.id() + " is already live exactly as proposed");
        }
        Proposal made = store.create(s.name(), s.version(), text, base, note == null ? "" : note.trim(), p.user());
        users.recordAudit(p.user(), "sutra-proposed", s.id(), made.id() + (made.note().isEmpty() ? "" : ": " + made.note()));
        return made;
    }

    public Proposal approve(String id, String comment, Principal p) {
        requireApprover(p);
        Proposal done = store.decide(id, current -> {
            if (fourEyes() && current.author().equals(p.user())) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "four eyes: " + p.user() + " proposed " + id + " and cannot approve it");
            }
            if (!sutras.source(current.name(), current.version()).orElse("").equals(current.baseText())) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, current.name() + "@" + current.version()
                        + " changed after " + id + " was proposed; reject it and propose again from the live version");
            }
            return current;
        }, () -> save(store.require(id).text()), Proposal.APPROVED, p.user(), comment);
        users.recordAudit(p.user(), "sutra-approved", done.name() + "@" + done.version(), id + " by " + done.author());
        return done;
    }

    public Proposal reject(String id, String comment, Principal p) {
        requireApprover(p);
        if (comment == null || comment.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why the proposal is rejected");
        }
        Proposal done = store.decide(id, c -> c, () -> { }, Proposal.REJECTED, p.user(), comment);
        users.recordAudit(p.user(), "sutra-rejected", done.name() + "@" + done.version(), id + ": " + comment.trim());
        return done;
    }

    public Proposal withdraw(String id, Principal p) {
        Proposal done = store.decide(id, c -> {
            if (!c.author().equals(p.user()) && !entitlements.isAdmin(p)) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "only " + c.author() + " can withdraw " + id);
            }
            return c;
        }, () -> { }, Proposal.WITHDRAWN, p.user(), "withdrawn");
        users.recordAudit(p.user(), "sutra-withdrawn", done.name() + "@" + done.version(), id);
        return done;
    }

    public List<Proposal> list(String status, String name, Principal p) {
        requireReader(p);
        return store.list(status, name);
    }

    public Proposal get(String id, Principal p) {
        requireReader(p);
        return store.require(id);
    }

    /** The live text of the proposal's Sutra now (empty when that version is new). */
    public String liveText(Proposal pr) {
        return sutras.source(pr.name(), pr.version()).orElse("");
    }

    /** Whether {@code p} may approve {@code pr} now (for the console's buttons; the server checks again). */
    public boolean mayApprove(Proposal pr, Principal p) {
        return pr.pending() && entitlements.mayApprove(p) && !(fourEyes() && pr.author().equals(p.user()));
    }

    public boolean stale(Proposal pr) {
        return pr.pending() && !liveText(pr).equals(pr.baseText());
    }

    private boolean fourEyes() {
        return props.fourEyes() && security.enabled();
    }

    private void save(String text) {
        try {
            sutras.save(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void requireAuthor(Principal p) {
        if (!entitlements.mayAuthor(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not a Sutra author");
        }
    }

    private void requireApprover(Principal p) {
        if (!entitlements.mayApprove(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not approve Sutras");
        }
    }

    private void requireReader(Principal p) {
        if (!entitlements.mayAuthor(p) && !entitlements.mayApprove(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is neither a Sutra author nor an approver");
        }
    }
}
