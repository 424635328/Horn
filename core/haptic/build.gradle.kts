plugins {
    id("autohorn.android.library")
}

android {
    namespace = "com.industrial.autohorn.haptic"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:audio"))

    testImplementation(libs.junit)
}
