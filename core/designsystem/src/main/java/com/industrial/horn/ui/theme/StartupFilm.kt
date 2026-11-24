package com.industrial.horn.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 开屏短片需要的全部预计算产物：气氛笔刷、表盘三角函数表、参考图线稿模板、构图锚点。
 * 一次按尺寸/主题构建，逐帧只读；同一实例可渲染任意 t，因此定格、scrub、replay 都免费。
 */
class StartupFilmScene(
    val brushes: WakeSceneBrushes,
    val contour: ContourTemplate,
    val center: Offset,
    val dialRadius: Float,
    val surface: Color,
    val heroAspect: Float,
) {
    companion object {
        /** 光学中心：表盘、参考图、交棒轴线共用同一处。 */
        const val OPTICAL_AXIS = StartupHeroLayout.CENTER_Y_FRACTION
        const val DIAL_RADIUS_FRACTION = 0.24f

        fun of(
            size: Size,
            density: Float,
            palette: HornColors,
            accent: Color,
            dark: Boolean,
            polylines: List<FloatArray>,
            heroAspect: Float,
        ): StartupFilmScene {
            if (size.width <= 0f || size.height <= 0f) {
                // 首帧可能拿到零尺寸作用域；模板仍要可构建，避免跳过第一帧。
                return of(Size(1f, 1f), density, palette, accent, dark, polylines, heroAspect)
            }
            val center = Offset(size.width / 2f, size.height * OPTICAL_AXIS)
            val dialRadius = min(size.width, size.height) * DIAL_RADIUS_FRACTION
            return StartupFilmScene(
                brushes = WakeSceneBrushes.of(size, center, dialRadius, palette, accent, dark),
                contour =
                    ContourTemplate.of(
                        size = size,
                        density = density,
                        polylines = polylines,
                        aspect = heroAspect,
                    ),
                center = center,
                dialRadius = dialRadius,
                surface = palette.canvas,
                heroAspect = heroAspect,
            )
        }
    }
}

/**
 * 短片唯一渲染入口：`render(t)` 六幕。所有幕共享同一个场景与同一个时钟，
 * 所以暂停、拖动、定格都只是换一个 t。
 *
 * 交棒（skill §"收尾转场 = 交棒协议"）：内容纵向合拢到光学轴，底衬同步溶解，
 * 真实界面从两半之间露出来——不是"淡出消失"。
 */
fun DrawScope.drawStartupFilm(
    t: Float,
    scene: StartupFilmScene,
    palette: HornColors,
    accent: Color,
    dark: Boolean,
    reduced: Boolean,
    hero: ImageBitmap?,
) {
    val collapse = if (reduced) 0f else StartupTimeline.shutterCollapse(t)
    val squeeze = (1f - collapse).coerceAtLeast(0.001f)
    val overlay = StartupTimeline.overlayAlpha(t)
    if (overlay <= 0f) return
    val surfaceAlpha = StartupTimeline.backgroundAlpha(t) * overlay
    val contentAlpha = overlay

    withTransform({ scale(scaleX = 1f, scaleY = squeeze, pivot = scene.center) }) {
        drawStartupAtmosphere(
            t = t,
            brushes = scene.brushes,
            palette = palette,
            dark = dark,
            surface = scene.surface,
            alpha = contentAlpha * (1f - collapse * 0.35f),
            backgroundAlpha = surfaceAlpha,
        )

        drawPulseAct(
            t = t,
            brushes = scene.brushes,
            center = scene.center,
            radius = scene.dialRadius,
            accent = accent,
            palette = palette,
            dark = dark,
            alpha = contentAlpha,
        )

        drawIdentityAct(
            t = t,
            brushes = scene.brushes,
            palette = palette,
            accent = accent,
            dark = dark,
            center = scene.center,
            radius = scene.dialRadius,
            alpha = contentAlpha,
        )

        // 身份幕的头像 = 参考图的圆形裁切，让"这张脸"在聚合之前先被认出来。
        val avatarAlpha = StartupTimeline.identityAlpha(t) * (1f - StartupTimeline.identitySpread(t)) * contentAlpha
        if (avatarAlpha > 0f && t < StartupTimeline.DRAWING_START_MS) {
            drawHeroAvatar(
                hero = hero,
                t = t,
                scene = scene,
                color = if (dark) accent else palette.textPrimary,
                alpha = avatarAlpha * 0.9f,
            )
        }

        drawIdentitySpread(
            t = t,
            center = scene.center,
            radius = scene.dialRadius,
            accent = accent,
            alpha = contentAlpha,
        )

        drawContourAct(
            t = t,
            template = scene.contour,
            lineColor = palette.textSecondary,
            alpha = contentAlpha,
            reduced = reduced,
        )

        drawHeroReveal(
            t = t,
            scene = scene,
            hero = hero,
            alpha = contentAlpha,
            reduced = reduced,
        )

        drawLightSweep(t = t, brushes = scene.brushes, alpha = contentAlpha * 0.9f)

        drawParticles(
            t = t,
            accent = accent,
            color = palette.textSecondary,
            radiusPx = 2.2f.dp.toPx(),
            risePx = scene.dialRadius * 0.55f,
            alpha = contentAlpha,
            reduced = reduced,
        )
    }
}

