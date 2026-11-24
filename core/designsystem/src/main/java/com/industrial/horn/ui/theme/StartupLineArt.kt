package com.industrial.horn.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

// 落定强调色的存活时间（ms）：刚归位的列闪一下，随后融回线稿色。
private const val SETTLE_FLASH_MS = 110f

/**
 * 参考图 → 线稿 → 碎片 → 归位。这条管线是 skill 的招牌效果在本项目的落地：
 *
 * ```
 * startup_hero.png（参考图，658×877）
 *   → tools/startup-hero/extract-contours.mjs 离线跑一次
 *     （PNG 解码 → 灰度 → Sobel → 非极大值抑制 → 边缘行走 → 归一化 polylines）
 *   → assets/startup/hero_contours.bin（1000 条折线、24k 点、96 KB）
 *   → 运行时按尺寸映射到画面、等弧长切碎片、确定性散射、按目标 X 归位
 *   → 归位完成后再交叉淡化回参考图本身（同几何对齐）
 * ```
 *
 * 与 skill 的分工一致：重活（边缘检测）在构建期完成，运行期只做插值与增量绘制；
 * 散射位用 sin-hash 而非随机数，保证 scrub / replay / 定格可逐位复现。
 */
object StartupHeroLayout {
    /** 参考图占屏宽比例（两侧留出 HUD 呼吸位）。 */
    const val WIDTH_FRACTION = 0.74f

    /** 参考图高度上限占屏高比例（给字标、字幕、同步条留位）。 */
    const val HEIGHT_FRACTION = 0.44f

    /** 构图光学中心：与表盘、交棒轴线同一处。 */
    const val CENTER_Y_FRACTION = 0.42f

    /** 参考图在给定画布下的显示宽度（与宽高比相关）。 */
    fun subjectWidth(
        width: Float,
        height: Float,
        aspect: Float,
    ): Float = minOf(width * WIDTH_FRACTION, height * HEIGHT_FRACTION * aspect)

    fun subjectHeight(
        width: Float,
        height: Float,
        aspect: Float,
    ): Float = subjectWidth(width, height, aspect) / aspect
}

/** 一片飞行中的线稿碎片：相对质心的点 + 确定性散射位 + 旋转 + 漂移相位。 */
class ContourFragment(
    val localPoints: FloatArray,
    val homeX: Float,
    val homeY: Float,
    val scatterX: Float,
    val scatterY: Float,
    val angle: Float,
    val normalisedX: Float,
    val driftPhase: Float,
)

/** 已归位的纵向列：一次 `drawPath` 画完整列。 */
class ContourColumn(
    val normalisedX: Float,
    val path: Path,
    val fragments: List<ContourFragment>,
)

/**
 * 一份按画布尺寸预计算的线稿素材。构建一次（`drawWithCache`），逐帧只重写 [flyingPath]。
 */
