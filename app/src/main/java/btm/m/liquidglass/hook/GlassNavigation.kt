// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.liquidglass.hook

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.AppColorMode
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.abs
import kotlin.math.sign

data class HostTab(
    val label: String,
    val className: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

@Composable
fun CustomNavigation(
    sourceView: View,
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    blurRadius: Int,
    labelMode: String,
    navigationStyle: String,
    advancedMaterial: Boolean,
    colorMode: String,
    liquidBottomSpacingDp: Int = 8,
    concealHostBottomBar: Boolean = false,
    forceFloatingGlass: Boolean = false,
    adaptiveFloatingWidth: Boolean = false,
    dragWholeFloatingBar: Boolean = false,
    adaptiveLiquidWidth: Boolean = false,
    accentColorOverride: Color? = null,
    redrawNativeText: Boolean = false,
    onHostPreDraw: () -> Unit,
    backdropOverride: Backdrop? = null,
    tabImageVector: ((Int) -> ImageVector)? = null,
    tabIconContent: (@Composable (Int, Color) -> Unit)? = null
) {
    if (tabs.isEmpty()) return
    val style = NavigationStyle.fromPreference(navigationStyle)
    val requestedMode = LabelMode.fromPreference(labelMode)
    val effectiveMode = requestedMode
    val systemIsDarkTheme = LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val hostIsDarkTheme = when (AppColorMode.fromPreference(colorMode)) {
        AppColorMode.SYSTEM -> systemIsDarkTheme
        AppColorMode.LIGHT -> false
        AppColorMode.DARK -> true
    }
    val backdrop = backdropOverride ?: rememberNativeViewBackdrop(sourceView, onHostPreDraw, redrawNativeText)
    val density = LocalDensity.current
    if (style != NavigationStyle.LIQUID_GLASS) {
        Box(Modifier.fillMaxSize()) {
            if (concealHostBottomBar) {
                HostBottomBarConcealment(backdrop, hostIsDarkTheme, density)
            }
            HyperNavigation(
                sourceView = sourceView,
                tabs = tabs,
                selectedIndex = selectedIndex,
                floating = style == NavigationStyle.HYPER_OS_FLOATING || style == NavigationStyle.HARMONY_OS_FLOATING || forceFloatingGlass,
                harmonyFloating = style == NavigationStyle.HARMONY_OS_FLOATING,
                backdrop = backdrop,
                blurRadius = blurRadius,
                showIcons = effectiveMode != LabelMode.TEXT_ONLY,
                showLabels = effectiveMode != LabelMode.ICON_ONLY,
                textOnly = effectiveMode == LabelMode.TEXT_ONLY,
                advancedMaterial = advancedMaterial,
                hostIsDarkTheme = hostIsDarkTheme,
                accentColorOverride = accentColorOverride,
                adaptiveFloatingWidth = adaptiveFloatingWidth,
                dragWholeFloatingBar = dragWholeFloatingBar,
                tabImageVector = tabImageVector,
                tabIconContent = tabIconContent
            )
        }
        return
    }
    val view = LocalView.current

    Box(Modifier.fillMaxSize()) {
        if (concealHostBottomBar) {
            HostBottomBarConcealment(backdrop, hostIsDarkTheme, density)
        }
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 6.dp,
                    bottom = liquidBottomSpacingDp.dp
                )
        ) {
            LiquidBottomTabs(
                selectedTabIndex = { selectedIndex.intValue.coerceIn(0, tabs.lastIndex) },
                onTabSelected = { index ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    tabs[index].onClick()
                },
                backdrop = backdrop,
                tabsCount = tabs.size,
                showIcons = effectiveMode != LabelMode.TEXT_ONLY,
                showLabels = effectiveMode != LabelMode.ICON_ONLY,
                // Keep the existing preference scale while mapping its default 18 to the
                // official LiquidBottomTabs blur radius of 8dp.
                blurRadius = (blurRadius * 8f / 18f).dp,
                advancedMaterial = advancedMaterial,
                isDarkTheme = hostIsDarkTheme,
                accentColorOverride = accentColorOverride,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(
                        if (adaptiveLiquidWidth) {
                            1f
                        } else {
                            0.53f * 0.82f * 1.10f
                        }
                    )
                    .height(64.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    LiquidBottomTab(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            tab.onClick()
                        }
                    ) {
                        if (effectiveMode != LabelMode.TEXT_ONLY) {
                            if (tabImageVector != null) {
                                Image(
                                    imageVector = tabImageVector(index),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(LocalLiquidContentColor.current),
                                    modifier = Modifier.size(28.dp)
                                )
                            } else if (tabIconContent != null) {
                                tabIconContent(index, LocalLiquidContentColor.current)
                            } else {
                                TabIcon(tab.label, LocalLiquidContentColor.current)
                            }
                        }
                        if (effectiveMode != LabelMode.ICON_ONLY && tab.label != "+") {
                            BasicText(
                                text = tab.label,
                                style = TextStyle(
                                    color = LocalLiquidContentColor.current,
                                    fontSize = if (effectiveMode == LabelMode.TEXT_ONLY) 15.75.sp else 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostBottomBarConcealment(
    backdrop: Backdrop,
    hostIsDarkTheme: Boolean,
    density: androidx.compose.ui.unit.Density
) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    vibrancy()
                    blur(with(density) { 40.dp.toPx() })
                },
                highlight = null,
                onDrawSurface = {
                    drawRect(if (hostIsDarkTheme) Color.Black else Color.White)
                }
            )
    )
}

@Composable
private fun HyperNavigation(
    sourceView: View,
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    floating: Boolean,
    harmonyFloating: Boolean,
    backdrop: Backdrop,
    blurRadius: Int,
    showIcons: Boolean,
    showLabels: Boolean,
    textOnly: Boolean,
    advancedMaterial: Boolean,
    hostIsDarkTheme: Boolean,
    accentColorOverride: Color? = null,
    adaptiveFloatingWidth: Boolean = false,
    dragWholeFloatingBar: Boolean = false,
    enableDrag: Boolean = true,
    tabImageVector: ((Int) -> ImageVector)? = null,
    tabIconContent: (@Composable (Int, Color) -> Unit)? = null
) {
    val controller = remember(hostIsDarkTheme) {
        ThemeController(
            if (hostIsDarkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light
        )
    }
    val view = LocalView.current
    MiuixTheme(controller = controller) {
        val isDarkTheme = hostIsDarkTheme
        val useAdvancedMaterial = advancedMaterial && isRuntimeShaderSupported()
        val density = LocalDensity.current
        val materialBlurRadius = with(density) {
            blurRadius.coerceIn(0, 40).dp.toPx()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            if (floating) {
                HyperFloatingNavigationBar(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    backdrop = backdrop,
                    blurRadius = blurRadius.coerceIn(0, 40).toFloat(),
                    showIcons = showIcons,
                    showLabels = showLabels,
                    advancedMaterial = useAdvancedMaterial,
                    isDarkTheme = isDarkTheme,
                    accentColorOverride = accentColorOverride,
                    adaptiveFloatingWidth = adaptiveFloatingWidth,
                    dragWholeFloatingBar = dragWholeFloatingBar,
                    harmonyFloating = harmonyFloating,
                    enableDrag = !harmonyFloating,
                    tabImageVector = tabImageVector,
                    tabIconContent = tabIconContent,
                    onTabSelected = { index ->
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        tabs[index].onClick()
                    }
                )
            } else {
                val standardBarMaterial = if (useAdvancedMaterial) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { androidx.compose.ui.graphics.RectangleShape },
                        effects = {
                            vibrancy()
                            blur(materialBlurRadius)
                        },
                        highlight = null,
                        onDrawSurface = {
                            drawRect(
                                if (isDarkTheme) Color.Black.copy(alpha = 0.58f)
                                else Color.White.copy(alpha = 0.62f)
                            )
                        }
                    )
                } else {
                    Modifier
                }
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            shape = androidx.compose.ui.graphics.RectangleShape
                            clip = true
                            shadowElevation = 0f
                            ambientShadowColor = Color.Transparent
                            spotShadowColor = Color.Transparent
                        }
                        .then(standardBarMaterial),
                    color = if (useAdvancedMaterial) Color.Transparent else MiuixTheme.colorScheme.surface,
                    showDivider = false,
                    defaultWindowInsetsPadding = true,
                    mode = if (showLabels) {
                        NavigationBarDisplayMode.IconAndText
                    } else {
                        NavigationBarDisplayMode.IconOnly
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        if (textOnly) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                label = tab.label,
                                modifier = Modifier.weight(1f),
                                textOnly = true,
                                selectedColor = accentColorOverride,
                                icon = {}
                            )
                        } else if (tabIconContent != null) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                label = if (showLabels) tab.label else "",
                                modifier = Modifier.weight(1f),
                                selectedColor = accentColorOverride,
                                icon = { color ->
                                    if (showIcons) tabIconContent(index, color)
                                }
                            )
                        } else {
                            NavigationBarItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); tab.onClick() },
                                icon = if (showIcons) {
                                    tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                                } else {
                                    HyperIcons.Empty
                                },
                                label = tab.label
                            )
                        }
                    }
                }
            }
        }
    }
}

