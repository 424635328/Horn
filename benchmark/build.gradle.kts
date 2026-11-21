plugins {
    id("autohorn.android.library")
}

android {
    namespace = "com.industrial.autohorn.benchmark"
}

dependencies {
    testImplementation(project(":core:audio"))
    testImplementation(libs.junit)
}
