plugins {
    id("horn.android.library")
}

android {
    namespace = "com.industrial.horn.data"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:audio"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
