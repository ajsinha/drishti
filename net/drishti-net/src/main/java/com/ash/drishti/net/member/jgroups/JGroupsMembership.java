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
package com.ash.drishti.net.member.jgroups;

import com.ash.drishti.net.config.HostAddress;
import com.ash.drishti.net.member.Membership;
import com.ash.drishti.net.member.NodeIdentity;
import com.ash.drishti.net.member.View;
import com.ash.drishti.net.member.ViewChange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jgroups.Address;
import org.jgroups.BytesMessage;
import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.MergeView;
import org.jgroups.Message;
import org.jgroups.PhysicalAddress;
import org.jgroups.Receiver;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.ExtendedUUID;
import org.jgroups.util.NameCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Membership} on JGroups (CLUSTER_I5_DESIGN.md sections 2.2 and 3.1): agreed views over TCP, the stack of {@link JGroupsStack}.
 * A member's logical name is its node id; its role travels in its address ({@code drishti.role}); its cluster address is the transport's
 * physical address. Messages are {@code topic} and bytes, at most {@link Membership#MAX_MESSAGE_BYTES}, sent to the whole view.
 *
 * <p>Every address this provider admits or reports is a real one: the bind host must not be loopback or wildcard ({@link HostAddress}).
 * Thread-safety: join, leave and close take one lock; the view is a volatile reference swapped whole; listeners are told on the JGroups
 * thread that delivered the view or the message.
 */
public final class JGroupsMembership implements Membership {

    private static final Logger LOG = LoggerFactory.getLogger(JGroupsMembership.class);
    private static final String ROLE = "drishti.role";

    private final NodeIdentity self;
    private final JGroupsStack.Spec spec;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Consumer<ViewChange>> viewListeners = new CopyOnWriteArrayList<>();
    private final Map<String, List<BiConsumer<String, byte[]>>> messageListeners = new ConcurrentHashMap<>();
    private volatile JChannel channel;
    private volatile View view = View.NONE;

    /**
     * @param nodeId this node's id (the channel's logical name)
     * @param role {@code server} or {@code witness}
     * @param spec what the stack is built from
     */
    public JGroupsMembership(String nodeId, String role, JGroupsStack.Spec spec) {
        if (HostAddress.loopbackOrWildcard(spec.bindHost())) {
            throw new IllegalArgumentException(HostAddress.refusal("the cluster address", "http://" + spec.bindHost() + ":" + spec.port()));
        }
        this.spec = Objects.requireNonNull(spec);
        this.self = new NodeIdentity(nodeId, spec.bindHost() + ":" + spec.port(), role);
    }

    @Override
    public NodeIdentity self() {
        return self;
    }

    @Override
    public View view() {
        return view;
    }

    /** The cluster's name ({@code [net.cluster] name}). */
    public String clusterName() {
        return spec.clusterName();
    }

    @Override
    public void join() {
        lock.lock();
        try {
            if (channel != null) {
                return;
            }
            JChannel ch = new JChannel(JGroupsStack.protocols(spec));
            ch.addAddressGenerator(() -> ExtendedUUID.randomUUID(self.id()).put(ROLE, self.role().getBytes(StandardCharsets.UTF_8)));
            ch.name(self.id());
            ch.setReceiver(new Receiver() {
                @Override
                public void viewAccepted(org.jgroups.View v) {
                    accepted(ch, v);
                }

                @Override
                public void receive(Message msg) {
                    received(msg);
                }
            });
            channel = ch;
            ch.connect(spec.clusterName());
            LOG.info("net: {} joined the cluster {} on {} ({} seeds{})", self.id(), spec.clusterName(), self.address(), spec.seeds().size(),
                    spec.tls() != null ? ", TLS" : ", encrypted with the cluster key");
        } catch (Exception e) {
            JChannel ch = channel;
            channel = null;
            if (ch != null) {
                ch.close();
            }
            throw new IllegalStateException("net: " + self.id() + " cannot join the cluster " + spec.clusterName() + " on " + self.address() + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void leave() {
        JChannel ch;
        lock.lock();
        try {
            ch = channel;
            channel = null;
        } finally {
            lock.unlock();
        }
        if (ch == null) {
            return;
        }
        ch.close();                                            // a clean leave: the coordinator installs a view without this node at once
        View before = view;
        view = View.NONE;
        tell(ViewChange.between(before, View.NONE, false));
    }

    @Override
    public void onChange(Consumer<ViewChange> listener) {
        viewListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void publish(String topic, byte[] small) {
        byte[] payload = frame(topic, small);
        if (payload.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("a membership message is at most " + MAX_MESSAGE_BYTES + " bytes; " + topic + " has " + payload.length);
        }
        JChannel ch = channel;
        if (ch == null) {
            throw new IllegalStateException("net: " + self.id() + " has not joined the cluster");
        }
        try {
            ch.send(new BytesMessage(null, payload));
        } catch (Exception e) {
            throw new IllegalStateException("net: sending " + topic + " to the cluster failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void onMessage(String topic, BiConsumer<String, byte[]> listener) {
        messageListeners.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(Objects.requireNonNull(listener));
    }

    @Override
    public void close() {
        leave();
    }

    /**
     * Stops the stack without a leave, as a killed process does (the contract suite's and the chaos suite's kill): the failure detector's
     * socket closes, so the peers find the node gone without waiting for the heartbeat time-out.
     */
    public void crash() {
        JChannel ch;
        lock.lock();
        try {
            ch = channel;
            channel = null;
        } finally {
            lock.unlock();
        }
        if (ch != null) {
            try {
                ch.getProtocolStack().stopStack(spec.clusterName());
                ch.getProtocolStack().destroy();
            } catch (RuntimeException e) {
                LOG.debug("net: crashing {}: {}", self.id(), e.toString());
            }
        }
        view = View.NONE;
    }

    // -- JGroups callbacks ------------------------------------------------------------------------------------------------

    private void accepted(JChannel ch, org.jgroups.View v) {
        List<NodeIdentity> members = new ArrayList<>(v.size());
        for (Address a : v.getMembers()) {
            members.add(new NodeIdentity(name(a), physical(ch, a), role(a)));
        }
        View next = new View(v.getViewId().getId(), name(v.getCoord()), members);
        View before = view;
        view = next;
        ViewChange change = ViewChange.between(before, next, v instanceof MergeView);
        LOG.info("net: view {} of {}: {}{}{}", next.id(), spec.clusterName(), next.ids(), change.joined().isEmpty() ? "" : ", joined " + change.joined(),
                change.left().isEmpty() ? "" : ", left " + change.left());
        tell(change);
    }

    private void tell(ViewChange change) {
        for (Consumer<ViewChange> l : viewListeners) {
            try {
                l.accept(change);
            } catch (RuntimeException e) {
                LOG.warn("net: a view listener failed: {}", e.toString());
            }
        }
    }

    private void received(Message msg) {
        String from = name(msg.getSrc());
        String topic;
        byte[] body;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(msg.getArray(), msg.getOffset(), msg.getLength()))) {
            topic = in.readUTF();
            body = in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            LOG.debug("net: a message from {} that is not a Drishti message was dropped: {}", from, e.toString());
            return;
        }
        for (BiConsumer<String, byte[]> l : messageListeners.getOrDefault(topic, List.of())) {
            try {
                l.accept(from, body);
            } catch (RuntimeException e) {
                LOG.warn("net: a {} listener failed: {}", topic, e.toString());
            }
        }
    }

    private static byte[] frame(String topic, byte[] body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream o = new DataOutputStream(b)) {
            o.writeUTF(topic);
            o.write(body == null ? new byte[0] : body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    private static String name(Address a) {
        if (a == null) {
            return "";
        }
        String n = NameCache.get(a);
        return n == null ? a.toString() : n;
    }

    private static String role(Address a) {
        if (a instanceof ExtendedUUID u) {
            byte[] r = u.get(ROLE);
            if (r != null) {
                return new String(r, StandardCharsets.UTF_8);
            }
        }
        return NodeIdentity.SERVER;
    }

    /** {@code host:port} of a member's transport, or empty when the transport does not know it yet. */
    private static String physical(JChannel ch, Address a) {
        try {
            Object p = ch.down(new Event(Event.GET_PHYSICAL_ADDRESS, a));
            if (p instanceof IpAddress ip) {
                return ip.getIpAddress().getHostAddress() + ":" + ip.getPort();
            }
            return p instanceof PhysicalAddress pa ? pa.toString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
