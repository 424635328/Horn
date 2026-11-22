plugins {
    id("horn.android.library")
}

android {
    namespace = "com.industrial.horn.haptic"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:audio"))

    testImplementation(libs.junit)
}
