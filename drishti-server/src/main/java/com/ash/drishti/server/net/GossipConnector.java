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
package com.ash.drishti.server.net;

import com.ash.drishti.net.config.NodeSettings;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerSslBundle;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;

/**
 * The gossip port (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 5, "gossip off the busy path", 2.6.7): a second Tomcat connector of the same server, opened when
 * the node joins, that serves only {@code POST /api/v1/net/gossip}, {@code GET /api/v1/net/ping} and {@code GET /api/v1/net/digest}
 * ({@link GossipPath}). It has its own acceptor, its own connection limit and its own small pool of <b>platform</b> threads, so a server
 * whose request handling is saturated (virtual-thread carriers held by CPU-bound work, a platform request pool full of slow reads, the
 * connection limit reached, every admission slot taken) still answers its peers' gossip and probes at once, and is never wrongly suspected.
 *
 * <p>{@code gossip-port: auto} (node.yaml, the default) binds a free port and the node advertises it in its member entry; a number binds
 * that port (a firewall that must name it); {@code off} opens nothing and gossip uses the server's port, as before 2.6.7. With the server
 * on TLS ({@code server.ssl.*}) the gossip port uses the same certificate, trust store and client authentication.
 */
public final class GossipConnector implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GossipConnector.class);
    /** Request threads of the gossip port: platform threads, a few, never virtual. */
    static final int THREADS = 8;

    private final ApplicationContext context;
    private final ObjectProvider<ServerProperties> server;
    private final ObjectProvider<SslBundles> bundles;
    private volatile Connector connector;
    private volatile int wanted = Integer.MIN_VALUE;

    public GossipConnector(ApplicationContext context, ObjectProvider<ServerProperties> server, ObjectProvider<SslBundles> bundles) {
        this.context = context;
        this.server = server;
        this.bundles = bundles;
    }

    /** The bound port, or 0 when none is open. */
    public int port() {
        Connector c = connector;
        return c == null ? 0 : c.getLocalPort();
    }

    /** True for a request that came in on the gossip port. */
    public boolean isGossipPort(int localPort) {
        int p = port();
        return p > 0 && p == localPort;
    }

    /**
     * Opens the gossip port as node.yaml asks ({@link NodeSettings#GOSSIP_AUTO}, {@link NodeSettings#GOSSIP_OFF} or a port) and gives the
     * bound port, 0 for none. The same request again keeps the port open; another closes it and opens the new one.
     */
    public synchronized int open(int ask) {
        if (ask == NodeSettings.GOSSIP_OFF) {
            close();
            wanted = ask;
            return 0;
        }
        Connector c = connector;
        if (c != null && ask == wanted && c.getState() == LifecycleState.STARTED) {
            return c.getLocalPort();
        }
        close();
        Tomcat tomcat = tomcat();
        if (tomcat == null) {
            LOG.info("net: no Tomcat web server here: gossip goes to the server's port");
            return 0;
        }
        Connector made = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        made.setPort(ask == NodeSettings.GOSSIP_AUTO ? 0 : ask);
        made.setProperty("maxThreads", Integer.toString(THREADS));
        made.setProperty("minSpareThreads", "2");
        made.setProperty("maxConnections", "4096");       // one kept-alive connection per peer, and room for many peers
        made.setProperty("acceptCount", "128");
        made.setProperty("connectionTimeout", "5000");
        made.setProperty("keepAliveTimeout", "120000");
        made.setProperty("maxKeepAliveRequests", "-1");
        made.setProperty("maxPostSize", Integer.toString(16 * 1024 * 1024));
        ServerProperties sp = server.getIfAvailable();
        if (sp != null && sp.getAddress() != null) {
            made.setProperty("address", sp.getAddress().getHostAddress());
        }
        if (sp != null && Ssl.isEnabled(sp.getSsl())) {
            tls(made, sp.getSsl());
        }
        tomcat.getService().addConnector(made);           // a started service starts the connector now
        if (made.getState() != LifecycleState.STARTED || made.getLocalPort() <= 0) {
            tomcat.getService().removeConnector(made);
            throw new IllegalStateException("the gossip port " + (ask == NodeSettings.GOSSIP_AUTO ? "(auto)" : ask) + " could not be opened");
        }
        connector = made;
        wanted = ask;
        LOG.info("net: gossip port {} open ({} platform threads of its own; gossip, probes and digests only)", made.getLocalPort(), THREADS);
        return made.getLocalPort();
    }

    @Override
    public synchronized void close() {
        Connector c = connector;
        connector = null;
        if (c != null) {
            Tomcat tomcat = tomcat();
            try {
                if (tomcat != null) {
                    tomcat.getService().removeConnector(c);   // stops it
                } else {
                    c.stop();
                }
                c.destroy();
            } catch (Exception e) {
                LOG.debug("net: closing the gossip port: {}", e.toString());
            }
        }
    }

    private Tomcat tomcat() {
        if (context instanceof ServletWebServerApplicationContext w && w.getWebServer() instanceof TomcatWebServer t) {
            return t.getTomcat();
        }
        return null;
    }

    /** The server's own TLS on the gossip port: certificate, trust store, client authentication, protocols and ciphers. */
    private void tls(Connector c, Ssl ssl) {
        SslBundle bundle = WebServerSslBundle.get(ssl, bundles.getIfAvailable());
        AbstractHttp11Protocol<?> p = (AbstractHttp11Protocol<?>) c.getProtocolHandler();
        c.setScheme("https");
        c.setSecure(true);
        p.setSSLEnabled(true);
        SSLHostConfig host = new SSLHostConfig();
        host.setHostName(p.getDefaultSSLHostConfigName());
        host.setSslProtocol(bundle.getProtocol());
        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
        SslStoreBundle stores = bundle.getStores();
        cert.setCertificateKeystore(stores.getKeyStore());
        String keyPassword = bundle.getKey().getPassword() != null ? bundle.getKey().getPassword() : stores.getKeyStorePassword();
        if (keyPassword != null) {
            cert.setCertificateKeystorePassword(keyPassword);
        }
        if (bundle.getKey().getAlias() != null) {
            cert.setCertificateKeyAlias(bundle.getKey().getAlias());
        }
        host.addCertificate(cert);
        if (stores.getTrustStore() != null) {
            host.setTrustStore(stores.getTrustStore());
        }
        Ssl.ClientAuth auth = ssl.getClientAuth();
        host.setCertificateVerification(auth == Ssl.ClientAuth.NEED ? "required" : auth == Ssl.ClientAuth.WANT ? "optional" : "none");
        if (bundle.getOptions().getEnabledProtocols() != null) {
            host.setProtocols(String.join(",", bundle.getOptions().getEnabledProtocols()));
        }
        if (bundle.getOptions().getCiphers() != null) {
            host.setCiphers(String.join(",", bundle.getOptions().getCiphers()));
        }
        p.addSslHostConfig(host);
    }
}
