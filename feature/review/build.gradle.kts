plugins {
    id("depenses.android.feature")
}

android {
    namespace = "io.github.jaytech64.depenses.feature.review"
}

dependencies {
    // The view model is exercised against a real database rather than a fake
    // repository: a ruling is only correct if it survives the pipeline re-parsing
    // the same notification, which no fake can tell you.
    testImplementation(project(":core:testing"))
    testImplementation(project(":capture:parsing"))
    testImplementation(libs.robolectric)
}
