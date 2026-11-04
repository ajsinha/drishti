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
package com.ash.drishti.flight;

import java.util.List;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * What Flight SQL's catalog calls list, from the server (the BI model catalogue, as the person may see it): one catalog (the product), a
 * schema per BI model the caller may read (its id, {@code risk-summary@1}), a table per alias of the model ({@code trades}) with its
 * columns as the model describes them. A statement reads one model (DSQL, BI_LANGUAGE_REFERENCE.md): its {@code FROM} names the model's
 * aliases, so the schema of the tables is the model the statement is about.
 */
public interface FlightSqlCatalog {

    /** The one catalog's name (the product's, from its branding). */
    String catalog();

    /** The models this caller may read, by id. */
    List<String> schemas(FlightBackend.Caller caller);

    /** The tables (the model's aliases) with their columns, as this caller may see them (masked and hidden columns as the model says). */
    List<Table> tables(FlightBackend.Caller caller, String schema);

    /** The server's version (SqlInfo). */
    String version();

    /** One table of a schema. */
    record Table(String schema, String name, Schema columns) {}
}
