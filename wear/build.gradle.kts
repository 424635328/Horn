plugins {
    id("autohorn.android.application")
    id("autohorn.android.wear")
    id("autohorn.android.compose")
}

android {
    namespace = "com.industrial.autohorn.wear"

    defaultConfig {
        applicationId = "com.industrial.autohorn"
        versionCode = 3
        versionName = "3.0.0"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(project(":core:data"))
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
}
