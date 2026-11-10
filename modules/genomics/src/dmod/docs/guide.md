# genomics: the guide

**What it does.** The function `GENOMICS` answers with a greeting for the name it is given, on the page
`/m/genomics/hello/{name}`. The greeting comes from the bean `Greeting` (`src/main/java`), which the module's Spring
configuration `ModuleConfig` declares.

**Its data.** The module provides the kind `genomics-greeting`; its contract is in
`packs/genomics-pack/contracts/`. It says what the data looks like, never where it lives: the site binds it.

**How it is built.** `mvn verify` compiles the code, runs its tests, packs `target/dmod/genomics-0.1.0.dmod` and runs
the conformance checks C1 to C15. See the [tutorial](tutorial.md).
