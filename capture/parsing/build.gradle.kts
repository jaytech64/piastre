plugins {
    id("depenses.jvm.library")
    alias(libs.plugins.kotlin.serialization)

    // The captured corpus is the evidence every layer above is tested against, so
    // it is published as a test fixture rather than trapped in this module's own
    // test source set.
    `java-test-fixtures`
}

dependencies {
    // Domain types only. No Android, no framework, nothing at runtime beyond
    // :core:model — which is what keeps the parser suite a sub-second loop.
    api(project(":core:model"))

    // Solely to read the captured fixture corpus.
    testFixturesImplementation(libs.kotlinx.serialization.json)
}
