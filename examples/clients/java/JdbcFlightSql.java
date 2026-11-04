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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

/**
 * Read-only SQL over a BI model through the Arrow Flight SQL JDBC driver (docs/guides/flight/JDBC.md; FLIGHT_GUIDE.md section 12). Plain
 * JDBC: only {@code java.sql} at compile time, the driver jar ({@code org.apache.arrow:flight-sql-jdbc-driver}, one jar, no installer)
 * at run time.
 *
 * <pre>
 * java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" JdbcFlightSql.java                       # the models and their tables
 * java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" JdbcFlightSql.java trading-risk@1 \
 *      "SELECT trades.DESK, COUNT(*) AS n FROM trades GROUP BY trades.DESK"                            # a statement over a model
 * </pre>
 *
 * Without arguments it lists what the token may read: a schema per BI model, a table per alias of the model (DatabaseMetaData). With a
 * model and a statement it runs the statement, written in DSQL (the model's aliases in FROM, MEASURE('…') for a measure), and prints the
 * rows. The model goes in the connection's {@code model} property (the driver sends it as a header). The same address and token as every
 * client example (DRISHTI_FLIGHT_URL, DRISHTI_TOKEN or DRISHTI_TOKEN_FILE); the CA as a Java trust store (DRISHTI_FLIGHT_TRUSTSTORE and
 * DRISHTI_FLIGHT_TRUSTSTORE_PASSWORD), as the driver takes it.
 */
public final class JdbcFlightSql {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DRISHTI_FLIGHT_URL");                 // grpc+tls://host:18815
        if (url == null || !url.startsWith("grpc+tls://")) {
            System.err.println("Set DRISHTI_FLIGHT_URL to grpc+tls://<a node's real address>:18815");
            System.exit(2);
        }
        String hostPort = url.substring("grpc+tls://".length());
        Properties props = new Properties();
        props.setProperty("useEncryption", "true");
        props.setProperty("token", token());
        String trustStore = System.getenv("DRISHTI_FLIGHT_TRUSTSTORE");
        if (trustStore != null) {
            props.setProperty("trustStore", trustStore);
            props.setProperty("trustStorePassword", System.getenv().getOrDefault("DRISHTI_FLIGHT_TRUSTSTORE_PASSWORD", ""));
        }
        if (args.length >= 1) {
            props.setProperty("model", args[0]);                          // sent as the header model: the model the statements read
        }
        try (Connection c = DriverManager.getConnection("jdbc:arrow-flight-sql://" + hostPort + "/", props)) {
            c.setReadOnly(true);                                           // read only: the service refuses anything else
            if (args.length < 2) {
                list(c.getMetaData());
                return;
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(args[1])) {
                print(rs);
            }
        }
    }

    /** The models the token may read (schemas) and each model's aliases (tables) with their columns. */
    private static void list(DatabaseMetaData md) throws Exception {
        try (ResultSet schemas = md.getSchemas()) {
            while (schemas.next()) {
                String model = schemas.getString("TABLE_SCHEM");
                System.out.println("model " + model);
                try (ResultSet tables = md.getTables(null, model, "%", null)) {
                    while (tables.next()) {
                        StringBuilder cols = new StringBuilder();
                        try (ResultSet cs = md.getColumns(null, model, tables.getString("TABLE_NAME"), "%")) {
                            while (cs.next()) {
                                cols.append(cols.length() == 0 ? "" : ", ").append(cs.getString("COLUMN_NAME"));
                            }
                        }
                        System.out.println("  table " + tables.getString("TABLE_NAME") + " (" + cols + ")");
                    }
                }
            }
        }
    }

    private static void print(ResultSet rs) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            System.out.print((i > 1 ? "\t" : "") + md.getColumnLabel(i));
        }
        System.out.println();
        while (rs.next()) {
            for (int i = 1; i <= md.getColumnCount(); i++) {
                System.out.print((i > 1 ? "\t" : "") + rs.getString(i));
            }
            System.out.println();
        }
    }

    private static String token() throws Exception {
        String value = System.getenv("DRISHTI_TOKEN");
        if (value != null && !value.isBlank()) {
            return value.strip();
        }
        String file = System.getenv("DRISHTI_TOKEN_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file.strip().replaceFirst("^~", System.getProperty("user.home"))), StandardCharsets.UTF_8)
                    .lines().findFirst().orElse("").strip();
        }
        System.err.println("Set DRISHTI_TOKEN (or DRISHTI_TOKEN_FILE) to an API token with the scope data:read");
        System.exit(2);
        return null;
    }
}
