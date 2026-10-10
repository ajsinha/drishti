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
# Writing a source plugin (moved)

This guide is now [CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md): the `SourcePlugin` contract is under
[The SourcePlugin SPI](CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi), and every connector's settings are in its own
document (`<NAME>_CONNECTOR.md`, listed under [The shipped connectors](CONNECTOR_DEVELOPER_GUIDE.md#the-shipped-connectors)).

## Describing your settings, and where connectors come from

Named connectors now come from **files**: one YAML file per connector in `config/connectors/<name>.yaml`, edited in **Admin → Connectors**, in an editor or with
`drishti.py connector` ([CONNECTOR_FILES.md](CONNECTOR_FILES.md)). A pack only names the connectors it reads through and may suggest a template; the old
`drishti.sources.connectors` definitions still work but are deprecated. A plugin needs no change to be used from a file.

A plugin may describe the settings it reads by overriding one optional SPI method,
`default List<SettingSpec> settingSpecs()`, returning `com.ash.drishti.api.SettingSpec(name, type, required, defaultValue, description, secret, group)` records
(a trailing `.*` in `name` covers a subtree, such as `layout.*`). The form of Admin → Connectors is generated from them, and the validator uses them to mark what is
required, to warn about a setting nobody reads, and to refuse a literal value for a `secret` setting. The shipped plugins are described in
`drishti-engine/src/main/resources/drishti/plugin-settings.yaml`; a plugin that declares its own wins. The record and an example are in
[CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md#settingspecs-describing-your-settings).