/** OS4-style floating navigation: one capsule, a moving selected pill, and a damped drag. */
@Composable
private fun HyperFloatingNavigationBar(
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    backdrop: Backdrop,
    blurRadius: Float,
    showIcons: Boolean,
    showLabels: Boolean,
    advancedMaterial: Boolean,
    isDarkTheme: Boolean,
    accentColorOverride: Color?,
    adaptiveFloatingWidth: Boolean,
    dragWholeFloatingBar: Boolean,
    harmonyFloating: Boolean,
    enableDrag: Boolean,
    tabImageVector: ((Int) -> ImageVector)?,
    tabIconContent: (@Composable (Int, Color) -> Unit)?,
    onTabSelected: (Int) -> Unit,
) {
    // Keep the OS4 floating bar compact without changing the other navigation styles.
    val floatingScale = 0.82f
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val tabCount = tabs.size.coerceAtLeast(1)
    val initialIndex = selectedIndex.intValue.coerceIn(0, tabCount - 1)
    val dragAnimation = remember(tabCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = initialIndex.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // OS4 compresses the floating pill while it is held, then springs back on release.
            pressedScale = 0.9f,
            onDragStopped = {
                val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
                animateToValue(target.toFloat())
                if (target != selectedIndex.intValue) onTabSelected(target)
            },
            onDrag = { size, amount ->
                val slotWidth = size.width.toFloat() / tabCount.coerceAtLeast(1)
                if (slotWidth > 0f) {
                    updateValue((targetValue + amount.x / slotWidth).fastCoerceIn(0f, (tabCount - 1).toFloat()))
                }
            },
            // OS4 restores the pressed scale as soon as the finger is released.
            awaitTargetBeforeRelease = false,
            followFingerImmediately = dragWholeFloatingBar,
            onTap = if (dragWholeFloatingBar) ({ size, position ->
                val slotWidth = size.width.toFloat() / tabCount
                if (slotWidth > 0f) {
                    val target = (position.x / slotWidth).toInt().fastCoerceIn(0, tabCount - 1)
                    animateToValue(target.toFloat())
                    if (target != selectedIndex.intValue) onTabSelected(target)
                }
            }) else null,
        )
    }
    val touchInteractionSource = remember { MutableInteractionSource() }
    val touchPressed by touchInteractionSource.collectIsPressedAsState()
    val touchProgress by animateFloatAsState(
        targetValue = if (harmonyFloating && touchPressed) 1f else 0f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.82f),
        label = "harmonyFloatingTouchProgress",
    )
    val visualPressProgress = if (harmonyFloating) touchProgress else dragAnimation.pressProgress
    val interactiveHighlight = remember(animationScope, tabCount) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    LaunchedEffect(selectedIndex.intValue) {
        dragAnimation.animateToValue(selectedIndex.intValue.coerceIn(0, tabCount - 1).toFloat())
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black
    val selectedContentColor = accentColorOverride ?: contentColor
    val pressedIndex = if (dragAnimation.pressProgress > 0.001f) {
        dragAnimation.value.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
    } else {
        -1
    }
    val selectedSurface = if (isDarkTheme) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val containerSurface = if (isDarkTheme) {
        Color.Black.copy(alpha = if (harmonyFloating) 0.36f else 0.30f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = if (harmonyFloating) 0.48f else 0.42f)
    }
    val fallbackSurface = if (isDarkTheme) {
        Color(0xFF242424).copy(alpha = 0.88f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.82f)
    }
    val capsule = Capsule()
    val shadowColor = Color.Black.copy(alpha = 0.35f)

    val widthFraction = if (adaptiveFloatingWidth) {
        (0.25f + tabCount * 0.135f).coerceIn(0.53f, 0.92f)
    } else {
        0.53f * floatingScale
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(widthFraction)
            .navigationBarsPadding()
            .padding(bottom = 8.dp * floatingScale)
            .height(72.dp * floatingScale),
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerPadding = 4.dp * floatingScale
        val innerWidthPx = with(density) { (maxWidth - innerPadding * 2).toPx() }
        val tabWidthPx = innerWidthPx / tabCount
        val tabWidth = with(density) { tabWidthPx.toDp() }
        val selectedTranslation = dragAnimation.value * tabWidthPx
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (harmonyFloating) {
                        // Match the title button glass response: the capsule expands visibly
                        // while the touch highlight and refraction build up.
                        val deformation = lerp(1f, 1.08f, visualPressProgress)
                        scaleX = deformation
                        scaleY = deformation
                    }
                }
                .shadow(
                    elevation = 6.dp,
                    shape = capsule,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .then(
                    if (advancedMaterial) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { capsule },
                            effects = {
                                vibrancy()
                                blur(with(density) {
                                    (blurRadius * if (harmonyFloating) 0.29f else 0.24f).dp.toPx()
                                })
                                if (harmonyFloating) {
                                    // Harmony's capsule keeps its liquid refraction at rest. A
                                    // press boosts it instead of being the only time it exists.
                                    val progress = 1f + 0.3f * visualPressProgress
                                    lens(
                                        refractionHeight = 16.dp.toPx() * progress,
                                        refractionAmount = 32.dp.toPx() * progress,
                                        depthEffect = true,
                                        chromaticAberration = false,
                                    )
                                } else {
                                    // Keep the lens pronounced while avoiding an overly strong refraction.
                                    lens(
                                        refractionHeight = 16.dp.toPx(),
                                        refractionAmount = 32.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = false,
                                    )
                                }
                            },
                            highlight = {
                                Highlight.Default.copy(
                                    alpha = if (harmonyFloating) {
                                        // Keep the edge highlight visible when idle, then make
                                        // the pressed state noticeably brighter.
                                        val base = if (isDarkTheme) 0.78f else 0.96f
                                        (base * (0.72f + 0.28f * visualPressProgress))
                                            .coerceIn(0f, 1f)
                                    } else {
                                        if (isDarkTheme) 0.78f else 0.96f
                                    }
                                )
                            },
                            onDrawSurface = {
                                drawRect(containerSurface)
                            },
                        )
                    } else Modifier.clip(capsule).background(fallbackSurface)
                )
        )
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Draw the selection behind labels, while a transparent overlay below captures drag.
            if (!harmonyFloating) Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .clip(capsule)
                    .background(selectedSurface)
            )
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val pressedProgress = if (pressedIndex == index) dragAnimation.pressProgress else 0f
                    val color = if (harmonyFloating) {
                        if (index == selectedIndex.intValue || pressedProgress > 0f) Color(0xFF0088FF) else contentColor.copy(alpha = 0.58f)
                    } else if (pressedProgress > 0f) {
                        Color.Gray
                    } else {
                        // OS4 uses the hover capsule, rather than a different tint, to show selection.
                        selectedContentColor
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val scale = lerp(1f, 0.85f, pressedProgress)
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable(
                                interactionSource = if (harmonyFloating) touchInteractionSource else null,
                                indication = null,
                                role = Role.Tab,
                            ) {
                                if (index != selectedIndex.intValue) onTabSelected(index)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (showIcons) {
                            val icon = tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                            if (tabIconContent != null) {
                                tabIconContent(index, color)
                            } else {
                                Image(icon, null, Modifier.size(28.dp * floatingScale), colorFilter = ColorFilter.tint(color))
                            }
                        }
                        if (showLabels && tab.label != "+") {
                            BasicText(
                                tab.label,
                                style = TextStyle(
                                    color = color,
                                    fontSize = if (showIcons) 15.sp * floatingScale else 17.sp * floatingScale,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                modifier = if (showIcons) Modifier.padding(top = 2.dp * floatingScale) else Modifier,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .then(if (dragWholeFloatingBar || !enableDrag) Modifier else dragAnimation.modifier)
            )
            if (dragWholeFloatingBar && enableDrag) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dragAnimation.modifier)
                )
            }
            // Draw the interactive highlight above the backdrop surface. InteractiveHighlight's
            // draw modifier renders before its child content, so it must be a final overlay here.
            if (harmonyFloating) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(capsule)
                        .then(interactiveHighlight.modifier)
                )
            }
        }
    }
}

