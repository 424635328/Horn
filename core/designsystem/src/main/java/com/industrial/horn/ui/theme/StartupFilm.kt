package com.industrial.horn.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
            accent = accent,
            alpha = contentAlpha,
            reduced = reduced,
        )

        drawHeroReveal(
            t = t,
            scene = scene,
            palette = palette,
            hero = hero,
            accent = accent,
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
    palette: HornColors,
    hero: ImageBitmap?,
    accent: Color,
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
        // 仪表边框：1dp 发丝 + 四角强调刻度，让参考图读作「仪表上的一块印片」而不是浮在暗场里的图。
        drawRect(
            color = palette.hairlineStrong.copy(alpha = 0.55f * mix * alpha),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawFrameTicks(rect, accent, mix * alpha)
    }
    if (!reduced && t >= StartupTimeline.COLORIZE_END_MS && t < StartupTimeline.PORTRAIT_END_MS) {
        drawHeroPortraitOverlay(t = t, rect = rect, surface = scene.surface, accent = accent, alpha = alpha)
    }
}

/** 印片四角的强调刻度：与交棒遮片的接缝同一套仪表语言。 */
private fun DrawScope.drawFrameTicks(
    rect: Rect,
    accent: Color,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val inset = 7.dp.toPx()
    val arm = 15.dp.toPx()
    val stroke = 1.4.dp.toPx()
    val color = accent.copy(alpha = 0.62f * alpha)
    val left = rect.left + inset
    val right = rect.right - inset
    val top = rect.top + inset
    val bottom = rect.bottom - inset
    drawLine(color, Offset(left, top), Offset(left + arm, top), stroke)
    drawLine(color, Offset(left, top), Offset(left, top + arm), stroke)
    drawLine(color, Offset(right, top), Offset(right - arm, top), stroke)
    drawLine(color, Offset(right, top), Offset(right, top + arm), stroke)
    drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), stroke)
    drawLine(color, Offset(left, bottom), Offset(left, bottom - arm), stroke)
    drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), stroke)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), stroke)
}

/**
 * PORTRAIT：一层向内的晕影把注意力收回画面中心，外加一次自上而下的扫描（skill 的「光扫一次性」）。
 * 两者都只画在参考图矩形内，且不改变图像本身（零滤镜、零额外纹理）。
 */
private fun DrawScope.drawHeroPortraitOverlay(
    t: Float,
    rect: Rect,
    surface: Color,
    accent: Color,
    alpha: Float,
) {
    val progress =
        ((t - StartupTimeline.COLORIZE_END_MS) / (StartupTimeline.PORTRAIT_END_MS - StartupTimeline.COLORIZE_END_MS))
            .coerceIn(0f, 1f)
    clipRect(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom) {
        drawRect(
            brush =
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(Color.Transparent, surface.copy(alpha = 0.55f)),
                    center = rect.center,
                    radius = rect.width * 0.72f,
                ),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            alpha = alpha * 0.9f,
        )
        val sweep = kotlin.math.sin(progress * kotlin.math.PI.toFloat())
        if (sweep > 0f) {
            val y = rect.top + rect.height * progress
            drawRect(
                brush =
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, accent.copy(alpha = 0.16f), Color.Transparent),
                        startY = y - 26.dp.toPx(),
                        endY = y + 26.dp.toPx(),
                    ),
                topLeft = Offset(rect.left, y - 26.dp.toPx()),
                size = Size(rect.width, 52.dp.toPx()),
                alpha = alpha * sweep,
            )
            drawLine(
                color = accent.copy(alpha = 0.34f * sweep * alpha),
                start = Offset(rect.left, y),
                end = Offset(rect.right, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
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
    // 头像框四角 reticle（skill §11.1.2）：让圆形头像读作"被取景框锁定的仪表窗口"。
    val bracket = radius * 1.20f
    val arm = radius * 0.30f
    val stroke = 1.2.dp.toPx()
    val bracketColor = color.copy(alpha = 0.50f * alpha)
    val left = center.x - bracket
    val right = center.x + bracket
    val top = center.y - bracket
    val bottom = center.y + bracket
    drawLine(bracketColor, Offset(left, top), Offset(left + arm, top), stroke)
    drawLine(bracketColor, Offset(left, top), Offset(left, top + arm), stroke)
    drawLine(bracketColor, Offset(right, top), Offset(right - arm, top), stroke)
    drawLine(bracketColor, Offset(right, top), Offset(right, top + arm), stroke)
    drawLine(bracketColor, Offset(left, bottom), Offset(left + arm, bottom), stroke)
    drawLine(bracketColor, Offset(left, bottom), Offset(left, bottom - arm), stroke)
    drawLine(bracketColor, Offset(right, bottom), Offset(right - arm, bottom), stroke)
    drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - arm), stroke)
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
