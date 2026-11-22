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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.security.Principal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The Homes there are and who is offered which (ROLE_HOME_SUTRAS.md 1). The three visibility rules, none new: a Home is offered while the
 * modules it names are on; it is offered to a person whose roles name it ({@code drishti.homes.roles}; an administrator: all); and, when it
 * lists {@code requires:}, while at least one of those mnemonics is active. Panels hide by their own {@code requires: mnemonic.<X>}, answered
 * by the host when the screen is drawn. Read once at start and again on {@link #reload()}; a file with a fault is left out and said in
 * {@link #faults()}, never half-loaded.
 */
public final class HomeCatalogue {

    private final HomeProperties props;
    private volatile Map<String, HomeDefinition> homes = Map.of();
    private volatile Map<String, String> faults = Map.of();

    public HomeCatalogue(HomeProperties props) {
        this.props = props;
        reload();
    }

    /** Reads every {@code *.home.yaml} under the Homes folder. */
    public synchronized void reload() {
        Map<String, HomeDefinition> next = new LinkedHashMap<>();
        Map<String, String> bad = new LinkedHashMap<>();
        if (props.enabled() && props.dir() != null && !props.dir().isBlank() && Files.isDirectory(Path.of(props.dir()))) {
            try (Stream<Path> s = Files.walk(Path.of(props.dir()))) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".home.yaml")).sorted().toList()) {
                    try {
                        HomeDefinition d = HomeFiles.read(f);
                        if (next.putIfAbsent(d.name(), d) != null) {
                            bad.put(f.toString(), "the Home " + d.name() + " is already defined");
                        }
                    } catch (IOException | RuntimeException e) {
                        bad.put(f.toString(), String.valueOf(e.getMessage()));
                    }
                }
            } catch (IOException e) {
                bad.put(props.dir(), String.valueOf(e.getMessage()));
            }
        }
        homes = Map.copyOf(next);
        faults = Map.copyOf(bad);
    }

    public Map<String, String> faults() {
        return faults;
    }

    public Optional<HomeDefinition> find(String name) {
        return Optional.ofNullable(homes.get(name));
    }

    public List<HomeDefinition> all() {
        return new ArrayList<>(homes.values());
    }

    /**
     * The Homes a person is offered, their role's default first. {@code modulesOn} answers whether a module is on; {@code mnemonicActive}
     * whether a mnemonic is active for the site and the person; {@code admin} sees every offered Home.
     */
    public List<HomeDefinition> offered(Principal p, boolean admin, Predicate<String> modulesOn, Predicate<String> mnemonicActive) {
        List<String> order = new ArrayList<>();
        for (String role : p.roles() == null ? List.<String>of() : p.roles()) {
            for (String h : props.roles().getOrDefault(role, List.of())) {
                if (!order.contains(h)) {
                    order.add(h);
                }
            }
        }
        if (admin) {
            homes.keySet().stream().filter(h -> !order.contains(h)).forEach(order::add);
        }
        List<HomeDefinition> out = new ArrayList<>();
        for (String name : order) {
            HomeDefinition d = homes.get(name);
            if (d != null && d.modules().stream().allMatch(modulesOn) && (d.requires().isEmpty() || d.requires().stream().anyMatch(mnemonicActive))) {
                out.add(d);
            }
        }
        return out;
    }

    /** The Home to open: the person's chosen one when they are offered it, else their role's first. */
    public Optional<HomeDefinition> start(List<HomeDefinition> offered, String chosen) {
        return offered.stream().filter(d -> d.name().equals(chosen)).findFirst().or(() -> offered.stream().findFirst());
    }

    /**
     * The Sutra as served: a locked Home has {@code user-can-arrange} set false whatever the file says (the one server-side rule of
     * "arrange my Home"; the stored arrangement is then not applied either, see {@link #arrangementApplies}).
     */
    public String sutraFor(HomeDefinition d) {
        String text = d.sutraText();
        return d.arrange() ? text : text.replaceAll("(?m)^(\\s*user-can-arrange:\\s*)true", "$1false");
    }

    public boolean arrangementApplies(HomeDefinition d) {
        return d.arrange();
    }

    /** Mnemonics a Home's panels ask for ({@code requires: mnemonic.X}), for the picker and the tests. */
    public Set<String> mnemonicsOf(HomeDefinition d) {
        Set<String> out = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("requires:\\s*mnemonic\\.([A-Za-z0-9_-]+)").matcher(d.sutraText());
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