@Composable
 private fun FontNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier,
    textOnly: Boolean = false,
    selectedColor: Color? = null,
    icon: @Composable (Color) -> Unit
) {
    val color = if (selected && selectedColor != null) {
        selectedColor
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (selected) 1f else 0.55f)
    }
    Column(
        modifier = modifier.height(56.dp).clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon(color)
        if (label.isNotEmpty()) {
            BasicText(label, style = TextStyle(color = color, fontSize = if (textOnly) 16.5.sp else 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
        }
    }
}

private fun hyperIcon(label: String): ImageVector = when {
    label == "+" -> HyperIcons.Add
    label.contains("\u9996\u9875") || label.contains("\u5065\u5eb7") -> HyperIcons.Home
    label.contains("\u5206\u7c7b") || label.contains("\u83dc\u5355") || label.contains("\u6d4f\u89c8") ||
        label.contains("\u8d44\u6599\u5e93") || label.contains("\u8bbe\u5907") -> HyperIcons.Category
    label.contains("\u670d\u52a1") -> HyperIcons.Service
    label.contains("\u8d2d\u7269\u8f66") -> HyperIcons.Cart
    label.contains("\u89c6\u9891") || label.contains("\u77ed\u5267") -> HyperIcons.Video
    label.contains("\u7701\u94b1") -> HyperIcons.Savings
    label.contains("\u501f\u94b1") -> HyperIcons.Loan
    label.contains("\u53d1\u73b0") || label.contains("\u5e7f\u573a") ||
        label.contains("\u641c\u7d22") || label.contains("\u96f7\u8fbe") -> HyperIcons.Search
    label.contains("\u6211") || label.contains("\u4e2a\u4eba") -> HyperIcons.Profile
    label.contains("\u6d88\u606f") || label.contains("\u804a\u5929") ||
        label.contains("\u6536\u4ef6\u7bb1") -> HyperIcons.Messages
    label.contains("\u8054\u7cfb") || label.contains("\u793e\u533a") -> HyperIcons.Contacts
    label.contains("\u5373\u4eab") || label.contains("\u4f1a\u5458\u5361") -> HyperIcons.Channels
    label.contains("\u9891\u9053") || label.contains("\u5e7f\u64ad") ||
        label.contains("\u7acb\u5373\u8046\u542c") || label.contains("\u8fd0\u52a8") -> HyperIcons.Channels
    else -> HyperIcons.Dynamic
}

private object HyperIcons {
    val Empty: ImageVector = lineIcon("Empty") {}
    val Add: ImageVector = lineIcon("Add") {
        moveTo(12f, 4f); lineTo(12f, 20f)
        moveTo(4f, 12f); lineTo(20f, 12f)
    }
    val Home: ImageVector = lineIcon("Home") {
        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
        lineTo(19f, 11f); lineTo(19f, 21f); lineTo(5f, 21f)
        lineTo(5f, 11f); close()
        moveTo(9f, 21f); lineTo(9f, 14f); lineTo(15f, 14f); lineTo(15f, 21f)
    }
    val Category: ImageVector = lineIcon("GridView") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val Service: ImageVector = lineIcon("Store") {
        moveTo(4f, 10f); lineTo(20f, 10f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(3f, 10f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 10f)
        moveTo(8f, 10f); curveTo(8f, 12f, 10f, 13f, 12f, 13f)
        curveTo(14f, 13f, 16f, 12f, 16f, 10f)
    }
    val Cart: ImageVector = lineIcon("ShoppingCart") {
        moveTo(3f, 4f); lineTo(5f, 4f); lineTo(7.5f, 16f); lineTo(19f, 16f); lineTo(21f, 8f); lineTo(6f, 8f)
        moveTo(9f, 20f); curveTo(9f, 20.6f, 8.6f, 21f, 8f, 21f); curveTo(7.4f, 21f, 7f, 20.6f, 7f, 20f); curveTo(7f, 19.4f, 7.4f, 19f, 8f, 19f); curveTo(8.6f, 19f, 9f, 19.4f, 9f, 20f)
        moveTo(19f, 20f); curveTo(19f, 20.6f, 18.6f, 21f, 18f, 21f); curveTo(17.4f, 21f, 17f, 20.6f, 17f, 20f); curveTo(17f, 19.4f, 17.4f, 19f, 18f, 19f); curveTo(18.6f, 19f, 19f, 19.4f, 19f, 20f)
    }
    val Video: ImageVector = lineIcon("Video") {
        moveTo(4f, 6f); lineTo(15f, 6f); curveTo(16.1f, 6f, 17f, 6.9f, 17f, 8f)
        lineTo(17f, 16f); curveTo(17f, 17.1f, 16.1f, 18f, 15f, 18f)
        lineTo(4f, 18f); curveTo(2.9f, 18f, 2f, 17.1f, 2f, 16f)
        lineTo(2f, 8f); curveTo(2f, 6.9f, 2.9f, 6f, 4f, 6f); close()
        moveTo(17f, 10f); lineTo(22f, 7f); lineTo(22f, 17f); lineTo(17f, 14f)
    }
    val Savings: ImageVector = lineIcon("Savings") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(8f, 9f); lineTo(12f, 12f); lineTo(16f, 9f)
        moveTo(8f, 14f); lineTo(16f, 14f)
        moveTo(12f, 12f); lineTo(12f, 18f)
    }
    val Loan: ImageVector = lineIcon("Loan") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(15f, 12f); curveTo(15f, 13.7f, 13.7f, 15f, 12f, 15f)
        curveTo(10.3f, 15f, 9f, 13.7f, 9f, 12f); curveTo(9f, 10.3f, 10.3f, 9f, 12f, 9f)
        curveTo(13.7f, 9f, 15f, 10.3f, 15f, 12f); close()
        moveTo(6f, 12f); lineTo(6.1f, 12f)
        moveTo(18f, 12f); lineTo(18.1f, 12f)
    }
    val Search: ImageVector = lineIcon("Search") {
        moveTo(19f, 11f); curveTo(19f, 15.4f, 15.4f, 19f, 11f, 19f)
        curveTo(6.6f, 19f, 3f, 15.4f, 3f, 11f); curveTo(3f, 6.6f, 6.6f, 3f, 11f, 3f)
        curveTo(15.4f, 3f, 19f, 6.6f, 19f, 11f); close()
        moveTo(17f, 17f); lineTo(22f, 22f)
    }
    val Profile: ImageVector = lineIcon("Profile") {
        moveTo(16f, 8f); curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
        curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f); curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
        curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f); close()
        moveTo(4f, 21f); curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
        curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
    }
    val Messages: ImageVector = ImageVector.Builder(
        name = "Messages",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(5f, 5f)
            curveTo(3.9f, 5f, 3f, 5.9f, 3f, 7f)
            lineTo(3f, 15f)
            curveTo(3f, 16.1f, 3.9f, 17f, 5f, 17f)
            lineTo(8f, 17f)
            lineTo(6f, 21f)
            lineTo(12f, 17f)
            lineTo(19f, 17f)
            curveTo(20.1f, 17f, 21f, 16.1f, 21f, 15f)
            lineTo(21f, 7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
        }
    }.build()

    val Contacts: ImageVector = ImageVector.Builder(
        name = "Contacts",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(16f, 8f)
            curveTo(16f, 10.2f, 14.2f, 12f, 12f, 12f)
            curveTo(9.8f, 12f, 8f, 10.2f, 8f, 8f)
            curveTo(8f, 5.8f, 9.8f, 4f, 12f, 4f)
            curveTo(14.2f, 4f, 16f, 5.8f, 16f, 8f)
            close()
            moveTo(4f, 21f)
            curveTo(4.8f, 16.8f, 7.5f, 14.5f, 12f, 14.5f)
            curveTo(16.5f, 14.5f, 19.2f, 16.8f, 20f, 21f)
        }
    }.build()

    val Channels: ImageVector = ImageVector.Builder(
        name = "Channels",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            close()
            moveTo(14.8f, 8.2f)
            lineTo(12.7f, 13.1f)
            lineTo(8.2f, 15.8f)
            lineTo(10.3f, 10.9f)
            close()
        }
    }.build()

    val Dynamic: ImageVector = ImageVector.Builder(
        name = "Dynamic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(14.2f, 9.8f)
            lineTo(22f, 12f)
            lineTo(14.2f, 14.2f)
            lineTo(12f, 22f)
            lineTo(9.8f, 14.2f)
            lineTo(2f, 12f)
            lineTo(9.8f, 9.8f)
            close()
        }
    }.build()
}

