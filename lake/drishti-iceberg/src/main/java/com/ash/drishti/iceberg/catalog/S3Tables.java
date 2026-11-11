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

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.SortOrder;
import com.ash.drishti.iceberg.types.Schema;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS S3 Tables through its Iceberg REST endpoint ({@code catalog: s3tables}): the connector names the table bucket by its ARN
 * ({@code table-bucket-arn: arn:aws:s3tables:<region>:<account>:bucket/<name>}) and this class fills in the REST settings that follow
 * from it, so a connector file holds one line instead of six:
 *
 * <ul>
 *   <li>{@code uri}: {@code https://s3tables.<region>.amazonaws.com/iceberg} ({@code amazonaws.com.cn} in the China partition); a
 *       given {@code uri} wins (a VPC or FIPS endpoint);</li>
 *   <li>{@code warehouse}: the ARN; {@code prefix}: the ARN URL-encoded, the path prefix S3 Tables' {@code /v1/config} names;</li>
 *   <li>{@code catalog.rest.sigv4-enabled: true}, {@code catalog.rest.signing-name: s3tables}, {@code catalog.rest.signing-region}: the
 *       ARN's region; credentials from {@code catalog.rest.access-key-id} or the AWS default chain (an ECS task role);</li>
 *   <li>{@code s3.region}: the ARN's region (the table data is read from S3 with the same credentials);
 *       {@code vended-credentials: false} (S3 Tables does not vend them; the principal's own IAM reads the data);</li>
 *   <li>{@code namespace}: the domain as an S3 Tables name ({@link #name}).</li>
 * </ul>
 *
 * <p>S3 Tables names namespaces and tables with lower-case letters, digits and underscores only, so the catalog it opens reads and writes
 * the kind {@code pnl-vector} as the table {@code pnl_vector} ({@link #name}); a name that cannot be made valid is refused with the rule.
 * Everything else is the plain REST catalog ({@link RestCatalog}). Thread-safe (no state).
 */
public final class S3Tables {

    /** The {@code catalog} value that selects S3 Tables. */
    public static final String CATALOG = "s3tables";

    /** The SigV4 signing name of S3 Tables' Iceberg REST endpoint. */
    public static final String SIGNING_NAME = "s3tables";

    /** The setting naming the table bucket. */
    public static final String ARN_SETTING = "table-bucket-arn";

    private static final Pattern ARN = Pattern.compile(
            "arn:(aws|aws-cn|aws-us-gov):s3tables:([a-z]{2}(?:-[a-z]+)+-\\d):(\\d{12}):bucket/([a-z0-9][a-z0-9-]{1,61}[a-z0-9])");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9_]{0,253}[a-z0-9])?");

    private S3Tables() {}

    /** A table bucket, from its ARN. */
    public record Bucket(String partition, String region, String account, String name, String arn) {

        /** S3 Tables' Iceberg REST endpoint in the bucket's region. */
        public String endpoint() {
            return "https://s3tables." + region + "." + (partition.equals("aws-cn") ? "amazonaws.com.cn" : "amazonaws.com") + "/iceberg";
        }

        /** The REST path prefix: the ARN, URL-encoded as one path segment. */
        public String prefix() {
            return java.net.URLEncoder.encode(arn, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    /** Whether the settings ask for S3 Tables ({@code catalog: s3tables}). */
    public static boolean named(Map<String, String> settings) {
        return CATALOG.equalsIgnoreCase(settings.getOrDefault("catalog", "").trim());
    }

    /** The table bucket's ARN from the settings ({@code table-bucket-arn}, else an ARN given as {@code warehouse}); blank when none. */
    public static String arnOf(Map<String, String> settings) {
        String arn = settings.getOrDefault(ARN_SETTING, "").trim();
        if (arn.isEmpty() && settings.getOrDefault("warehouse", "").trim().startsWith("arn:")) {
            arn = settings.get("warehouse").trim();
        }
        return arn;
    }

    /** The bucket an ARN names; refused, with the form expected, when it is not a table bucket ARN. */
    public static Bucket bucket(String arn) {
        Matcher m = ARN.matcher(arn == null ? "" : arn.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("table-bucket-arn is not an S3 Tables table bucket ARN: '" + arn
                    + "' (expected arn:aws:s3tables:<region>:<12-digit account>:bucket/<name>, the name 3 to 63 lower-case letters, digits and hyphens)");
        }
        return new Bucket(m.group(1), m.group(2), m.group(3), m.group(4), m.group());
    }

    /**
     * A namespace or table name as S3 Tables accepts it: lower case, hyphens and dots to underscores ({@code pnl-vector} to
     * {@code pnl_vector}); refused when the result still breaks the rule (1 to 255 lower-case letters, digits and underscores, starting
     * and ending with a letter or digit).
     */
    public static String name(String given) {
        String n = given == null ? "" : given.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("'" + given + "' cannot be an S3 Tables namespace or table name: 1 to 255 lower-case letters, digits "
                    + "and underscores, starting and ending with a letter or digit");
        }
        return n;
    }

    /**
     * The settings with the REST catalog settings S3 Tables needs filled in from the ARN (see the class comment). {@code catalog} stays
     * {@code s3tables}; a setting already given is kept, except {@code catalog.rest.sigv4-enabled}, which is always true. Applying it twice
     * gives the same map.
     */
    public static Map<String, String> expand(Map<String, String> settings) {
        String arn = arnOf(settings);
        if (arn.isEmpty()) {
            throw new IllegalArgumentException("catalog: s3tables needs table-bucket-arn (arn:aws:s3tables:<region>:<account>:bucket/<name>)");
        }
        Bucket b = bucket(arn);
        String warehouse = settings.getOrDefault("warehouse", "").trim();
        if (!warehouse.isEmpty() && !warehouse.equals(b.arn())) {
            throw new IllegalArgumentException("warehouse (" + warehouse + ") and table-bucket-arn (" + b.arn() + ") name different table buckets");
        }
        Map<String, String> out = new LinkedHashMap<>(settings);
        out.put(ARN_SETTING, b.arn());
        out.put("warehouse", b.arn());
        fill(out, "uri", b.endpoint());
        fill(out, "prefix", b.prefix());
        out.put("catalog.rest.sigv4-enabled", "true");
        fill(out, "catalog.rest.signing-name", SIGNING_NAME);
        fill(out, "catalog.rest.signing-region", b.region());
        fill(out, "s3.region", b.region());
        fill(out, "vended-credentials", "false");
        String domain = out.getOrDefault("domain", "").trim();
        if (!domain.isEmpty()) {
            fill(out, "namespace", name(domain));
        }
        return out;
    }

    private static void fill(Map<String, String> m, String key, String value) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            m.put(key, value);
        }
    }

    /** The namespace of a table bucket as a catalog: the REST catalog the settings describe, with S3 Tables' names. */
    static Catalog open(Map<String, String> settings, String domain, FileAccess io) {
        Map<String, String> s = expand(settings);
        String ns = name(s.getOrDefault("namespace", "").isBlank() ? domain : s.get("namespace"));
        s.put("namespace", ns);
        return new Named(RestCatalog.open(s, ns, io));
    }

    /** The REST catalog under an S3 Tables catalog, or null when {@code c} is not one (for its proxy settings). */
    public static RestCatalog restOf(Catalog c) {
        return c instanceof Named n ? n.rest() : null;
    }

    /** A REST catalog whose table names are made S3 Tables names on the way in. */
    private record Named(RestCatalog rest) implements Catalog {

        @Override
        public Optional<Table> load(String name) throws IOException {
            return rest.load(S3Tables.name(name));
        }

        @Override
        public String missing(String name) {
            String n = S3Tables.name(name);
            return n.equals(name) ? rest.missing(n) : rest.missing(n) + " (the kind " + name + " is the S3 Tables table " + n + ")";
        }

        @Override
        public List<String> tables() throws IOException {
            return rest.tables();
        }

        @Override
        public Table create(String name, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) throws IOException {
            return rest.create(S3Tables.name(name), schema, spec, order, properties);
        }

        @Override
        public boolean reachable() {
            return rest.reachable();
        }

        @Override
        public String describe() {
            return "S3 Tables " + rest.describe();
        }

        @Override
        public void close() {
            rest.close();
        }
    }
}
