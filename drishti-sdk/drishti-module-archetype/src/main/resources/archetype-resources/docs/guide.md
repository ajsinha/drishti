# ${artifactId}: the guide

**What it does.** The function `${artifactId.toUpperCase()}` answers with a greeting for the name it is given, on the page
`/m/${artifactId}/hello/{name}`. The greeting comes from the bean `Greeting` (`java/${artifactId}/src/main/java`), which the module's Spring
configuration `ModuleConfig` declares.

**Its data.** The module provides the kind `${artifactId}-greeting`; its contract is in
`packs/${artifactId}-pack/contracts/`. It says what the data looks like, never where it lives: the site binds it.

**How it is built.** `mvn verify` compiles the code, runs its tests, packs `java/${artifactId}/target/dmod/${artifactId}-${version}.dmod` and runs
the conformance checks C1 to C14. See the [tutorial](tutorial.md).