private fun lineIcon(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}.build()

private val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }
private val LocalLiquidContentColor = staticCompositionLocalOf { Color.Black }

@Composable
private fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
private fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    showIcons: Boolean,
    showLabels: Boolean,
    blurRadius: androidx.compose.ui.unit.Dp,
    advancedMaterial: Boolean,
    isDarkTheme: Boolean,
    accentColorOverride: Color? = null,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val isLightTheme = !isDarkTheme
    val accentColor = accentColorOverride ?:
        if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = 0.4f)
    } else {
        Color(0xFF121212).copy(alpha = 0.4f)
    }
    val tabsBackdrop = rememberKyantLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8.dp.toPx()) / tabsCount
        }
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedTabIndex()) }
        val dragAnimation = remember(animationScope, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, amount ->
                    updateValue(
                        (targetValue + amount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + amount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { currentIndex = it }
        }
        LaunchedEffect(dragAnimation) {
            snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
                dragAnimation.animateToValue(index.toFloat())
                onTabSelected(index)
            }
        }
        val interactiveHighlight = remember(animationScope, isLtr, tabWidth) {
            InteractiveHighlight(animationScope) { size, _ ->
                Offset(
                    if (isLtr) (dragAnimation.value + 0.5f) * tabWidth + panelOffset
                    else size.width - (dragAnimation.value + 0.5f) * tabWidth + panelOffset,
                    size.height / 2f
                )
            }
        }

        CompositionLocalProvider(LocalLiquidContentColor provides contentColor) {
            Row(
                Modifier
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (advancedMaterial) {
                                vibrancy()
                                blur(blurRadius.toPx())
                                lens(24.dp.toPx(), 24.dp.toPx())
                            }
                        },
                        highlight = if (advancedMaterial) ({ Highlight.Default }) else null,
                        layerBlock = {
                            val progress = dragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(64.dp)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dragAnimation.pressProgress)
            },
            LocalLiquidContentColor provides contentColor
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            if (advancedMaterial) {
                                val progress = dragAnimation.pressProgress
                                vibrancy()
                                blur(blurRadius.toPx())
                                lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            }
                        },
                        highlight = if (advancedMaterial) ({
                            Highlight.Default.copy(alpha = dragAnimation.pressProgress)
                        }) else null,
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dragAnimation.pressProgress
                        lens(
                            10.dp.toPx() * progress,
                            14.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = if (advancedMaterial) ({
                        Highlight.Default.copy(alpha = dragAnimation.pressProgress)
                    }) else null,
                    layerBlock = {
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                        val velocity = dragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(alpha = 0.1f)
                            else Color.White.copy(alpha = 0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

@Composable
private fun TabIcon(label: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when {
            label == "+" -> {
                drawRoundRect(
                    color = Color(0xFFFF2442),
                    topLeft = Offset(center.x - 11.dp.toPx(), center.y - 8.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(22.dp.toPx(), 16.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                )
                drawLine(
                    Color.White,
                    center - Offset(5.dp.toPx(), 0f),
                    center + Offset(5.dp.toPx(), 0f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    Color.White,
                    center - Offset(0f, 5.dp.toPx()),
                    center + Offset(0f, 5.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u9996\u9875") || label.contains("\u5065\u5eb7") -> {
                drawPath(Path().apply {
                    moveTo(center.x - 9.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x, center.y - 9.dp.toPx())
                    lineTo(center.x + 9.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y + 9.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y + 9.dp.toPx())
                    lineTo(center.x - 7.dp.toPx(), center.y - 1.dp.toPx())
                    close()
                }, color, style = stroke)
            }
            label.contains("\u5206\u7c7b") || label.contains("\u83dc\u5355") || label.contains("\u6d4f\u89c8") ||
                label.contains("\u8d44\u6599\u5e93") || label.contains("\u8bbe\u5907") -> {
                val cell = 6.dp.toPx()
                val gap = 3.dp.toPx()
                for (row in 0..1) for (column in 0..1) {
                    drawRoundRect(
                        color,
                        Offset(center.x - cell - gap / 2 + column * (cell + gap), center.y - cell - gap / 2 + row * (cell + gap)),
                        androidx.compose.ui.geometry.Size(cell, cell),
                        androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()),
                        style = stroke
                    )
                }
            }
            label.contains("\u670d\u52a1") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 8.dp.toPx(), center.y - 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(16.dp.toPx(), 11.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawLine(color, center - Offset(6.dp.toPx(), 2.dp.toPx()), center - Offset(4.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center + Offset(6.dp.toPx(), 2.dp.toPx()), center + Offset(4.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            label.contains("\u8d2d\u7269\u8f66") -> {
                drawLine(color, center - Offset(9.dp.toPx(), 7.dp.toPx()), center - Offset(6.dp.toPx(), 7.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center - Offset(6.dp.toPx(), 7.dp.toPx()), center - Offset(3.dp.toPx(), 2.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawRoundRect(color, Offset(center.x - 3.dp.toPx(), center.y - 5.dp.toPx()), androidx.compose.ui.geometry.Size(11.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = stroke)
                drawCircle(color, 1.5.dp.toPx(), center + Offset(0f, 7.dp.toPx()), style = stroke)
                drawCircle(color, 1.5.dp.toPx(), center + Offset(7.dp.toPx(), 7.dp.toPx()), style = stroke)
            }
            label.contains("\u89c6\u9891") || label.contains("\u77ed\u5267") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 9.dp.toPx(), center.y - 7.dp.toPx()),
                    androidx.compose.ui.geometry.Size(14.dp.toPx(), 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    style = stroke
                )
                drawPath(Path().apply {
                    moveTo(center.x + 5.dp.toPx(), center.y - 4.dp.toPx())
                    lineTo(center.x + 10.dp.toPx(), center.y - 7.dp.toPx())
                    lineTo(center.x + 10.dp.toPx(), center.y + 7.dp.toPx())
                    lineTo(center.x + 5.dp.toPx(), center.y + 4.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u7701\u94b1") -> {
                drawCircle(color, 9.dp.toPx(), center, style = stroke)
                drawLine(
                    color,
                    center - Offset(4.dp.toPx(), 3.dp.toPx()),
                    center + Offset(4.dp.toPx(), 3.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    center - Offset(4.dp.toPx(), -3.dp.toPx()),
                    center + Offset(4.dp.toPx(), -3.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color,
                    center - Offset(0f, 6.dp.toPx()),
                    center + Offset(0f, 6.dp.toPx()),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u501f\u94b1") -> {
                drawRoundRect(
                    color,
                    Offset(center.x - 10.dp.toPx(), center.y - 7.dp.toPx()),
                    androidx.compose.ui.geometry.Size(20.dp.toPx(), 14.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawCircle(color, 3.5.dp.toPx(), center, style = stroke)
                drawCircle(color, 1.dp.toPx(), center - Offset(7.dp.toPx(), 0f))
                drawCircle(color, 1.dp.toPx(), center + Offset(7.dp.toPx(), 0f))
            }
            label.contains("\u53d1\u73b0") || label.contains("\u5e7f\u573a") ||
                label.contains("\u641c\u7d22") || label.contains("\u96f7\u8fbe") -> {
                drawCircle(color, 8.dp.toPx(), center - Offset(1.dp.toPx(), 1.dp.toPx()), style = stroke)
                drawLine(
                    color,
                    center + Offset(5.dp.toPx(), 5.dp.toPx()),
                    center + Offset(10.dp.toPx(), 10.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            label.contains("\u6211") || label.contains("\u4e2a\u4eba") -> {
                drawCircle(color, 4.dp.toPx(), center - Offset(0f, 5.dp.toPx()), style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 8.dp.toPx(), center.y + 9.dp.toPx())
                    quadraticTo(center.x, center.y, center.x + 8.dp.toPx(), center.y + 9.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u6d88\u606f") || label.contains("\u804a\u5929") ||
                label.contains("\u6536\u4ef6\u7bb1") -> {
                val rect = Rect(
                    center.x - 9.dp.toPx(), center.y - 7.dp.toPx(),
                    center.x + 9.dp.toPx(), center.y + 6.dp.toPx()
                )
                drawRoundRect(
                    color, rect.topLeft, rect.size,
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = stroke
                )
                drawPath(Path().apply {
                    moveTo(center.x - 3.dp.toPx(), center.y + 6.dp.toPx())
                    lineTo(center.x - 6.dp.toPx(), center.y + 10.dp.toPx())
                    lineTo(center.x + 1.dp.toPx(), center.y + 6.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u8054\u7cfb") || label.contains("\u793e\u533a") -> {
                drawCircle(color, 4.dp.toPx(), center - Offset(0f, 5.dp.toPx()), style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 8.dp.toPx(), center.y + 9.dp.toPx())
                    quadraticTo(center.x, center.y, center.x + 8.dp.toPx(), center.y + 9.dp.toPx())
                }, color, style = stroke)
            }
            label.contains("\u5373\u4eab") || label.contains("\u4f1a\u5458\u5361") -> {
                drawCircle(color, 9.dp.toPx(), center, style = stroke)
                drawLine(color, center - Offset(5.dp.toPx(), 0f), center + Offset(5.dp.toPx(), 0f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, center - Offset(0f, 5.dp.toPx()), center + Offset(0f, 5.dp.toPx()), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            label.contains("\u9891\u9053") || label.contains("\u5e7f\u64ad") ||
                label.contains("\u7acb\u5373\u8046\u542c") || label.contains("\u8fd0\u52a8") -> {
                drawCircle(color, 9.dp.toPx(), center, style = stroke)
                drawPath(Path().apply {
                    moveTo(center.x - 3.dp.toPx(), center.y + 4.dp.toPx())
                    lineTo(center.x + 2.dp.toPx(), center.y - 5.dp.toPx())
                    lineTo(center.x + 4.dp.toPx(), center.y - 1.dp.toPx())
                    close()
                }, color, style = stroke)
            }
            else -> drawPath(Path().apply {
                moveTo(center.x, center.y - 10.dp.toPx())
                lineTo(center.x + 2.5.dp.toPx(), center.y - 2.5.dp.toPx())
                lineTo(center.x + 10.dp.toPx(), center.y)
                lineTo(center.x + 2.5.dp.toPx(), center.y + 2.5.dp.toPx())
                lineTo(center.x, center.y + 10.dp.toPx())
                lineTo(center.x - 2.5.dp.toPx(), center.y + 2.5.dp.toPx())
                lineTo(center.x - 10.dp.toPx(), center.y)
                lineTo(center.x - 2.5.dp.toPx(), center.y - 2.5.dp.toPx())
                close()
            }, color, style = stroke)
        }
    }
}
