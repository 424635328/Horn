import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint("1.8.0")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint("1.8.0")
    }
    format("misc") {
        target("**/*.md", "**/*.yml", "**/*.yaml", "**/*.xml", "**/*.toml", "**/*.pro")
        targetExclude("**/build/**", "**/.gradle/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        source.setFrom(
            files(
                "src/main/java",
                "src/main/kotlin",
                "src/test/java",
                "src/test/kotlin",
                "src/androidTest/java",
                "src/androidTest/kotlin",
            ),
        )
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(true)
            sarif.required.set(false)
        }
    }
}

// Local-first quality gate: the exact surface CI's `checks` job runs. Developers (and the
// pre-push hook) run this single command so GitHub CI stays a lightweight re-verification.
tasks.register("checkAll") {
    group = "verification"
    description =
        "Runs every quality gate: spotlessCheck, detekt, lintDebug, testDebugUnitTest, verifyRoborazziDebug."
}

gradle.projectsEvaluated {
    val gateNames = setOf("spotlessCheck", "detekt", "lintDebug", "testDebugUnitTest", "verifyRoborazziDebug")
    val gates = allprojects.flatMap { project -> project.tasks.matching { it.name in gateNames } }
    tasks.named("checkAll").configure { dependsOn(gates) }
}
