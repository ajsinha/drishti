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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsException;
import com.ash.drishti.api.tls.TlsMaterial;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.util.SerializableSupplier;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Iceberg's S3FileIO (the object store behind a REST catalog) over TLS with the shared {@code tls.*} settings. Iceberg names the
 * file IO class in the catalog property {@code io-impl}; this one is S3FileIO with an S3 client whose HTTP layer has the
 * module's trust (a private CA for MinIO, Ceph, an internal gateway) and client certificate. The endpoint, region, path-style
 * and credentials are the catalog properties the stock S3FileIO reads.
 *
 * <p>Public with a no-argument constructor because Iceberg instantiates it by name. {@link IcebergLake.RestLake} builds and
 * checks the material once, at start, and registers it under the {@code drishti.tls.id} property.
 */
public final class TlsS3FileIO extends S3FileIO {

    /** The catalog property carrying the id of the registered material. */
    static final String ID = "drishti.tls.id";
    private static final Map<String, Material> REGISTRY = new ConcurrentHashMap<>();

    /** What the file IO is given: the object store's TLS material and/or outbound proxy (either may be absent). */
    record Material(TlsMaterial tls, ProxySettings proxy) {}

    private final Clients clients;

    public TlsS3FileIO() {
        this(new Clients());
    }

    private TlsS3FileIO(Clients clients) {
        super(clients);
        this.clients = clients;
    }

    static String register(TlsMaterial m) {
        return register(m, ProxySettings.none());
    }

    static String register(TlsMaterial m, ProxySettings proxy) {
        String id = java.util.UUID.randomUUID().toString();
        REGISTRY.put(id, new Material(m, proxy));
        return id;
    }

    static void unregister(String id) {
        if (id != null) {
            REGISTRY.remove(id);
        }
    }

    @Override
    public void initialize(Map<String, String> props) {
        Material m = props.get(ID) == null ? null : REGISTRY.get(props.get(ID));
        if (m == null) {
            throw new TlsException("TlsS3FileIO has no TLS or proxy settings (the catalog was closed, or io-impl was set without tls.* or proxy.* on the connector)");
        }
        if (m.tls() != null && !m.tls().settings().verifyHostname()) {
            throw new TlsException("tls.verify-hostname: false is not supported for Iceberg's S3 client (the AWS client always checks the host "
                    + "name): give the endpoint a name the certificate carries");
        }
        clients.tls = m.tls();
        clients.proxy = m.proxy();
        clients.properties = Map.copyOf(props);
        super.initialize(props);
    }

    /** The S3 client, made on first use from the properties and the material {@link #initialize} was given. */
    private static final class Clients implements SerializableSupplier<S3Client> {
        private transient volatile TlsMaterial tls;
        private transient volatile ProxySettings proxy = ProxySettings.none();
        private transient volatile Map<String, String> properties;

        @Override
        public S3Client get() {
            AwsClientProperties client = new AwsClientProperties(properties);
            S3FileIOProperties s3 = new S3FileIOProperties(properties);
            TlsMaterial m = tls;
            ApacheHttpClient.Builder http = ApacheHttpClient.builder();
            if (m != null) {
                http.tlsTrustManagersProvider(m::trustManagers).tlsKeyManagersProvider(m::keyManagers);
            }
            ProxySettings px = proxy;
            if (px.active()) {                                  // the host was checked against no-proxy when the catalog was opened
                var pc = ProxyConfiguration.builder().endpoint(px.uri()).useSystemPropertyValues(false).useEnvironmentVariableValues(false);
                if (px.hasCredentials()) {
                    pc.username(px.username()).password(px.password()).preemptiveBasicAuthenticationEnabled(true);
                }
                http.proxyConfiguration(pc.build());
            }
            S3ClientBuilder b = S3Client.builder().httpClientBuilder(http);
            client.applyClientRegionConfiguration(b);
            client.applyLegacyMd5Plugin(b);
            s3.applyCredentialConfigurations(client, b);
            s3.applyServiceConfigurations(b);
            s3.applySignerConfiguration(b);
            s3.applyEndpointConfigurations(b);
            s3.applyRetryConfigurations(b);
            return b.build();
        }
    }
}