/** 参考图的显示矩形（未缩放），与线稿共用同一个构图盒。 */
private fun StartupFilmScene.heroRect(): Rect {
    val width = contour.subjectWidth
    val height = contour.subjectHeight
    return Rect(
        left = center.x - width / 2f,
        top = center.y - height / 2f,
        right = center.x + width / 2f,
        bottom = center.y + height / 2f,
    )
}

/**
 * COLORIZE + PORTRAIT：参考图本体淡入，与线稿逐像素对齐；Ken Burns 用同一 transform-origin，
 * 所以"错位感"不会出现（skill 明确要求同几何、同 origin）。
 */
private fun DrawScope.drawHeroReveal(
    t: Float,
    scene: StartupFilmScene,
    hero: ImageBitmap?,
    alpha: Float,
    reduced: Boolean,
) {
    val mix = StartupTimeline.colorizeMix(t)
    if (hero == null || mix <= 0f) return
    val rect = scene.heroRect()
    val zoom = if (reduced) 1f else StartupTimeline.kenBurnsScale(t)
    withTransform({ scale(scaleX = zoom, scaleY = zoom, pivot = scene.center) }) {
        drawImage(
            image = hero,
            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
            dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            alpha = mix * alpha,
        )
    }
}

/** 身份幕头像：参考图按 cover 裁成圆形，压在表盘中心。 */
private fun DrawScope.drawHeroAvatar(
    hero: ImageBitmap?,
    t: Float,
    scene: StartupFilmScene,
    color: Color,
    alpha: Float,
) {
    if (hero == null) return
    val radius = scene.dialRadius * 0.62f * StartupTimeline.dialScale(t)
    val center = scene.center
    val circle = Path().apply { addOval(Rect(center = center, radius = radius)) }
    clipPath(circle) {
        drawImageCover(hero, center, radius * 2f, alpha)
    }
    drawCircle(
        color = color.copy(alpha = 0.35f * alpha),
        radius = radius,
        center = center,
        style =
            androidx.compose.ui.graphics.drawscope
                .Stroke(width = 1.dp.toPx()),
    )
}

/** 以中心方形裁切方式把参考图铺满 [size] 方框（cover，不拉伸）。 */
private fun DrawScope.drawImageCover(
    hero: ImageBitmap,
    center: Offset,
    size: Float,
    alpha: Float,
) {
    val imageWidth = hero.width.toFloat()
    val imageHeight = hero.height.toFloat()
    val side = min(imageWidth, imageHeight)
    val srcLeft = ((imageWidth - side) / 2f).roundToInt()
    val srcTop = ((imageHeight - side) / 2f).roundToInt()
    drawImage(
        image = hero,
        srcOffset = IntOffset(srcLeft, srcTop),
        srcSize = IntSize(side.roundToInt(), side.roundToInt()),
        dstOffset = IntOffset((center.x - size / 2f).roundToInt(), (center.y - size / 2f).roundToInt()),
        dstSize = IntSize(size.roundToInt(), size.roundToInt()),
        alpha = alpha,
    )
}
