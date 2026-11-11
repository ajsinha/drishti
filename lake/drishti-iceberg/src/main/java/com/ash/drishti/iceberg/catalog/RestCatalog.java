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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.io.DrishtiFileIO;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.Bridge;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;

/**
 * A namespace of an Iceberg REST catalog (Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg endpoint, Unity Catalog, Nessie,
 * Lakekeeper, Gravitino) through Apache Iceberg's own REST client. Settings: {@code uri}, {@code warehouse}, {@code credential} or
 * {@code token}, {@code scope}, {@code oauth2-server-uri}, {@code prefix}, {@code namespace}, {@code catalog.<key>} (Iceberg catalog
 * properties, such as {@code catalog.rest.sigv4-enabled}); {@code vended-credentials: false} stops asking for the table's own S3
 * credentials. Files are read through {@link DrishtiFileIO}. Thread-safe.
 */
public final class RestCatalog implements Catalog {

    private final org.apache.iceberg.rest.RESTCatalog catalog;
    private final Namespace namespace;
    private final FileAccess io;
    private final String uri;
    private final ProxySettings proxy;

    private RestCatalog(org.apache.iceberg.rest.RESTCatalog catalog, Namespace namespace, FileAccess io, String uri, ProxySettings proxy) {
        this.catalog = catalog;
        this.namespace = namespace;
        this.io = io;
        this.uri = uri;
        this.proxy = proxy;
    }

    static RestCatalog open(Map<String, String> s, String domain, FileAccess io) {
        String uri = s.getOrDefault("uri", "").trim();
        if (uri.isBlank()) {
            io.close();
            throw new IllegalArgumentException("a REST catalog needs uri");
        }
        Map<String, String> props = new HashMap<>(s);                  // the connector's s3.*, tls.*, proxy.*, relocate: DrishtiFileIO reads them
        s.forEach((k, v) -> {
            if (k.startsWith("catalog.")) {
                props.put(k.substring(8), v);
            }
        });
        props.put("uri", uri);
        props.put("io-impl", DrishtiFileIO.class.getName());
        for (String k : List.of("warehouse", "credential", "token", "scope", "oauth2-server-uri", "prefix")) {
            if (s.getOrDefault(k, "").isBlank()) {
                props.remove(k);
            }
        }
        if (Boolean.parseBoolean(s.getOrDefault("vended-credentials", "true"))) {
            props.put("header.X-Iceberg-Access-Delegation", "vended-credentials");
        }
        ProxySettings proxy = ProxySettings.resolve(s, uri);
        if (proxy.appliesTo(java.net.URI.create(uri).getHost())) {
            props.put("rest.client.proxy.hostname", proxy.host());
            props.put("rest.client.proxy.port", String.valueOf(proxy.port()));
            if (proxy.hasCredentials()) {
                props.put("rest.client.proxy.username", proxy.username());
                props.put("rest.client.proxy.password", proxy.password());
            }
        }
        org.apache.iceberg.rest.RESTCatalog c = new org.apache.iceberg.rest.RESTCatalog();
        try {
            c.initialize(s.getOrDefault("source-name", "drishti"), props);
        } catch (org.apache.iceberg.exceptions.NotAuthorizedException e) {
            io.close();
            throw new IllegalStateException("the REST catalog at " + uri + " refused the credential: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            io.close();
            throw new IllegalStateException("cannot reach the REST catalog at " + uri + ": " + com.ash.drishti.iceberg.io.Reasons.of(e), e);
        }
        String ns = s.getOrDefault("namespace", "").isBlank() ? domain : s.get("namespace");
        return new RestCatalog(c, ns.isBlank() ? Namespace.empty() : Namespace.of(ns.split("\\.")), io, uri, proxy);
    }

    public ProxySettings proxy() {
        return proxy;
    }

    private TableIdentifier id(String name) {
        return TableIdentifier.of(namespace, name);
    }

    @Override
    public Optional<Table> load(String name) {
        try {
            return Optional.of(new Table(name, catalog.loadTable(id(name)), () -> catalog.loadTable(id(name))));
        } catch (NoSuchTableException | NoSuchNamespaceException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> tables() {
        try {
            return catalog.listTables(namespace).stream().map(TableIdentifier::name).sorted().toList();
        } catch (NoSuchNamespaceException e) {
            return List.of();
        }
    }

    @Override
    public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) throws IOException {
        if (!namespace.isEmpty() && !catalog.namespaceExists(namespace)) {
            try {
                catalog.createNamespace(namespace);
            } catch (AlreadyExistsException e) {
                // another writer created it meanwhile
            }
        }
        org.apache.iceberg.Schema s = Bridge.iceberg(schema);
        try {
            return new Table(name, catalog.buildTable(id(name), s).withPartitionSpec(Bridge.iceberg(spec, schema, s))
                    .withSortOrder(Bridge.iceberg(order, schema, s)).withProperties(properties).create(), () -> catalog.loadTable(id(name)));
        } catch (AlreadyExistsException e) {
            throw new TableExists("the catalog already has a table " + namespace + "." + name);
        }
    }

    @Override
    public boolean reachable() {
        try {
            return namespace.isEmpty() || catalog.namespaceExists(namespace);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String describe() {
        return uri + " namespace " + namespace;
    }

    @Override
    public void close() {
        try {
            catalog.close();
        } catch (IOException e) {
            // closing: nothing to save
        }
        io.close();
    }
}
