package com.industrial.autohorn.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture guardrails. These tests fail the build if a future change bypasses the module
 * boundaries, moves a ViewModel out of the presentation package, or introduces a mutable global
 * in the real-time audio module.
 */
class ArchitectureTest {
    @Test
    fun featureAndDesignSystemNeverImportAndroidMediaDirectly() {
        val featureImports = Konsist.scopeFromModule("feature/horn").imports
        val designImports = Konsist.scopeFromModule("core/designsystem").imports

        assertTrue("Konsist did not inspect feature:horn", featureImports.isNotEmpty())
        assertTrue("Konsist did not inspect core:designsystem", designImports.isNotEmpty())
        assertTrue(
            "feature:horn must not own Android media playback",
            featureImports.none { it.name.startsWith("android.media.") },
        )
        assertTrue(
            "core:designsystem must not own Android media playback",
            designImports.none { it.name.startsWith("android.media.") },
        )
    }

    @Test
    fun coreAudioNeverDependsOnCompose() {
        val imports = Konsist.scopeFromModule("core/audio").imports
        assertTrue("Konsist did not inspect core:audio", imports.isNotEmpty())
        assertTrue(
            "core:audio must remain UI-framework independent",
            imports.none { it.name.startsWith("androidx.compose.") },
        )
    }

    @Test
    fun everyViewModelLivesInPresentationPackage() {
        val viewModels = Konsist.scopeFromProject().classes().filter { it.name.endsWith("ViewModel") }

        assertTrue("Expected at least one ViewModel", viewModels.isNotEmpty())
        viewModels.forEach { viewModel ->
            assertTrue(
                "${viewModel.name} must live in a presentation package",
                viewModel.resideInPackage("..presentation.."),
            )
            assertTrue(
                "${viewModel.name} has an unexpected path: ${viewModel.path}",
                viewModel.path.replace('\\', '/').contains("/presentation/"),
            )
        }
    }

    @Test
    fun realtimeAudioModuleHasNoTopLevelMutableState() {
        val properties = Konsist.scopeFromModule("core/audio").properties()
        assertTrue("Konsist did not inspect core:audio properties", properties.isNotEmpty())
        val mutableTopLevel = properties.filter { it.isTopLevel && it.isVar }

        assertTrue(
            "core:audio must not expose mutable global state: " +
                mutableTopLevel.joinToString { it.name },
            mutableTopLevel.isEmpty(),
        )
    }
}
