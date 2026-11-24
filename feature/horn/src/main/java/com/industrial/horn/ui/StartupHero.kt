package com.industrial.horn.ui

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 参考图的线稿素材（构建期产物，见 `tools/startup-hero/extract-contours.mjs`）。
 *
 * 格式：`'HRNC' | version u16 | reserved u16 | imageW u16 | imageH u16 | pathCount u32`
 * 之后每条路径：`pointCount u16` + `pointCount × (xNorm u16, yNorm u16)`（归一化 0..1，小端）。
 *
 * 读取只发生一次（组合期，IO 线程）。解析失败不让开屏崩：返回空折线，
 * 聚合幕退化为「只见参考图」，其余五幕照常播放。
 */
class StartupHero(
    val polylines: List<FloatArray>,
    val aspect: Float,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val hasContours: Boolean
        get() = polylines.isNotEmpty()

    companion object {
        private const val ASSET = "startup/hero_contours.bin"
        private const val MAGIC = "HRNC"
        private const val VERSION = 1

        /** 参考图 658×877；素材缺失时仍按同一构图比例排布。 */
        const val FALLBACK_ASPECT = 658f / 877f

        fun load(context: Context): StartupHero =
            runCatching {
                context.assets.open(ASSET).use { stream -> parse(stream.readBytes()) }
            }.getOrElse {
                StartupHero(emptyList(), FALLBACK_ASPECT, 0, 0)
            }

        fun parse(bytes: ByteArray): StartupHero {
            require(bytes.size >= 16) { "hero contours: truncated header" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            require(magic == MAGIC) { "hero contours: bad magic '$magic'" }
            val version = buffer.short.toInt() and 0xFFFF
            require(version == VERSION) { "hero contours: unsupported version $version" }
            buffer.short // reserved
            val imageWidth = buffer.short.toInt() and 0xFFFF
            val imageHeight = buffer.short.toInt() and 0xFFFF
            val pathCount = buffer.int
            require(pathCount in 1..100_000) { "hero contours: implausible path count $pathCount" }

            val polylines = ArrayList<FloatArray>(pathCount)
            repeat(pathCount) {
                require(buffer.remaining() >= 2) { "hero contours: truncated path header" }
                val pointCount = buffer.short.toInt() and 0xFFFF
                require(pointCount in 1..4_096) { "hero contours: implausible point count $pointCount" }
                require(buffer.remaining() >= pointCount * 4) { "hero contours: truncated path body" }
                val points = FloatArray(pointCount * 2)
                for (index in 0 until pointCount) {
                    points[index * 2] = (buffer.short.toInt() and 0xFFFF) / 65535f
                    points[index * 2 + 1] = (buffer.short.toInt() and 0xFFFF) / 65535f
                }
                polylines.add(points)
            }
            val aspect =
                if (imageWidth > 0 && imageHeight > 0) {
                    imageWidth.toFloat() / imageHeight.toFloat()
                } else {
                    FALLBACK_ASPECT
                }
            return StartupHero(polylines, aspect, imageWidth, imageHeight)
        }
    }
}
