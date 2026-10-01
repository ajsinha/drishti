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
package com.ash.drishti.server.security;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a principal may see. Denied entities are refused with 403; links to them stay visible but
 * disabled, with the reason, so people learn what exists without seeing it (the MAYA rule: visible, not
 * hidden). Raw JSON is redacted for roles without {@code raw}.
 */
public final class Entitlements {

    public static final String DENIED = "no access";
    private final SecurityProperties props;
    private final PackAccess packs;
    private final RoleCatalog roles;

    public Entitlements(SecurityProperties props, PackAccess packs, RoleCatalog roles) {
        this.props = props;
        this.packs = packs;
        this.roles = roles;
    }

    /** The user's roles allow the kind, and the kind's pack is active for the user. */
    public boolean mayOpen(Principal p, String kind) {
        return packs.kindAllowed(p.user(), kind) && roleAllows(p, kind);
    }

    private boolean roleAllows(Principal p, String kind) {
        if (!props.enabled() || p.roles().contains("*")) {
            return true;
        }
        for (String r : p.roles()) {
            if (roles.find(r).map(role -> role.mayOpen(kind)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    public void requireOpen(Principal p, String kind) {
        if (!mayOpen(p, kind)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not open " + kind + " entities");
        }
    }

    private boolean has(Principal p, java.util.function.Predicate<com.ash.drishti.identity.RoleDefinition> test) {
        if (!props.enabled() || p.roles().contains("*")) {
            return true;
        }
        return p.roles().stream().map(roles::find).anyMatch(r -> r.isPresent() && test.test(r.get()));
    }

    /** The console's own identity, used only to verify sign-ins. */
    public static final String SERVICE = "service";

    public boolean isAdmin(Principal p) {
        return has(p, com.ash.drishti.identity.RoleDefinition::admin);
    }

    public void requireAdmin(Principal p) {
        if (!isAdmin(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not an administrator");
        }
    }

    public void requireService(Principal p) {
        if (props.enabled() && !p.roles().contains(SERVICE)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "sign-in is verified by the console only");
        }
    }

    public boolean mayAuthor(Principal p) {
        return has(p, com.ash.drishti.identity.RoleDefinition::author);
    }

    /** Approvers review proposed Sutras: roles with {@code approve}, and admins. */
    public boolean mayApprove(Principal p) {
        return has(p, com.ash.drishti.identity.RoleDefinition::approve) || has(p, com.ash.drishti.identity.RoleDefinition::admin);
    }

    public DataNode redact(Principal p, DataNode data) {
        return has(p, com.ash.drishti.identity.RoleDefinition::raw) || props.redact().isEmpty() ? data : mask(data);
    }

    private DataNode mask(DataNode n) {
        if (n instanceof DataNode.Obj o) {
            Map<String, DataNode> out = new LinkedHashMap<>();
            o.fields().forEach((k, v) -> out.put(k, props.redact().contains(k) ? new DataNode.Val("•••") : mask(v)));
            return new DataNode.Obj(out);
        }
        if (n instanceof DataNode.Arr a) {
            return new DataNode.Arr(a.elements().stream().map(this::mask).toList());
        }
        return n;
    }

    public List<Suggestion> filter(Principal p, List<Suggestion> in) {
        return in.stream().filter(s -> s.kind() == null || mayOpen(p, s.kind())).toList();
    }

    /** Disables links the principal may not follow: linked-entity rows, cell links and link keys. */
    public ViewModel restrict(Principal p, ViewModel v) {
        List<ViewModel.PanelView> panels = new ArrayList<>();
        for (ViewModel.PanelView pv : v.panels()) {
            if (pv.data() instanceof PanelData.Links l) {
                List<PanelData.LinkItem> items = l.links().stream().map(i -> i.link() != null && !mayOpen(p, i.link().kind())
                        ? new PanelData.LinkItem(i.label(), i.text(), null, DENIED, "denied") : i).toList();
                panels.add(new ViewModel.PanelView(pv.id(), pv.kind(), pv.title(), pv.code(), pv.key(), pv.area(), pv.inferred(),
                        pv.explanation(), new PanelData.Links(items), pv.error(), pv.empty()));
            } else {
                panels.add(pv);
            }
        }
        List<ViewModel.KeyView> keys = v.keys().stream().map(k -> k.link() != null && !mayOpen(p, k.link().kind())
                ? new ViewModel.KeyView(k.key(), k.label() + " (" + DENIED + ")", "denied", null, null) : k).toList();
        ViewModel.TitleView t = v.title();
        if (t.with() != null && t.with().link() != null && !mayOpen(p, t.with().link().kind())) {
            t = new ViewModel.TitleView(t.pill(), t.id(), new ViewModel.Cell(null, t.with().text(), null, null, false, null));
        }
        return new ViewModel(v.ref(), v.mnemonic(), t, v.strip(), panels, keys, v.provenance(), v.timings());
    }
}
