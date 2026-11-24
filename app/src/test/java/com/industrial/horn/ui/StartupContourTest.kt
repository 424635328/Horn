package com.industrial.horn.ui

import androidx.compose.ui.geometry.Size
import com.industrial.horn.ui.theme.ContourTemplate
import com.industrial.horn.ui.theme.StartupTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 参考图素材管线契约（docs/spec/03 §11.1.1）。
 *
 * 聚合幕只有在「碎片是尺寸与素材的确定性函数」时才可信：scrub、replay、定格帧都假设
 * 同输入同输出。这里同时锁住三件事：素材真的打进了包（不是构建期产物漏打包）、
 * 解析对坏数据必须报错（而不是画出一团乱线）、到达次序仍按参考图的目标 X。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupContourTest {
    @Test
    fun heroContoursShipInsideTheApk() {
        val hero = hero()
        assertTrue("参考图线稿未打进包（assets/startup/hero_contours.bin）", hero.hasContours)
        assertTrue("轮廓过少，参考图未真正参与: ${hero.polylines.size}", hero.polylines.size >= 100)
        assertEquals("素材应记录参考图宽度", 658, hero.imageWidth)
        assertEquals("素材应记录参考图高度", 877, hero.imageHeight)
        assertTrue("宽高比应为参考图的 658:877", abs(hero.aspect - 658f / 877f) < 0.01f)
    }

    @Test
    fun parseRejectsCorruptAssets() {
        assertThrows(IllegalArgumentException::class.java) { StartupHero.parse(ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) {
            StartupHero.parse(ByteArray(32).also { it[0] = 'X'.code.toByte() })
        }
    }

    @Test
    fun templateIsDeterministicForTheSameSize() {
        val hero = hero()
        val first = template(hero)
        val second = template(hero)
        assertEquals("列数必须稳定", first.columns.size, second.columns.size)
        assertEquals("碎片数必须稳定", first.fragmentCount, second.fragmentCount)
        val a = first.columns.flatMap { it.fragments }
        val b = second.columns.flatMap { it.fragments }
        a.zip(b).forEach { (left, right) ->
            assertEquals("home x 必须稳定", left.homeX, right.homeX, 0.0001f)
            assertEquals("scatter x 必须稳定", left.scatterX, right.scatterX, 0.0001f)
            assertEquals("scatter y 必须稳定", left.scatterY, right.scatterY, 0.0001f)
            assertEquals("旋转角必须稳定", left.angle, right.angle, 0.0001f)
        }
    }

    @Test
    fun fragmentsCoverTheWholeReferenceImage() {
        val template = template(hero())
        assertTrue("碎片数应在合理区间: ${template.fragmentCount}", template.fragmentCount in 200..2_000)
        assertTrue("应出现多列: ${template.columns.size}", template.columns.size >= 8)
        assertTrue("参考图是竖构图（658×877）", template.subjectHeight > template.subjectWidth)
        assertFalse(
            "线稿路径必须可绘制",
            template.columns
                .first()
                .path.isEmpty,
        )
        assertTrue("工具必须报告碎片数 > 0", template.fragmentCount > 0)
    }

    @Test
    fun arrivalFollowsTheTargetXOrdering() {
        val template = template(hero())
        val first = template.columns.first()
        val last = template.columns.last()

        assertEquals(0f, StartupTimeline.fragmentArrival(StartupTimeline.DRAWING_START_MS, first.normalisedX), 0.0001f)
        assertEquals(1f, StartupTimeline.fragmentArrival(StartupTimeline.DRAWING_END_MS, last.normalisedX), 0.0001f)

        val mid = 5_600f
        assertTrue(
            "左侧先落定，右侧发梢最后落定",
            StartupTimeline.fragmentArrival(mid, first.normalisedX) >
                StartupTimeline.fragmentArrival(mid, last.normalisedX),
        )
        assertEquals(1f, StartupTimeline.fragmentArrival(mid, first.normalisedX), 0.0001f)
        assertEquals(0f, StartupTimeline.fragmentArrival(mid, last.normalisedX), 0.0001f)
    }

    @Test
    fun fragmentsSitInsideTheirOwnColumns() {
        val template = template(hero())
        val columnCount = 24
        template.columns.forEach { column ->
            val bucketOfColumn = (column.normalisedX * columnCount).toInt()
            for (fragment in column.fragments) {
                assertEquals(
                    "碎片必须落在它所属的列里（已归位列一次 draw 的前提）",
                    bucketOfColumn,
                    (fragment.normalisedX * columnCount).toInt(),
                )
            }
        }
    }

    private fun hero(): StartupHero = StartupHero.load(RuntimeEnvironment.getApplication())

    private fun template(hero: StartupHero): ContourTemplate =
        ContourTemplate.of(
            size = Size(1_080f, 2_400f),
            density = 2f,
            polylines = hero.polylines,
            aspect = hero.aspect,
        )
}
