<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
---
generator: tools/packgen/gleif/make.py
sources:
- name: GLEIF golden copy, level 2 (relationship records, reporting exceptions)
  licence: CC0 1.0 (attribution appreciated)
  internal-only: false
  used-for: who consolidates whom (direct and ultimate parents), relationship periods and status, why a parent is not reported
- name: GLEIF LEI API (lei-records, entity-legal-forms, countries, lei-issuers)
  licence: CC0 1.0 (attribution appreciated)
  internal-only: false
  used-for: each entity's level 1 record, legal form names, country names, LOU names
- name: GLEIF mapping files (BIC, ISIN, MIC, OpenCorporates to LEI)
  licence: CC0 1.0 (the BIC file with SWIFT, the ISIN file with ANNA)
  internal-only: false
  used-for: the identifiers of the entities
- name: GLEIF golden copy, level 1 bulk file
  licence: CC0 1.0
  internal-only: false
  used-for: full-country pulls into the data folder only (golden-lei source; never committed, for its size)
fields:
  lei-entity:
    LEI: Legal Entity Identifier (ISO 17442, 20 characters)
    NAME: Legal name
    OTHER_NAMES: Other and previous names, with their type
    LEGAL_FORM: Legal form (the ELF code's name)
    CATEGORY: Entity category when not general (fund, branch, sole proprietor)
    JURISDICTION: Legal jurisdiction when it differs from the country
    LEI_COUNTRY: Country of the legal address (ISO 3166 alpha-2), a link to its lei-country
    COUNTRY_NAME: The country's name
    LEGAL_ADDRESS: Legal address
    LEGAL_CITY: City of the legal address
    HQ_ADDRESS: Headquarters address, when it differs from the legal address
    HQ_COUNTRY: Headquarters country, when it differs
    REGISTERED_AS: Number at the business register
    ENTITY_STATUS: Entity status (ACTIVE, INACTIVE)
    CREATED: Date the entity was created
    SUCCESSOR: Successor entity's LEI
    REGISTRATION_STATUS: LEI registration status (ISSUED, LAPSED, RETIRED, ...)
    INITIAL_REGISTRATION: First registration of the LEI
    LAST_UPDATE: Last update of the record
    NEXT_RENEWAL: Next renewal date
    RENEWAL: What the renewal says, when late or due within 30 days
    RENEWAL_LATE: True when the registration lapsed or its renewal date has passed
    MANAGING_LOU: The LEI issuer (LOU) managing the record
    BICS: Its BICs (GLEIF mapping file)
    BIC_COUNT: Number of BICs
    ISIN_COUNT: Number of ISINs mapped to it (GLEIF mapping file)
    MIC: Market identifier codes it operates
    OPENCORPORATES: OpenCorporates company id
    DIRECT_PARENT: Direct accounting consolidation parent's LEI
    ULTIMATE_PARENT: Ultimate parent's LEI, when it differs from the direct parent
    LEI_GROUP: The ownership group (its ultimate parent's LEI)
    PARENTS: The parents chain, direct parent first
    PARENT_EXCEPTIONS: Reporting exceptions (why no parent is reported)
    CHILDREN: Entities it directly consolidates, in the pack
    CHILD_COUNT: Number of children in the pack
    DIRECT_CHILDREN_GLEIF: Number of direct children in GLEIF
    ULTIMATE_CHILDREN_GLEIF: Number of entities naming it ultimate parent in GLEIF
    MAPPING_IDS: Its identifier documents (lei-mapping)
    SEGMENT: Why it is a seed (US equities, G-SIB, bank, insurer, asset manager, market infrastructure)
    US_COMPANY: The us-equities company's ticker, a link to it
    US_COMPANY_NAME: The us-equities company's name
  lei-group:
    GROUP_ID: The ultimate parent's LEI (ALL for the browse document)
    ENTITY_LEI: The ultimate parent's entity
    MEMBERS: Members the pack keeps
    MEMBERS_IN_GLEIF: Members GLEIF has
    CAPPED: True when GLEIF has more members than the pack keeps
    CAP_NOTE: The cap in words
    LEVELS: Depth of the tree
    TREE: The tree from the ultimate parent (CHILDREN nested)
    ROWS: Every member with its country and level
    SEEDS: The seeds that brought the group in
    BY_COUNTRY: Members by country
    BY_LEGAL_FORM: Members by legal form
    PROVENANCE: Source, retrieval date, licence and URL
  lei-relationship:
    RELATIONSHIP_ID: LEIREL-<child LEI>-DIRECT or -ULTIMATE
    LEVEL: Direct or Ultimate
    CHILD_ENTITY: The child's LEI
    PARENT_ENTITY: The parent's LEI
    RELATIONSHIP_STATUS: ACTIVE or INACTIVE
    START: Start of the relationship
    END: End of the relationship
    PERIODS: Accounting and document filing periods
    ACCOUNTING_STANDARD: Accounting standard of the consolidation
    REGISTRATION_STATUS: The relationship record's registration status
  lei-country:
    COUNTRY: ISO 3166 alpha-2 code (ALL for the directory)
    ENTITIES: Entities with a legal address there
    ROWS: The entities (the first 400 by name)
    GROUP_ROWS: Groups present, with their entities there
    BY_LEGAL_FORM: Entities by legal form
    BY_CITY: Entities by city
    PROVENANCE: Source, retrieval date, licence and URL
  lei-mapping:
    IDENTIFIER: The BIC, ISIN or MIC
    TYPE: BIC, ISIN or MIC
    ENTITY_LEI: The LEI it maps to
    ENTITY_NAME: That entity's legal name
    MAPPING_FILE: The GLEIF mapping file it came from
---
## What it is

Legal Entity Identifiers and who owns whom, from GLEIF: the ownership groups of the us-equities companies with an LEI and of the
large financial institutions (the FSB's G-SIBs, other large banks, insurers, asset managers and market infrastructures), each the
ultimate parent with the entities it consolidates, at most 50 a group, the nearest levels first. Every entity carries its addresses,
registration and renewal (late renewals flagged), its parents chain, its children and its BICs and ISIN count. Off by default; with
us-equities on, a company and its LEI record link both ways.

## Data sources and licences

Everything GLEIF publishes is CC0 1.0: free of charge, no restriction; attribution is appreciated ("Source: GLEIF, LEI data").
The groups use the consolidation relationships only; fund management, sub-fund, feeder and branch relationships are left out.
