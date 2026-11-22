plugins {
    id("horn.android.library")
}

android {
    namespace = "com.industrial.horn.benchmark"

    // Dedicated benchmark build type: gives the module a real `assembleBenchmark` artifact
    // (used by the CI gate and local acceptance commands) while unit tests keep running
    // against the debug variant.
    buildTypes {
        create("benchmark") {
            matchingFallbacks += listOf("debug")
        }
    }
}

dependencies {
    testImplementation(project(":core:audio"))
    testImplementation(libs.junit)
}
