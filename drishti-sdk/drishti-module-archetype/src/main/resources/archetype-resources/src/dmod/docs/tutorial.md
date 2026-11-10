# ${artifactId}: the tutorial

**1. Build.** In the project folder run `mvn verify`. The last lines say that all fifteen conformance checks passed and name
`target/dmod/${artifactId}-${version}.dmod`.

**2. Change the greeting.** Edit `Greeting.java`, run `mvn verify` again: the test `GreetingTest` checks the new words.

**3. Install.** Copy the package into the kernel's modules folder (`drishti.modules.path`), as
`${artifactId}/${version}/` unpacked with the `.dmod` beside it, and restart the kernel; Admin, Modules lists it.

Back to the [guide](guide.md).
