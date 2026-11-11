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
package com.ash.drishti.server.collab.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** How a message is written for one kind of endpoint: the body, and the headers that go with it. */
public interface BridgeFormat {

    /** An encoded request. */
    record Request(byte[] body, Map<String, String> headers) {}

    ObjectMapper JSON = new ObjectMapper();

    /** The request for the bridge; {@code seq} is the outbox delivery number (0 for a test), {@code nowSeconds} the signing time. */
    Request encode(BridgeMessage m, BridgeRegistry.Bridge bridge, long seq, long nowSeconds);

    /** The format for a bridge's {@code format} setting. */
    static BridgeFormat of(String name) {
        return switch (name) {
            case "teams" -> new Teams();
            case "slack" -> new Slack();
            default -> new Generic();
        };
    }

    /** The detail line under the headline: view, panel, date. Plain text, no values. */
    static String detail(BridgeMessage m) {
        StringBuilder b = new StringBuilder();
        if (m.kind() != null) {
            b.append(m.entityId() == null ? m.kind() : m.kind() + " " + m.entityId());
        }
        if (m.panel() != null) {
            b.append(b.length() > 0 ? " · " : "").append("Panel: ").append(m.panel());
        }
        if (m.when() != null) {
            b.append(b.length() > 0 ? " · " : "").append("As of: ").append(m.when());
        }
        return b.toString();
    }

    /** {@code application/json} with an HMAC-SHA256 signature of {@code timestamp + "." + body}. */
    final class Generic implements BridgeFormat {
        @Override
        public Request encode(BridgeMessage m, BridgeRegistry.Bridge bridge, long seq, long nowSeconds) {
            ObjectNode o = JSON.createObjectNode();
            o.put("schema", "drishti.bridge/1");
            o.put("event", m.event());
            o.put("id", m.id());
            o.put("product", m.product());
            o.put("headline", m.headline());
            o.put("kind", m.kind());
            o.put("entityId", m.entityId());
            o.put("panel", m.panel());
            o.put("asOf", m.when());
            o.put("note", m.note());
            o.put("link", m.link());
            o.put("at", m.at() == null ? null : m.at().toString());
            byte[] body = bytes(o);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("X-Drishti-Event", m.event());
            h.put("X-Drishti-Delivery", Long.toString(seq));
            h.put("X-Drishti-Timestamp", Long.toString(nowSeconds));
            h.put("X-Drishti-Signature", "sha256=" + sign(bridge.secret(), nowSeconds + ".", body));
            return new Request(body, h);
        }
    }

    /** A Teams message with an adaptive card: works for the incoming webhook connector and for Workflows ("post to a channel when a webhook is received"). */
    final class Teams implements BridgeFormat {
        @Override
        public Request encode(BridgeMessage m, BridgeRegistry.Bridge bridge, long seq, long nowSeconds) {
            ObjectNode card = JSON.createObjectNode();
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("type", "AdaptiveCard");
            card.put("version", "1.4");
            ArrayNode body = card.putArray("body");
            body.add(text(m.headline(), true));
            String d = detail(m);
            if (!d.isEmpty()) {
                body.add(text(d, false));
            }
            if (m.note() != null && !m.note().isBlank()) {
                body.add(text(m.note(), false));
            }
            ObjectNode action = card.putArray("actions").addObject();
            action.put("type", "Action.OpenUrl");
            action.put("title", "Open in " + m.product());
            action.put("url", m.link());
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "message");
            ObjectNode att = root.putArray("attachments").addObject();
            att.put("contentType", "application/vnd.microsoft.card.adaptive");
            att.putNull("contentUrl");
            att.set("content", card);
            return new Request(bytes(root), Map.of("Content-Type", "application/json"));
        }

        private static ObjectNode text(String t, boolean bold) {
            ObjectNode n = JSON.createObjectNode();
            n.put("type", "TextBlock");
            n.put("text", t);
            n.put("wrap", true);
            if (bold) {
                n.put("weight", "Bolder");
                n.put("size", "Medium");
            }
            return n;
        }
    }

    /** A Slack incoming-webhook message with blocks (mrkdwn escaped: {@code & < >}). */
    final class Slack implements BridgeFormat {
        @Override
        public Request encode(BridgeMessage m, BridgeRegistry.Bridge bridge, long seq, long nowSeconds) {
            ObjectNode root = JSON.createObjectNode();
            root.put("text", escape(m.headline()) + " " + m.link());
            ArrayNode blocks = root.putArray("blocks");
            StringBuilder head = new StringBuilder("*").append(escape(m.headline()).replace("*", "")).append('*');
            String d = detail(m);
            if (!d.isEmpty()) {
                head.append('\n').append(escape(d));
            }
            section(blocks, head.toString());
            if (m.note() != null && !m.note().isBlank()) {
                section(blocks, "> " + escape(m.note()).replace("\n", "\n> "));
            }
            ObjectNode actions = blocks.addObject();
            actions.put("type", "actions");
            ObjectNode button = actions.putArray("elements").addObject();
            button.put("type", "button");
            button.putObject("text").put("type", "plain_text").put("text", "Open in " + m.product());
            button.put("url", m.link());
            return new Request(bytes(root), Map.of("Content-Type", "application/json"));
        }

        private static void section(ArrayNode blocks, String mrkdwn) {
            ObjectNode b = blocks.addObject();
            b.put("type", "section");
            b.putObject("text").put("type", "mrkdwn").put("text", mrkdwn);
        }

        static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static byte[] bytes(ObjectNode o) {
        try {
            return JSON.writeValueAsBytes(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hex HMAC-SHA256 of {@code prefix + body}. */
    static String sign(byte[] secret, String prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
