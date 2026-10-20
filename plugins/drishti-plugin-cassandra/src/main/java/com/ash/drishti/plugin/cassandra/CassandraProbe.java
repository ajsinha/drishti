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
package com.ash.drishti.plugin.cassandra;

import com.ash.drishti.api.tls.TlsMaterial;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/**
 * Why a connector cannot connect, in words (QA3B-11): the driver keeps retrying in the background and says why only in its own WARN
 * lines, so Health said "connecting to Cassandra at …" alike for a closed port, a node that asks for a client certificate and a node that
 * speaks TLS to a connector that does not. The probe opens one connection to the first contact point itself, with the connector's TLS
 * when it has one, asks the node CQL's {@code OPTIONS}, and names a TLS, handshake or certificate failure with what to set. Empty when the
 * node answers CQL (the cause is elsewhere: the log-in, the data centre, the keyspace).
 */
final class CassandraProbe {

    /** CQL v4 {@code OPTIONS}: version, flags, stream 0, opcode 5, no body. Every node answers it before any log-in. */
    private static final byte[] OPTIONS = {0x04, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00};

    private CassandraProbe() {}

    /** The diagnosis for the first contact point that gives one, or empty. Never throws; bounded by {@code timeout} per step. */
    static Optional<String> diagnose(List<InetSocketAddress> contactPoints, TlsMaterial tls, Duration timeout) {
        for (InetSocketAddress a : contactPoints) {
            Optional<String> why = diagnose(a, tls, timeout);
            if (why.isPresent()) {
                return why;
            }
        }
        return Optional.empty();
    }

    static Optional<String> diagnose(InetSocketAddress at, TlsMaterial tls, Duration timeout) {
        String where = at.getHostString() + ":" + at.getPort();
        int ms = (int) Math.max(200, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        Socket plain = new Socket();
        try {
            try {
                plain.connect(new InetSocketAddress(at.getHostString(), at.getPort()), ms);
            } catch (IOException e) {
                return Optional.of("no connection to " + where + " (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())
                        + "): check contact-points, the port and that the node runs");
            }
            plain.setSoTimeout(ms);
            if (tls == null) {
                return plainText(plain, where);
            }
            return overTls(plain, at, tls, where);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();                                  // nothing definite: the health line stays as it was
        } finally {
            try {
                plain.close();
            } catch (IOException ignored) {
                // closing a probe socket
            }
        }
    }

    /** A connector without TLS: a node that demands TLS closes the connection (or answers with a TLS record) instead of CQL. */
    private static Optional<String> plainText(Socket s, String where) throws IOException {
        int first;
        try {
            OutputStream out = s.getOutputStream();
            out.write(OPTIONS);
            out.flush();
            first = s.getInputStream().read();
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } catch (IOException e) {
            first = -1;                                               // reset: the node dropped the plain-text bytes
        }
        if (first == -1 || first == 0x15 || first == 0x16) {
            return Optional.of("TLS: the node at " + where + " did not answer CQL in plain text and closed the connection, so it requires TLS "
                    + "(client_encryption_options): set tls.enabled: true with tls.ca-file (or tls.truststore), and tls.cert-file and tls.key-file "
                    + "when it asks for a client certificate");
        }
        return Optional.empty();
    }

    private static Optional<String> overTls(Socket plain, InetSocketAddress at, TlsMaterial tls, String where) throws IOException {
        SSLSocket s = (SSLSocket) tls.sslContext().getSocketFactory().createSocket(plain, at.getHostString(), at.getPort(), true);
        s.setSSLParameters(tls.sslParameters());
        boolean identity = tls.clientKey() != null;
        try {
            s.startHandshake();
            OutputStream out = s.getOutputStream();
            out.write(OPTIONS);
            out.flush();
            InputStream in = s.getInputStream();
            int first = in.read();
            if (first == -1) {
                throw new EOFException("closed after the handshake");
            }
            return Optional.empty();                                  // the node answered over TLS: not a TLS problem
        } catch (SocketTimeoutException e) {
            return Optional.empty();
        } catch (SSLHandshakeException | EOFException e) {
            return Optional.of(tlsWords(e, identity, where));
        } catch (SSLException e) {
            return Optional.of(tlsWords(e, identity, where));
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("reset") || m.contains("broken pipe") || m.contains("certificate")) {
                return Optional.of(tlsWords(e, identity, where));    // TLS 1.3: the refusal of a missing certificate arrives on the first read
            }
            return Optional.empty();
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a probe socket
            }
        }
    }

    /** A TLS failure in words: what the node refused and which {@code tls.*} settings to look at. */
    static String tlsWords(Throwable e, boolean identity, String where) {
        String raw = String.valueOf(e.getMessage());
        String m = raw.toLowerCase(Locale.ROOT);
        String head = "TLS to " + where + " failed (" + e.getClass().getSimpleName() + ": " + raw + "): ";
        if (m.contains("unsupported or unrecognized ssl message") || m.contains("plaintext connection")) {
            return head + "the node does not speak TLS on this port: set tls.enabled: false, or use the node's TLS port";
        }
        if (m.contains("pkix") || m.contains("certification path") || m.contains("unable to find valid") || m.contains("signature check failed")) {
            return head + "the node's certificate is not trusted: set tls.ca-file (or tls.truststore) to the authority that signed it";
        }
        if (m.contains("no subject alternative") || m.contains("no name matching") || m.contains("hostname")) {
            return head + "the node's certificate does not name " + where.substring(0, where.lastIndexOf(':'))
                    + ": connect by a name the certificate holds, or set tls.verify-hostname: false";
        }
        if (m.contains("expired") || m.contains("not yet valid")) {
            return head + "a certificate is out of its dates: renew it (tls.cert-file, or the node's)";
        }
        if (!identity) {
            return head + "the node asks for a client certificate (mutual TLS) and none is set: set tls.cert-file and tls.key-file "
                    + "(or tls.keystore), signed by an authority the node's truststore holds";
        }
        if (m.contains("certificate") || m.contains("bad_certificate") || m.contains("unknown_ca") || m.contains("closed after the handshake")
                || m.contains("reset")) {
            return head + "the node refused the client certificate: it must be signed by an authority in the node's truststore "
                    + "(check tls.cert-file, tls.key-file or tls.keystore, and the node's client_encryption_options)";
        }
        return head + "check tls.ca-file or tls.truststore, the client certificate (tls.cert-file, tls.key-file, tls.keystore) and tls.protocols";
    }
}
