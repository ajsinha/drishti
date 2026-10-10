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
package com.ash.drishti.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Common bookkeeping for providers: the model list from the provider file, and lookup by logical name. */
public abstract class BaseProvider implements LlmProvider {

    protected final ProviderConfig config;
    private final List<ModelInfo> models;

    protected BaseProvider(ProviderConfig config, Set<Capability> defaultChatCapabilities) {
        this.config = config;
        List<ModelInfo> list = new ArrayList<>();
        for (ProviderConfig.ModelConfig m : config.models()) {
            Set<Capability> caps = new LinkedHashSet<>(m.capabilities());
            if (!"embedding".equals(m.kind()) && m.capabilities().equals(Set.of(Capability.CHAT))) {
                caps.addAll(defaultChatCapabilities);
            }
            list.add(new ModelInfo(m.name(), config.name(), m.id(), m.kind(), caps, m.maxTokens(), m.temperature(),
                    m.costPer1kInput(), m.costPer1kOutput()));
        }
        this.models = List.copyOf(list);
    }

    @Override
    public String id() {
        return config.name();
    }

    @Override
    public String type() {
        return config.type();
    }

    @Override
    public List<ModelInfo> models() {
        return models;
    }

    @Override
    public Set<Capability> capabilities() {
        Set<Capability> all = new LinkedHashSet<>();
        models.forEach(m -> all.addAll(m.capabilities()));
        return all;
    }

    protected Optional<ModelInfo> find(String logicalName, boolean embedding) {
        return models.stream().filter(m -> m.name().equals(logicalName) && m.embedding() == embedding).findFirst();
    }

    /** The model a request names, or the provider's default when it names none. */
    protected static int maxTokens(ChatRequest r, ModelInfo m, int dflt) {
        return r.maxTokens() != null ? r.maxTokens() : m.maxTokens() != null ? m.maxTokens() : dflt;
    }

    protected static Double temperature(ChatRequest r, ModelInfo m) {
        return r.temperature() != null ? r.temperature() : m.temperature();
    }

    /** The system text and the non-system turns of a request, system messages folded into the system text. */
    protected static String systemText(ChatRequest r) {
        StringBuilder sb = new StringBuilder(r.system());
        for (ChatMessage m : r.messages()) {
            if (m.role() == ChatMessage.Role.SYSTEM) {
                sb.append(sb.isEmpty() ? "" : "\n").append(m.content());
            }
        }
        return sb.toString();
    }
}