class ContourTemplate(
    val columns: List<ContourColumn>,
    val fragmentCount: Int,
    val flyingPath: Path,
    val subjectCenter: Offset,
    val subjectWidth: Float,
    val subjectHeight: Float,
    val driftAmplitude: Float,
    val lineStyle: Stroke,
) {
    companion object {
        private const val COLUMN_COUNT = 24
        private const val TARGET_FRAGMENTS = 900

        /**
         * @param polylines 归一化到 0..1（相对参考图）的折线，来自 hero_contours.bin
         */
        fun of(
            size: Size,
            density: Float,
            polylines: List<FloatArray>,
            aspect: Float,
        ): ContourTemplate {
            val center = Offset(size.width / 2f, size.height * StartupHeroLayout.CENTER_Y_FRACTION)
            val subjectWidth = StartupHeroLayout.subjectWidth(size.width, size.height, aspect)
            val subjectHeight = StartupHeroLayout.subjectHeight(size.width, size.height, aspect)
            val left = center.x - subjectWidth / 2f
            val top = center.y - subjectHeight / 2f

            val strokes = polylines.map { polyline -> mapToBox(polyline, left, top, subjectWidth, subjectHeight) }
            val totalLength = strokes.sumOf { arcLength(it).toDouble() }.toFloat()
            val span = if (strokes.isEmpty()) 12f else maxOf(3f * density, totalLength / TARGET_FRAGMENTS)

            val fragments = ArrayList<ContourFragment>(TARGET_FRAGMENTS)
            for (stroke in strokes) {
                for (piece in splitByArcLength(stroke, span)) {
                    if (piece.size >= 4) {
                        fragments.add(fragmentOf(piece, fragments.size, left, subjectWidth))
                    }
                }
            }

            val buckets = Array(COLUMN_COUNT) { ArrayList<ContourFragment>() }
            for (fragment in fragments) {
                val column = (fragment.normalisedX * COLUMN_COUNT).toInt().coerceIn(0, COLUMN_COUNT - 1)
                buckets[column].add(fragment)
            }
            val columns = ArrayList<ContourColumn>(COLUMN_COUNT)
            for (index in 0 until COLUMN_COUNT) {
                val bucket = buckets[index]
                if (bucket.isEmpty()) continue
                val columnPath = Path()
                for (fragment in bucket) appendHome(columnPath, fragment)
                columns.add(ContourColumn((index + 0.5f) / COLUMN_COUNT, columnPath, bucket))
            }
            return ContourTemplate(
                columns = columns,
                fragmentCount = fragments.size,
                flyingPath = Path(),
                subjectCenter = center,
                subjectWidth = subjectWidth,
                subjectHeight = subjectHeight,
                driftAmplitude = 7f * density,
                lineStyle = Stroke(width = 1.35f * density, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        private fun mapToBox(
            polyline: FloatArray,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
        ): FloatArray {
            val mapped = FloatArray(polyline.size)
            var index = 0
            while (index < polyline.size) {
                mapped[index] = left + polyline[index] * width
                mapped[index + 1] = top + polyline[index + 1] * height
                index += 2
            }
            return mapped
        }

        private fun fragmentOf(
            piece: FloatArray,
            ordinal: Int,
            subjectLeft: Float,
            subjectWidth: Float,
        ): ContourFragment {
            var sumX = 0f
            var sumY = 0f
            val count = piece.size / 2
            for (index in 0 until count) {
                sumX += piece[index * 2]
                sumY += piece[index * 2 + 1]
            }
            val homeX = sumX / count
            val homeY = sumY / count
            val local = FloatArray(piece.size)
            for (index in 0 until count) {
                local[index * 2] = piece[index * 2] - homeX
                local[index * 2 + 1] = piece[index * 2 + 1] - homeY
            }
            val seed = ordinal + 0.5f
            val scatterAngle = hash01(seed * 1.7f) * 2f * PI.toFloat()
            val scatterDistance = (0.16f + 0.55f * hash01(seed * 3.1f)) * subjectWidth
            return ContourFragment(
                localPoints = local,
                homeX = homeX,
                homeY = homeY,
                scatterX = homeX + cos(scatterAngle) * scatterDistance,
                scatterY = homeY + sin(scatterAngle) * scatterDistance * 0.62f,
                angle = (hash01(seed * 5.3f) - 0.5f) * 1.8f,
                // 归位次序跟参考图里的横向位置走：左侧先落定，右侧发梢最后落定。
                normalisedX = ((homeX - subjectLeft) / subjectWidth).coerceIn(0f, 1f),
                driftPhase = hash01(seed * 7.9f) * 2f * PI.toFloat(),
            )
        }

        private fun hash01(value: Float): Float {
            val raw = sin(value * 12.9898f) * 43758.5453f
            return raw - floor(raw)
        }

        private fun arcLength(points: FloatArray): Float {
            var total = 0f
            var index = 2
            while (index < points.size) {
                total += hypot(points[index] - points[index - 2], points[index + 1] - points[index - 1])
                index += 2
            }
            return total
        }

        private fun appendHome(
            path: Path,
            fragment: ContourFragment,
        ) {
            path.moveTo(fragment.homeX + fragment.localPoints[0], fragment.homeY + fragment.localPoints[1])
            var index = 2
            while (index < fragment.localPoints.size) {
                path.lineTo(fragment.homeX + fragment.localPoints[index], fragment.homeY + fragment.localPoints[index + 1])
                index += 2
            }
        }

        /** 按弧长切分折线；尾段若还剩实线段则保留。 */
        private fun splitByArcLength(
            points: FloatArray,
            span: Float,
        ): List<FloatArray> {
            val pieces = ArrayList<FloatArray>()
            if (points.size < 4) return pieces
            var current = FloatBuilder(24)
            current.add(points[0], points[1])
            var accumulated = 0f
            var index = 2
            while (index < points.size) {
                val fromX = points[index - 2]
                val fromY = points[index - 1]
                val toX = points[index]
                val toY = points[index + 1]
                index += 2
                val segment = hypot(toX - fromX, toY - fromY)
                if (segment <= 0.0001f) continue
                var remaining = segment
                var startX = fromX
                var startY = fromY
                while (accumulated + remaining >= span) {
                    val need = span - accumulated
                    val ratio = if (remaining <= 0f) 1f else need / remaining
                    val cutX = startX + (toX - startX) * ratio
                    val cutY = startY + (toY - startY) * ratio
                    current.add(cutX, cutY)
                    pieces.add(current.toArray())
                    current = FloatBuilder(24)
                    current.add(cutX, cutY)
                    remaining -= need
                    startX = cutX
                    startY = cutY
                    accumulated = 0f
                }
                accumulated += remaining
                current.add(toX, toY)
            }
            if (current.size >= 4) pieces.add(current.toArray())
            return pieces
        }
    }
}

/** 构建期用的可增长 Float 数组。 */
private class FloatBuilder(
    initialCapacity: Int,
) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(4))
    var size = 0
        private set

    fun add(
        x: Float,
        y: Float,
    ) {
        ensure(2)
        data[size++] = x
        data[size++] = y
    }

    fun toArray(): FloatArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}

/**
 * DRAWING：碎片按目标 X 归位。已归位的列走预建 Path（每列 1 次 draw），
 * 飞行中的列合并进同一条 scratch Path（每帧 1 次 draw），零分配。
 */
fun DrawScope.drawContourAct(
    t: Float,
    template: ContourTemplate,
    lineColor: Color,
    accent: Color,
    alpha: Float,
    reduced: Boolean,
) {
    if (t < StartupTimeline.DRAWING_START_MS || alpha <= 0f) return
    val lineAlpha = alpha * (1f - StartupTimeline.colorizeMix(t))
    if (lineAlpha <= 0f) return
    val lineIn = smoothstep(StartupTimeline.DRAWING_START_MS, StartupTimeline.DRAWING_START_MS + 150f, t)
    val zoom = if (reduced) 1f else StartupTimeline.kenBurnsScale(t)
    withTransform({ scale(scaleX = zoom, scaleY = zoom, pivot = template.subjectCenter) }) {
        val scratch = template.flyingPath
        scratch.rewind()
        var flying = false
        for (column in template.columns) {
            val columnArrival = StartupTimeline.fragmentArrival(t, column.normalisedX)
            when {
                columnArrival <= 0.001f -> {
                    // 尚未开始：该列不画。
                }

                columnArrival >= 1f -> {
                    drawPath(
                        path = column.path,
                        color = lineColor.copy(alpha = 0.74f * lineAlpha * lineIn),
                        style = template.lineStyle,
                    )
                    // 刚落定的列额外压一层强调色 + 一道竖向扫描线：让"归位"这一下看得见。
                    val settling = 1f - StartupTimeline.fragmentArrival(t - SETTLE_FLASH_MS, column.normalisedX)
                    if (settling > 0.001f) {
                        drawPath(
                            path = column.path,
                            color = accent.copy(alpha = 0.55f * settling * lineAlpha * lineIn),
                            style = template.lineStyle,
                        )
                        val columnX = template.subjectCenter.x - template.subjectWidth / 2f + column.normalisedX * template.subjectWidth
                        drawLine(
                            color = accent.copy(alpha = 0.30f * settling * lineAlpha),
                            start = Offset(columnX, template.subjectCenter.y - template.subjectHeight / 2f),
                            end = Offset(columnX, template.subjectCenter.y + template.subjectHeight / 2f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }

                else -> {
                    for (fragment in column.fragments) {
                        appendFlying(
                            path = scratch,
                            fragment = fragment,
                            arrival = StartupTimeline.fragmentArrival(t, fragment.normalisedX),
                            t = t,
                            driftAmplitude = if (reduced) 0f else template.driftAmplitude,
                        )
                        flying = true
                    }
                }
            }
        }
        if (flying) {
            drawPath(
                path = scratch,
                color = lineColor.copy(alpha = 0.9f * lineAlpha * lineIn),
                style = template.lineStyle,
            )
        }
    }
}

private fun appendFlying(
    path: Path,
    fragment: ContourFragment,
    arrival: Float,
    t: Float,
    driftAmplitude: Float,
) {
    val eased = arrival.coerceIn(0f, 1f)
    val size = 0.16f + 0.84f * eased
    val drift = if (driftAmplitude <= 0f) 0f else (1f - eased) * driftAmplitude
    val driftX = sin(t * 0.0021f + fragment.driftPhase) * drift
    val driftY = cos(t * 0.0017f + fragment.driftPhase * 1.3f) * drift
    val positionX = fragment.scatterX + (fragment.homeX - fragment.scatterX) * eased + driftX
    val positionY = fragment.scatterY + (fragment.homeY - fragment.scatterY) * eased + driftY
    val angle = fragment.angle * (1f - eased)
    val cosA = cos(angle)
    val sinA = sin(angle)
    val points = fragment.localPoints
    var index = 0
    while (index < points.size) {
        val localX = points[index] * size
        val localY = points[index + 1] * size
        val rotatedX = localX * cosA - localY * sinA
        val rotatedY = localX * sinA + localY * cosA
        if (index == 0) {
            path.moveTo(positionX + rotatedX, positionY + rotatedY)
        } else {
            path.lineTo(positionX + rotatedX, positionY + rotatedY)
        }
        index += 2
    }
}
