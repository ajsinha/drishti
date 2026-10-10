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
package com.ash.drishti.api.tls;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Builds the TLS objects a client library takes ({@link SSLContext}, {@code KeyManager[]}, {@code TrustManager[]},
 * {@link SSLParameters}) from a connector's {@link TlsSettings}. Used by every connector, so every connector offers the same
 * trust and identity forms and fails with the same clear messages.
 *
 * <p>Pure JDK, no dependencies: it lives in the plugin API so a plugin can call it without Spring.
 *
 * <pre>{@code
 * TlsSettings tls = TlsSettings.from(ctx.settings());              // keys under "tls."
 * if (tls.enabled()) {
 *     TlsMaterial m = TlsContexts.build(tls);                      // throws TlsException with the file and the reason
 *     client.sslSocketFactory(m.socketFactory());
 *     // and for each socket: socket.setSSLParameters(m.sslParameters());
 * }
 * }</pre>
 */
public final class TlsContexts {

    private static final System.Logger LOG = System.getLogger("com.ash.drishti.tls");

    private TlsContexts() {}

    public static TlsMaterial build(TlsSettings s) {
        return build(s, System::getenv, Clock.systemUTC());
    }

    public static TlsMaterial build(TlsSettings s, Function<String, String> env, Clock clock) {
        List<String> warnings = new ArrayList<>();
        List<CertInfo> infos = new ArrayList<>();
        String p = s.prefix();
        if (s.insecureTrustAll() && !"true".equalsIgnoreCase(env.apply(TlsSettings.ALLOW_INSECURE_ENV))) {
            throw new TlsException(p + "insecure-trust-all is refused: it switches certificate checking off, so anyone on the network "
                    + "can impersonate the server. For development only, start the server with " + TlsSettings.ALLOW_INSECURE_ENV + "=true");
        }
        if (s.keystore() != null && (s.certFile() != null || s.keyFile() != null)) {
            throw new TlsException(p + "keystore and " + p + "cert-file/" + p + "key-file both set: give the client identity one way");
        }
        if ((s.certFile() == null) != (s.keyFile() == null)) {
            throw new TlsException((s.certFile() == null ? p + "key-file is set but " + p + "cert-file is not" : p + "cert-file is set but "
                    + p + "key-file is not") + ": a client identity needs both");
        }
        // client identity
        PrivateKey key = null;
        List<X509Certificate> chain = List.of();
        if (s.certFile() != null) {
            chain = PemReader.certificates(s.certFile(), p + "cert-file");
            key = PemReader.privateKey(s.keyFile(), s.keyPassword(), p + "key-file");
            checkPair(key, chain.get(0), p, s);
            chain.forEach(c -> infos.add(info(p + "cert-file", c)));
        } else if (s.keystore() != null) {
            KeyStore ks = loadStore(s.keystore(), s.keystoreType(), s.keystorePassword(), p + "keystore");
            String alias = pickAlias(ks, s.keyAlias(), p);
            try {
                String kp = s.keyPassword() != null ? s.keyPassword() : s.keystorePassword();
                key = (PrivateKey) ks.getKey(alias, kp == null ? null : kp.toCharArray());
                Certificate[] cs = ks.getCertificateChain(alias);
                if (key == null || cs == null || cs.length == 0) {
                    throw new TlsException(p + "keystore '" + s.keystore() + "': alias '" + alias + "' holds no private key with a "
                            + "certificate chain (it is a trusted-certificate entry)");
                }
                List<X509Certificate> list = new ArrayList<>();
                for (Certificate c : cs) {
                    list.add((X509Certificate) c);
                }
                chain = list;
            } catch (UnrecoverableKeyException e) {
                throw new TlsException(p + "keystore '" + s.keystore() + "': cannot recover the key '" + alias + "': wrong key password "
                        + "(" + p + "key-password; it defaults to the keystore password)", e);
            } catch (GeneralSecurityException e) {
                throw new TlsException(p + "keystore '" + s.keystore() + "': cannot read the key '" + alias + "' (" + e.getMessage() + ")", e);
            }
            chain.forEach(c -> infos.add(info(p + "keystore", c)));
        }
        // trust
        List<X509Certificate> trusted = new ArrayList<>();
        if (s.caFile() != null) {
            List<X509Certificate> cas = PemReader.certificates(s.caFile(), p + "ca-file");
            cas.forEach(c -> infos.add(info(p + "ca-file", c)));
            trusted.addAll(cas);
        }
        if (s.truststore() != null) {
            KeyStore ts = loadStore(s.truststore(), s.truststoreType(), s.truststorePassword(), p + "truststore");
            List<X509Certificate> fromStore = trustedCerts(ts, p, s.truststore());
            fromStore.forEach(c -> infos.add(info(p + "truststore", c)));
            trusted.addAll(fromStore);
        }
        boolean jvmDefault = s.trustJvmDefault() || (s.caFile() == null && s.truststore() == null);
        TrustManager[] tms;
        if (s.insecureTrustAll()) {
            tms = new TrustManager[] {new TrustAll()};
            warnings.add(p + "insecure-trust-all is ON: the server certificate is NOT checked. Development only.");
        } else {
            tms = trustManagers(trusted, jvmDefault, p);
        }
        KeyManager[] kms = key == null ? null : keyManagers(key, chain, p);
        // protocols, ciphers, hostname
        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLS");
            ctx.init(kms, tms, new SecureRandom());
        } catch (GeneralSecurityException e) {
            throw new TlsException("cannot create the TLS context (" + e.getMessage() + ")", e);
        }
        SSLParameters params = new SSLParameters();
        checkSupported(ctx, s);
        params.setProtocols(s.protocols().toArray(new String[0]));
        if (!s.cipherSuites().isEmpty()) {
            params.setCipherSuites(s.cipherSuites().toArray(new String[0]));
        }
        if (s.verifyHostname()) {
            params.setEndpointIdentificationAlgorithm("HTTPS");
        } else {
            params.setEndpointIdentificationAlgorithm(null);
            warnings.add(p + "verify-hostname is false: the server's name is NOT checked against its certificate. Anyone holding a "
                    + "certificate from a trusted authority can impersonate the server.");
        }
        Instant now = clock.instant();
        for (CertInfo c : infos) {
            if (c.expired(now)) {
                warnings.add("certificate " + c.subject() + " (" + c.source() + ") EXPIRED on " + date(c.notAfter()) + "; connections will fail");
            } else if (c.notYetValid(now)) {
                warnings.add("certificate " + c.subject() + " (" + c.source() + ") is not valid before " + date(c.notBefore()));
            } else if (c.daysLeft(now) < TlsMaterial.WARN_DAYS) {
                warnings.add("certificate " + c.subject() + " (" + c.source() + ") expires in " + c.daysLeft(now) + " days (" + date(c.notAfter()) + ")");
            }
        }
        warnings.forEach(w -> LOG.log(System.Logger.Level.WARNING, "TLS: " + w));
        return new TlsMaterial(s, ctx, kms, tms, params, key, List.copyOf(chain), List.copyOf(trusted), jvmDefault,
                List.copyOf(infos), List.copyOf(warnings));
    }

    private static String date(Instant i) {
        return i.toString().substring(0, 10);
    }

    private static CertInfo info(String source, X509Certificate c) {
        return new CertInfo(source, c.getSubjectX500Principal().getName(), c.getNotBefore().toInstant(), c.getNotAfter().toInstant());
    }

    private static void checkSupported(SSLContext ctx, TlsSettings s) {
        List<String> supported = List.of(ctx.getSupportedSSLParameters().getProtocols());
        for (String proto : s.protocols()) {
            if (!supported.contains(proto)) {
                throw new TlsException(s.prefix() + "protocols: '" + proto + "' is not supported by this JVM (supported: " + supported + ")");
            }
        }
        List<String> ciphers = List.of(ctx.getSupportedSSLParameters().getCipherSuites());
        for (String c : s.cipherSuites()) {
            if (!ciphers.contains(c)) {
                throw new TlsException(s.prefix() + "cipher-suites: '" + c + "' is not supported by this JVM");
            }
        }
    }

    /** A private key must belong to the certificate it is sent with: sign and verify a challenge. */
    private static void checkPair(PrivateKey key, X509Certificate leaf, String p, TlsSettings s) {
        PublicKey pub = leaf.getPublicKey();
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "EdDSA", "Ed25519" -> "Ed25519";
            case "DSA" -> "SHA256withDSA";
            case "RSASSA-PSS" -> "RSASSA-PSS";
            default -> null;
        };
        if (alg == null) {
            return;
        }
        try {
            byte[] challenge = "drishti-tls-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature sig = Signature.getInstance(alg);
            if ("RSASSA-PSS".equals(alg)) {
                sig.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
            }
            sig.initSign(key);
            sig.update(challenge);
            byte[] signed = sig.sign();
            Signature check = Signature.getInstance(alg);
            if ("RSASSA-PSS".equals(alg)) {
                check.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
            }
            check.initVerify(pub);
            check.update(challenge);
            if (check.verify(signed)) {
                return;
            }
        } catch (GeneralSecurityException e) {
            // fall through to the mismatch message
        }
        throw new TlsException(p + "key-file '" + s.keyFile() + "' does not match the certificate in " + p + "cert-file '" + s.certFile()
                + "' (its first certificate, " + leaf.getSubjectX500Principal().getName() + "): the key is for another certificate. "
                + "Compare: openssl x509 -noout -pubkey -in cert.pem | openssl md5; openssl pkey -pubout -in key.pem | openssl md5");
    }

    private static KeyManager[] keyManagers(PrivateKey key, List<X509Certificate> chain, String p) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] pw = new char[0];
            ks.setKeyEntry("client", key, pw, chain.toArray(new Certificate[0]));
            KeyManagerFactory f = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            f.init(ks, pw);
            return f.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new TlsException(p + "client identity: cannot use the key and certificate (" + e.getMessage() + ")", e);
        }
    }

    private static TrustManager[] trustManagers(List<X509Certificate> trusted, boolean jvmDefault, String p) {
        try {
            if (trusted.isEmpty()) {
                TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                f.init((KeyStore) null);
                return f.getTrustManagers();
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            int n = 0;
            if (jvmDefault) {
                TrustManagerFactory d = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                d.init((KeyStore) null);
                for (TrustManager tm : d.getTrustManagers()) {
                    if (tm instanceof X509TrustManager x) {
                        for (X509Certificate c : x.getAcceptedIssuers()) {
                            ks.setCertificateEntry("jvm-" + n++, c);
                        }
                    }
                }
            }
            for (X509Certificate c : trusted) {
                ks.setCertificateEntry("trusted-" + n++, c);
            }
            TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            f.init(ks);
            return f.getTrustManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new TlsException(p + "trust: cannot build the set of trusted authorities (" + e.getMessage() + ")", e);
        }
    }

    /** Loads a key store; the type is detected (PKCS12, JKS) when not given. */
    static KeyStore loadStore(String path, String type, String password, String setting) {
        File f = new File(path);
        if (!f.exists()) {
            throw new TlsException(setting + " '" + path + "': file not found");
        }
        if (!Files.isReadable(Path.of(path))) {
            throw new TlsException(setting + " '" + path + "': the file cannot be read (permissions)");
        }
        char[] pw = password == null ? null : password.toCharArray();
        try {
            if (type != null) {
                KeyStore ks = KeyStore.getInstance(type.toUpperCase(java.util.Locale.ROOT));
                try (var in = Files.newInputStream(f.toPath())) {
                    ks.load(in, pw);
                }
                return ks;
            }
            return KeyStore.getInstance(f, pw);
        } catch (NoSuchFileException | FileNotFoundException e) {
            throw new TlsException(setting + " '" + path + "': file not found");
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            boolean badPassword = e.getCause() instanceof UnrecoverableKeyException || m.contains("password") || m.contains("Integrity");
            throw new TlsException(setting + " '" + path + "': " + (badPassword
                    ? "wrong password (" + setting + "-password), or the file is damaged"
                    : "not a readable key store" + (type == null ? " (tried PKCS12 and JKS)" : " of type " + type) + ": " + m), e);
        } catch (GeneralSecurityException e) {
            throw new TlsException(setting + " '" + path + "': cannot load as " + (type == null ? "a PKCS12 or JKS store" : type)
                    + " (" + e.getMessage() + ")", e);
        }
    }

    private static String pickAlias(KeyStore ks, String wanted, String p) {
        try {
            if (wanted != null) {
                if (!ks.containsAlias(wanted)) {
                    throw new TlsException(p + "key-alias '" + wanted + "' is not in the keystore (aliases: " + Collections.list(ks.aliases()) + ")");
                }
                return wanted;
            }
            List<String> keys = new ArrayList<>();
            for (String a : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(a)) {
                    keys.add(a);
                }
            }
            if (keys.isEmpty()) {
                throw new TlsException(p + "keystore holds no private key (only trusted certificates): is it a truststore?");
            }
            if (keys.size() > 1) {
                LOG.log(System.Logger.Level.WARNING, "TLS: " + p + "keystore holds " + keys.size() + " keys " + keys + "; using '"
                        + keys.get(0) + "'. Set " + p + "key-alias to choose.");
            }
            return keys.get(0);
        } catch (java.security.KeyStoreException e) {
            throw new TlsException(p + "keystore: " + e.getMessage(), e);
        }
    }

    private static List<X509Certificate> trustedCerts(KeyStore ts, String p, String path) {
        List<X509Certificate> out = new ArrayList<>();
        try {
            for (String a : Collections.list(ts.aliases())) {
                Certificate c = ts.getCertificate(a);
                if (c instanceof X509Certificate x) {
                    out.add(x);
                }
            }
        } catch (java.security.KeyStoreException e) {
            throw new TlsException(p + "truststore '" + path + "': " + e.getMessage(), e);
        }
        if (out.isEmpty()) {
            throw new TlsException(p + "truststore '" + path + "': holds no certificates, so nothing would be trusted");
        }
        return out;
    }

    /** Development only: accepts any certificate. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
