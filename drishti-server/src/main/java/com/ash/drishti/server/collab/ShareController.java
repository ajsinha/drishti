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
package com.ash.drishti.server.collab;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.server.collab.snapshot.SnapshotService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Share with a note (docs/architecture/COLLABORATION.md): send a note and a pinned link to people and roles, open a share, list the
 * caller's shares. Personal API tokens read only (they cannot send). Collaboration off is {@code 403 DRS-7004}.
 */
@RestController
@RequestMapping("/api/v1")
public class ShareController {

    private final ShareService shares;
    private final CollabProperties props;
    private final Entitlements entitlements;
    private final SnapshotService snapshots;
    private final boolean accessLog;

    public ShareController(ShareService shares, CollabProperties props, Entitlements entitlements, SnapshotService snapshots,
            @Value("${drishti.access-log.enabled:true}") boolean accessLog) {
        this.shares = shares;
        this.props = props;
        this.entitlements = entitlements;
        this.snapshots = snapshots;
        this.accessLog = accessLog;
    }

    /** What the console needs to draw the share dialog: whether collaboration is on, the limits, and what the caller may do. */
    @GetMapping("/collab")
    public Map<String, Object> config(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestParam(required = false) String kind,
            @RequestParam(required = false) String gateKind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("store", props.store());
        m.put("email", shares.emailAvailable());
        m.put("maxText", props.share().maxText());
        m.put("maxRecipients", props.share().maxRecipients());
        m.put("undeliverable", props.share().undeliverable());
        m.put("onMaskedCopy", props.text().onMaskedCopy());
        m.put("postToThread", props.share().postToThread());
        m.put("minQuery", props.directory().minQuery());
        m.put("snapshots", kind != null && !kind.isBlank() && snapshots.allowed(kind.trim(), gateKind == null || gateKind.isBlank() ? null : gateKind.trim()));
        m.put("collaborate", props.enabled() && entitlements.mayCollaborate(p));
        m.put("compliance", entitlements.mayCompliance(p));
        return m;
    }

    @PostMapping("/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareService.Result send(@RequestBody ShareService.Request body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return shares.share(p, body, asOf, accessLog);
    }

    /** The picture a share with these recipients would carry (the dialog's preview). Nothing is sent. */
    @PostMapping("/shares/preview-picture")
    public ResponseEntity<byte[]> preview(@RequestBody ShareService.Request body, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return png(shares.previewPicture(p, body, asOf));
    }

    /** The watermarked picture of a share, for its sender, its recipients and compliance. */
    @GetMapping(value = "/shares/{id}/picture", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> picture(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return png(shares.picture(p, id));
    }

    private static ResponseEntity<byte[]> png(SnapshotService.Image img) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("X-Drishti-Snapshot-Masked", Boolean.toString(img.masked())).body(img.png());
    }

    @GetMapping("/shares/{id}")
    public ShareService.View open(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return shares.open(p, id);
    }

    /** A reply from the sender or a recipient; the other party is told. */
    @PostMapping("/shares/{id}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public com.ash.drishti.server.collab.thread.ThreadService.CommentView reply(@PathVariable String id, @RequestBody Map<String, String> body,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return shares.reply(p, id, body.get("note"), asOf);
    }

    @GetMapping("/me/shares")
    public List<ShareService.Summary> mine(@RequestParam(defaultValue = "received") String box, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return shares.box(p, box, limit, before);
    }
}
