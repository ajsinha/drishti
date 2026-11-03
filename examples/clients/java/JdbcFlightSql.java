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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

/**
 * WHEN FLIGHT SQL SHIPS: read-only SQL over a BI model through the Arrow Flight SQL JDBC driver (docs/guides/flight/JDBC.md). The service
 * does not answer Flight SQL yet (FLIGHT_REFERENCE.md section 6); until it does, this example compiles and stops at the first call with
 * the service's refusal. Plain JDBC: only {@code java.sql} at compile time, the driver jar ({@code org.apache.arrow:flight-sql-jdbc-driver},
 * one jar, no installer) at run time.
 *
 * <pre>
 * java -cp "lib/flight-sql-jdbc-driver-19.0.0.jar" JdbcFlightSql.java
 * </pre>
 *
 * The same address and token as every client example (DRISHTI_FLIGHT_URL, DRISHTI_TOKEN or DRISHTI_TOKEN_FILE); the CA as a Java trust
 * store (DRISHTI_FLIGHT_TRUSTSTORE and DRISHTI_FLIGHT_TRUSTSTORE_PASSWORD), as the driver takes it.
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
        String sql = args.length > 0 ? args[0]
                : "SELECT DESK, SUM(MTM) AS MTM, COUNT(*) AS TRADES FROM \"trades-basic\" GROUP BY DESK ORDER BY MTM DESC";
        try (Connection c = DriverManager.getConnection("jdbc:arrow-flight-sql://" + hostPort + "/", props)) {
            c.setReadOnly(true);                                           // read only: the service refuses anything else
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
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
