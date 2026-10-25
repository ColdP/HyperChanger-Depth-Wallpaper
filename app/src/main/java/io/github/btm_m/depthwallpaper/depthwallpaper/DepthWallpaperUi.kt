// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package io.github.btm_m.depthwallpaper.depthwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.btm_m.depthwallpaper.AppList
import io.github.btm_m.depthwallpaper.AppPage
import io.github.btm_m.depthwallpaper.GlassDialogButton
import io.github.btm_m.depthwallpaper.OverlayDropdownPreference
import io.github.btm_m.depthwallpaper.WindowDialog
import io.github.btm_m.depthwallpaper.tr
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Carries the project that the full screen preview page should open. */
object DepthWallpaperPreviewRequest {
    var projectId: String? = null
}

internal sealed interface LabGeneration {
    data object Idle : LabGeneration
    data class Running(val progress: Float) : LabGeneration
    data class Done(val projectId: String) : LabGeneration
    data class Failed(val message: String) : LabGeneration
}

/** Owned by the app shell so tab changes do not discard the current creation or its job. */
internal class DepthWallpaperLabState {
    var source by mutableStateOf<Bitmap?>(null)
    var sourceName by mutableStateOf("")
    var generation by mutableStateOf<LabGeneration>(LabGeneration.Idle)
    var generationJob: Job? = null
    var generationToken = 0
    val latestProjectId: String?
        get() = (generation as? LabGeneration.Done)?.projectId
}

@Composable
internal fun DepthWallpaperLabPage(
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPreview: (String) -> Unit,
    back: (() -> Unit)? = null,
    state: DepthWallpaperLabState = remember { DepthWallpaperLabState() },
    scope: CoroutineScope = rememberCoroutineScope(),
    active: Boolean = true,
) {
    val context = LocalContext.current
    val store = remember(context) { DepthWallpaperStore(context) }
    val settingsStore = remember(context) { DepthWallpaperSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.settings) }
    var source by state::source
    var sourceName by state::sourceName
    var generation by state::generation
    var generationJob by state::generationJob
    var generationToken by state::generationToken
    var showCancelDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val generating = generation is LabGeneration.Running
    val resultId = (generation as? LabGeneration.Done)?.projectId
    val progress = (generation as? LabGeneration.Running)?.progress ?: 0f

    LaunchedEffect(active) {
        if (active) {
            settings = settingsStore.settings
            val completed = generation as? LabGeneration.Done
            if (completed != null && store.project(completed.projectId) == null) {
                generation = LabGeneration.Idle
            }
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = decodeDepthSourceBitmap(context, uri)
            if (decoded == null) {
                toast(tr("depth_image_invalid", "无法读取图片"))
                return@launch
            }
            source = decoded
            sourceName = queryDisplayName(context, uri)
            generation = LabGeneration.Idle
        }
    }

    fun startGeneration() {
        val bitmap = source ?: return
        if (generation is LabGeneration.Running) return
        val model = DepthModel.byId(settings.modelId)
        if (!DepthModelStore.isReady(context, model)) {
            toast(tr("depth_model_missing", "请先下载所选模型"))
            onOpenDownloads()
            return
        }
        val mattingModel = MattingModel.byId(settings.mattingModelId)
        if (!DepthResourceStore.isReady(context, mattingModel.resource)) {
            toast(tr("depthMattingModelMissing", "请先在模型下载中下载所选抠图模型"))
            onOpenDownloads()
            return
        }
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        generationToken += 1
        val token = generationToken
        val job = scope.launch {
            generation = LabGeneration.Running(.01f)
            try {
                val result = DepthWallpaperGenerator.generate(context, bitmap, model, useMatting = true, mattingModel = mattingModel) { value ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generationToken == token && generation is LabGeneration.Running) {
                            generation = LabGeneration.Running(value)
                        }
                    }
                }
                if (generationToken != token) {
                    result.depth.recycle()
                    result.layers.mask.recycle()
                    result.layers.background.recycle()
                    result.layers.backgroundDepth.recycle()
                    return@launch
                }
                val project = withContext(Dispatchers.IO) {
                    store.save(bitmap, result.depth, result.layers, model, sourceName)
                }
                result.depth.recycle()
                result.layers.mask.recycle()
                result.layers.background.recycle()
                result.layers.backgroundDepth.recycle()
                if (generationToken == token) {
                    generation = LabGeneration.Done(project.id)
                }
            } catch (cancelled: CancellationException) {
                if (generationToken == token) generation = LabGeneration.Idle
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.e("DepthWallpaperLab", "generation failed", error)
                if (generationToken == token) {
                    generation = LabGeneration.Failed(error.message ?: "generation_failed")
                    toast(
                        tr("depth_generation_failed", "生成失败") +
                            (error.message?.take(120)?.let { ": $it" } ?: ""),
                    )
                }
            }
        }
        generationJob = job
    }

    fun cancelGeneration() {
        generationToken += 1
        generationJob?.cancel()
        generationJob = null
        generation = LabGeneration.Idle
    }

    AppPage(tr("depthWallpaperLab", "空间壁纸实验室"), back) { padding, scroll ->
        AppList(padding, scroll, if (back == null) 112 else 28) {
            item {
                DepthLabSectionTitle("01", tr("depthHomeSource", "选择画面"))
            }
            item {
                DepthSourceCard(
                    bitmap = source,
                    generating = generating,
                    progress = progress,
                    resultId = resultId,
                    onOpenPreview = onOpenPreview,
                    onPickSource = { pickImage.launch(arrayOf("image/*")) },
                )
            }
            if (sourceName.isNotBlank()) {
                item {
                    Text(
                        sourceName,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                DepthLabSectionTitle("02", tr("depthHomeOptions", "生成选项"))
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    OverlayDropdownPreference(
                        title = tr("depthDepthModel", "深度模型"),
                        items = DepthModel.entries.map {
                            if (DepthModelStore.isReady(context, it)) it.displayName
                            else it.displayName + " · " + tr("depthNotDownloaded", "未下载")
                        },
                        selectedIndex = DepthModel.entries.indexOf(DepthModel.byId(settings.modelId)),
                        enabled = !generating,
                        onSelectedIndexChange = { index ->
                            val model = DepthModel.entries.getOrElse(index) { DepthModel.DEPTH_ANYTHING_V2_SMALL }
                            settingsStore.update { it.copy(modelId = model.id) }
                            settings = settingsStore.settings
                        },
                    )
                    OverlayDropdownPreference(
                        title = tr("depthMattingModel", "抠图模型"),
                        items = MattingModel.entries.map {
                            if (DepthResourceStore.isReady(context, it.resource)) it.displayName
                            else it.displayName + " · " + tr("depthNotDownloaded", "未下载")
                        },
                        selectedIndex = MattingModel.entries.indexOf(MattingModel.byId(settings.mattingModelId)),
                        enabled = !generating,
                        onSelectedIndexChange = { index ->
                            val matting = MattingModel.entries.getOrElse(index) { MattingModel.U2NETP }
                            settingsStore.update {
                                it.copy(mattingModelId = matting.id)
                            }
                            settings = settingsStore.settings
                        },
                    )
                    OverlayDropdownPreference(
                        title = tr("depthRenderMode", "渲染模式"),
                        enabled = !generating,
                        items = listOf(
                            tr("depthRenderOriginal", "原图直通"),
                            tr("depthRenderDepth", "深度图查看"),
                            tr("depthRenderParallax", "纯视差"),
                            tr("depthRenderBokeh", "纯景深"),
                            tr("depthRenderFinal", "最终合成"),
                        ),
                        selectedIndex = settings.renderMode.coerceIn(0, DepthRenderMode.entries.lastIndex),
                        onSelectedIndexChange = { index ->
                            settingsStore.update { it.copy(renderMode = index) }
                            settings = settingsStore.settings
                        },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { pickImage.launch(arrayOf("image/*")) },
                            modifier = Modifier.weight(1f),
                            enabled = !generating,
                            cornerRadius = 50.dp,
                        ) {
                            Text(tr("depthPickImage", "选择图片"), fontSize = 15.sp)
                        }
                        Button(
                            onClick = {
                                source = null
                                sourceName = ""
                                generation = LabGeneration.Idle
                            },
                            modifier = Modifier.weight(1f),
                            enabled = source != null && !generating,
                            cornerRadius = 50.dp,
                        ) {
                            Text(tr("depthClearImage", "清除图片"), fontSize = 15.sp)
                        }
                    }
                    Button(
                        onClick = {
                            if (generating) showCancelDialog = true else startGeneration()
                        },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                        enabled = generating || source != null,
                        cornerRadius = 50.dp,
                        colors = if (generating) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            when {
                                generating -> tr("depthCancelGeneration", "取消生成")
                                resultId != null -> tr("depthRegenerate", "重新生成空间效果")
                                else -> tr("depthGenerate", "生成空间效果")
                            },
                            fontSize = 15.sp,
                        )
                    }
                    if (resultId != null && !generating) {
                        Button(
                            onClick = { onOpenPreview(resultId) },
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            cornerRadius = 50.dp,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(tr("depthHomePreview", "查看空间效果"), fontSize = 15.sp)
                        }
                    }
                    if (generation is LabGeneration.Failed) {
                        Text(
                            tr("depth_generation_failed", "生成失败") + ": " +
                                (generation as LabGeneration.Failed).message.take(160),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = ComposeColor(0xFFE05353),
                        )
                    }
                }
            }
            item {
                Text(
                    tr("depthLabHint", "空间效果仅在应用内预览，暂不会写入系统壁纸。"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (showCancelDialog) {
        WindowDialog(show = true, onDismissRequest = { showCancelDialog = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("depthCancelTitle", "取消生成？"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    tr("depthCancelMessage", "当前生成进度将丢失，确定要取消吗？"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ showCancelDialog = false }, Modifier.weight(1f)) {
                        Text(tr("depthContinue", "继续生成"))
                    }
                    GlassDialogButton(
                        onClick = {
                            showCancelDialog = false
                            cancelGeneration()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(tr("depthConfirmCancel", "取消生成"))
                    }
                }
            }
        }
    }
}

@Composable
private fun DepthLabSectionTitle(number: String, title: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
        Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DepthSourceCard(
    bitmap: Bitmap?,
    generating: Boolean,
    progress: Float,
    resultId: String?,
    onOpenPreview: (String) -> Unit,
    onPickSource: () -> Unit,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val ratio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (2f / 3f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val targetHeight = if (bitmap == null) 240.dp else (maxWidth / ratio.coerceIn(.3f, 4f)).coerceIn(220.dp, 440.dp)
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(460, easing = FastOutSlowInEasing),
                label = "depthSourceCardHeight",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
                    .clickable(enabled = !generating) {
                        if (resultId != null) onOpenPreview(resultId) else onPickSource()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "+",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Text(
                            tr("depthHomePickSource", "点此选择图片"),
                            style = MiuixTheme.textStyles.title4,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            tr("depthSourceEmptyHint", "选择一张图片，开始生成属于你的空间壁纸。"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (generating) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(if (dark) ComposeColor.Black.copy(alpha = .58f) else ComposeColor.White.copy(alpha = .74f)),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tr("depthGenerating", "生成中"),
                            style = MiuixTheme.textStyles.title3,
                            color = if (dark) ComposeColor.White else ComposeColor.Black,
                        )
                        Text(
                            tr("depthGeneratingProgress", "进度 %s%%").format((progress * 100f).roundToInt().coerceIn(0, 100)),
                            style = MiuixTheme.textStyles.body1,
                            color = (if (dark) ComposeColor.White else ComposeColor.Black).copy(alpha = .82f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DepthWallpaperHistoryPage(
    onOpenPreview: (String) -> Unit,
    back: (() -> Unit)? = null,
    latestProjectId: String? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { DepthWallpaperStore(context) }
    var projects by remember { mutableStateOf(store.projects()) }
    var pendingDelete by remember { mutableStateOf<DepthWallpaperProject?>(null) }
    LaunchedEffect(latestProjectId) {
        projects = store.projects()
    }
    AppPage(tr("depthHistory", "历史生成"), back) { padding, scroll ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding)
                    .padding(start = 32.dp, end = 32.dp, bottom = if (back == null) 112.dp else 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("depthHistoryEmptyTitle", "还没有生成记录"),
                        style = MiuixTheme.textStyles.title3,
                    )
                    Text(
                        tr("depthHistoryEmptyHint", "在空间壁纸实验室生成第一张空间壁纸后，记录会显示在这里。"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            AppList(padding, scroll, if (back == null) 112 else 28) {
                items(projects, key = { it.id }) { project ->
                    DepthHistoryCard(
                        project = project,
                        store = store,
                        onClick = { onOpenPreview(project.id) },
                        onDelete = { pendingDelete = project },
                    )
                }
            }
        }
    }
    pendingDelete?.let { project ->
        WindowDialog(show = true, onDismissRequest = { pendingDelete = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("depthDeleteTitle", "删除这条记录？"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    tr("depthDeleteMessage", "原图和深度数据都会被删除，无法恢复。"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ pendingDelete = null }, Modifier.weight(1f)) {
                        Text(tr("depthKeep", "保留"))
                    }
                    GlassDialogButton(
                        onClick = {
                            store.delete(project.id)
                            projects = store.projects()
                            pendingDelete = null
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(tr("depthDelete", "删除"))
                    }
                }
            }
        }
    }
}

@Composable
private fun DepthHistoryCard(
    project: DepthWallpaperProject,
    store: DepthWallpaperStore,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(null, project.id) {
        value = withContext(Dispatchers.IO) {
            store.loadThumbnail(project.id)?.asImageBitmap()
        }
    }
    val time = remember(project.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(project.createdAt))
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.5.dp)).clickable(onClick = onClick),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(196.dp).background(MiuixTheme.colorScheme.surfaceVariant)) {
            thumbnail?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    project.sourceName.ifBlank { tr("depthUntitled", "空间壁纸") },
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${project.model.displayName} · $time",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    MiuixIcons.Regular.Delete,
                    tr("depthDelete", "删除"),
                    Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
                )
            }
        }
    }
}

internal sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val downloaded: Long, val total: Long, val speed: Long) : ModelDownloadState
    data object Done : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

internal class DepthWallpaperDownloadsState(context: Context) {
    val states = mutableStateMapOf<String, ModelDownloadState>().apply {
        DepthModel.entries.forEach { model ->
            put(model.id, if (DepthModelStore.isReady(context, model)) ModelDownloadState.Done else ModelDownloadState.Idle)
        }
        MattingModel.entries.forEach { model ->
            put(model.id, if (DepthResourceStore.isReady(context, model.resource)) ModelDownloadState.Done else ModelDownloadState.Idle)
        }
    }
    val jobs = mutableStateMapOf<String, Job>()
}

@Composable
internal fun DepthWallpaperDownloadsPage(
    back: (() -> Unit)? = null,
    state: DepthWallpaperDownloadsState? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    val downloadState = state ?: remember(context) { DepthWallpaperDownloadsState(context) }
    val states = downloadState.states
    val jobs = downloadState.jobs


    fun startModelDownload(model: DepthModel) {
        if (jobs[model.id]?.isActive == true) return
        states[model.id] = ModelDownloadState.Downloading(
            DepthModelStore.downloadedBytes(context, model),
            model.sizeBytes,
            0L,
        )
        val job = scope.launch {
            try {
                DepthModelStore.download(context, model) { downloaded, total, speed ->
                    scope.launch(Dispatchers.Main.immediate) {
                        states[model.id] = ModelDownloadState.Downloading(downloaded, total, speed)
                    }
                }
                states[model.id] = ModelDownloadState.Done
            } catch (cancelled: CancellationException) {
                states[model.id] = ModelDownloadState.Idle
                throw cancelled
            } catch (error: Throwable) {
                states[model.id] = ModelDownloadState.Failed(error.message ?: tr("depthDownloadFailed", "下载失败"))
            } finally {
                jobs.remove(model.id)
            }
        }
        jobs[model.id] = job
    }

    fun startResourceDownload(resource: DepthResource) {
        if (jobs[resource.id]?.isActive == true) return
        states[resource.id] = ModelDownloadState.Downloading(
            DepthResourceStore.downloadedBytes(context, resource),
            resource.sizeBytes,
            0L,
        )
        val job = scope.launch {
            try {
                DepthResourceStore.download(context, resource) { downloaded, total, speed ->
                    scope.launch(Dispatchers.Main.immediate) {
                        states[resource.id] = ModelDownloadState.Downloading(downloaded, total, speed)
                    }
                }
                states[resource.id] = ModelDownloadState.Done
            } catch (cancelled: CancellationException) {
                states[resource.id] = ModelDownloadState.Idle
                throw cancelled
            } catch (error: Throwable) {
                states[resource.id] = ModelDownloadState.Failed(error.message ?: tr("depthDownloadFailed", "下载失败"))
            } finally {
                jobs.remove(resource.id)
            }
        }
        jobs[resource.id] = job
    }

    AppPage(tr("depthModelDownloadsTab", "模型下载"), back) { padding, scroll ->
        AppList(padding, scroll, if (back == null) 112 else 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(
                        tr("depthModelDownloadsIntro", "深度模型与抠图模型按需下载，生成时在本机离线使用。下载完成并通过校验后即可使用。"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            items(DepthModel.entries.toList()) { model ->
                ResourceCard(
                    title = model.displayName,
                    summary = when (model) {
                        DepthModel.DEPTH_ANYTHING_V2_SMALL ->
                            tr("depthModelDaSummary", "细节与边缘更稳，适合人像、建筑和复杂场景。")
                        DepthModel.MIDAS_MOBILE ->
                            tr("depthModelMidasSummary", "体积更小、速度更快，适合快速预览。")
                        else ->
                            tr("depthModelDa3Summary", "质量最佳，体积较大，生成耗时明显更长，建议在 Wi-Fi 下下载。")
                    },
                    sizeText = formatBytes(model.sizeBytes),
                    state = states[model.id] ?: ModelDownloadState.Idle,
                    downloadLabel = tr("depthDownloadModel", "下载模型"),
                    deleteLabel = tr("depthDeleteModel", "删除模型"),
                    onDownload = { startModelDownload(model) },
                    onCancel = { jobs[model.id]?.cancel() },
                    onDelete = {
                        DepthModelStore.delete(context, model)
                        states[model.id] = ModelDownloadState.Idle
                    },
                )
            }
            items(MattingModel.entries.toList()) { matting ->
                val resource = matting.resource
                ResourceCard(
                    title = matting.displayName,
                    summary = when (matting) {
                        MattingModel.U2NETP -> tr("depthMattingU2netSummary", "原有轻量模型，适合快速提取主体。透明图片优先使用 Alpha。")
                        MattingModel.ISNET_INT8 -> tr("depthMattingIsnetSummary", "1024 分辨率，兼顾主体边缘与生成速度。透明图片优先使用 Alpha。")
                        MattingModel.BIREFNET_LITE_INT8 -> tr("depthMattingBirefnetSummary", "适合复杂轮廓和细节，内存占用与生成耗时较高。透明图片优先使用 Alpha。")
                    },
                    sizeText = formatBytes(resource.sizeBytes),
                    state = states[resource.id] ?: ModelDownloadState.Idle,
                    downloadLabel = tr("depthDownloadResource", "下载"),
                    deleteLabel = tr("depthDeleteResource", "删除"),
                    onDownload = { startResourceDownload(resource) },
                    onCancel = { jobs[resource.id]?.cancel() },
                    onDelete = {
                        DepthResourceStore.delete(context, resource)
                        states[resource.id] = ModelDownloadState.Idle
                    },
                )
            }
            item {
                Text(
                    tr("depthDownloadsNote", "已下载的资源可随时删除；删除模型不会影响已经生成的历史记录。"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ResourceCard(
    title: String,
    summary: String,
    sizeText: String,
    state: ModelDownloadState,
    downloadLabel: String,
    deleteLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = MiuixTheme.textStyles.title4)
                        Text(
                            summary,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        sizeText,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                when (state) {
                    is ModelDownloadState.Downloading -> {
                        val fraction = if (state.total > 0) {
                            (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                        } else 0f
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                    .background(MiuixTheme.colorScheme.primary),
                            )
                        }
                        Text(
                            tr("depthDownloading", "正在下载 %s / %s · %s/s")
                                .format(
                                    formatBytes(state.downloaded),
                                    formatBytes(state.total),
                                    formatBytes(state.speed),
                                ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Button(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 50.dp,
                        ) {
                            Text(tr("depthCancelDownload", "取消下载"), fontSize = 15.sp)
                        }
                    }
                    ModelDownloadState.Done -> {
                        Text(
                            tr("depthDownloaded", "已下载并通过校验"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Button(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 50.dp,
                        ) {
                            Text(deleteLabel, fontSize = 15.sp)
                        }
                    }
                    is ModelDownloadState.Failed -> {
                        Text(
                            tr("depthDownloadFailedDetail", "下载失败：%s").format(state.message),
                            style = MiuixTheme.textStyles.body2,
                            color = ComposeColor(0xFFE05353),
                        )
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 50.dp,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(tr("depthRetryDownload", "重试下载"), fontSize = 15.sp)
                        }
                    }
                    ModelDownloadState.Idle -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 50.dp,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(downloadLabel, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

private class DepthPreviewData(
    val original: Bitmap?,
    val depth: Bitmap?,
    val mask: Bitmap?,
    val background: Bitmap?,
    val backgroundDepth: Bitmap?,
    val subjectDepth: Float,
    val hasLayers: Boolean,
)

@Composable
fun DepthWallpaperPreviewPage(back: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { DepthWallpaperStore(context) }
    val settings = remember(context) { DepthWallpaperSettingsStore(context).settings }
    val projectId = DepthWallpaperPreviewRequest.projectId
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var depth by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var background by remember { mutableStateOf<Bitmap?>(null) }
    var backgroundDepth by remember { mutableStateOf<Bitmap?>(null) }
    var subjectDepth by remember { mutableStateOf(.7f) }
    var hasLayers by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }
    val tiltState = rememberSpatialTilt()
    val drag = remember(projectId) { mutableStateOf(Offset.Zero) }
    var zoomTransform by remember(projectId) { mutableStateOf(PreviewZoomTransform()) }
    val strength = (settings.parallaxStrength / 100f).coerceIn(.2f, 1f)
    val backdrop = rememberLayerBackdrop()

    LaunchedEffect(projectId) {
        val id = projectId
        if (id == null) {
            failed = true
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val project = store.project(id)
            val layered = project?.hasLayers == true
            val original = store.loadOriginal(id)
            val depth = store.loadDepth(id)
            val mask = if (layered) store.loadMask(id) else null
            val plate = if (original != null && depth != null && mask != null) {
                store.loadBackgroundPlate(id, original, mask, depth)
            } else null
            DepthPreviewData(
                original = original,
                depth = depth,
                mask = mask,
                background = plate?.image,
                backgroundDepth = plate?.depth,
                subjectDepth = project?.subjectDepth ?: .7f,
                hasLayers = layered,
            )
        }
        original = loaded.original
        depth = loaded.depth
        mask = loaded.mask
        background = loaded.background
        backgroundDepth = loaded.backgroundDepth
        subjectDepth = loaded.subjectDepth
        hasLayers = loaded.hasLayers && loaded.mask != null && loaded.background != null
        failed = loaded.original == null || loaded.depth == null
    }
    LaunchedEffect(Unit) {
        delay(4200)
        hintVisible = false
    }

    val tiltProvider = remember(tiltState, drag) {
        { Offset(tiltState.tilt.x + drag.value.x, tiltState.tilt.y + drag.value.y) }
    }
    val requestedMode = settings.mode
    val mode = if (requestedMode == DepthRenderMode.FINAL && !hasLayers) {
        DepthRenderMode.PARALLAX
    } else {
        requestedMode
    }
    val interactive = mode == DepthRenderMode.PARALLAX ||
        mode == DepthRenderMode.BOKEH ||
        mode == DepthRenderMode.FINAL

    Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .layerBackdrop(backdrop)
                .pointerInput(projectId) {
                    detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                        if (zoom != 1f || zoomTransform.scale > 1f) {
                            zoomTransform = zoomTransform.transform(
                                zoom = zoom,
                                panX = pan.x,
                                panY = pan.y,
                                centroidX = centroid.x,
                                centroidY = centroid.y,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                            )
                        } else {
                            val next = Offset(
                                drag.value.x + pan.x / size.width.coerceAtLeast(1) * 2.4f,
                                drag.value.y + pan.y / size.height.coerceAtLeast(1) * 2.4f,
                            )
                            drag.value = Offset(next.x.coerceIn(-1.5f, 1.5f), next.y.coerceIn(-1.5f, 1.5f))
                        }
                    }
                }
                .pointerInput(projectId) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoomTransform = PreviewZoomTransform()
                            drag.value = Offset.Zero
                            tiltState.reset()
                        },
                    )
                },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoomTransform.scale
                scaleY = zoomTransform.scale
                translationX = zoomTransform.offsetX
                translationY = zoomTransform.offsetY
            }) {
                val image = original
                val depthMap = depth
                val maskMap = mask
                val backgroundMap = background
                val backgroundDepthMap = backgroundDepth
                when {
                    failed -> Text(
                        tr("depthPreviewMissing", "这条记录无法加载"),
                        modifier = Modifier.align(Alignment.Center),
                        color = ComposeColor.White,
                        style = MiuixTheme.textStyles.body1,
                    )
                    image == null || depthMap == null -> Unit
                    mode == DepthRenderMode.ORIGINAL -> Image(
                        image.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    mode == DepthRenderMode.DEPTH -> Image(
                        depthMap.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    mode == DepthRenderMode.BOKEH -> BokehDepthCanvas(
                        image = image,
                        depth = depthMap,
                        modifier = Modifier.fillMaxSize(),
                    )
                    mode == DepthRenderMode.FINAL && maskMap != null && backgroundMap != null && backgroundDepthMap != null -> LayeredSpatialCanvas(
                        image = image,
                        background = backgroundMap,
                        backgroundDepth = backgroundDepthMap,
                        foregroundDepth = depthMap,
                        mask = maskMap,
                        subjectDepth = subjectDepth,
                        tiltProvider = tiltProvider,
                        strength = strength,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> SpatialDepthCanvas(
                        image = image,
                        depth = depthMap,
                        tiltProvider = tiltProvider,
                        strength = strength,
                        tilt3d = mode != DepthRenderMode.PARALLAX,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Box(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp, start = 12.dp),
        ) {
            SpatialGlassBackButton(backdrop, back)
        }

        AnimatedVisibility(
            visible = hintVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(ComposeColor.Black.copy(alpha = .46f))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    if (!interactive) {
                        tr("depthPreviewZoomHint", "双指缩放 · 双击重置")
                    } else if (tiltState.hasSensor) {
                        tr("depthPreviewHint", "转动手机体验空间效果 · 双指缩放 · 双击重置")
                    } else {
                        tr("depthPreviewHintNoSensor", "拖动画面体验空间效果 · 双指缩放 · 双击重置")
                    },
                    color = ComposeColor.White.copy(alpha = .9f),
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
private fun SpatialGlassBackButton(backdrop: Backdrop, onClick: () -> Unit) {
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx())
                    lens(18.dp.toPx(), 44.dp.toPx(), depthEffect = true, chromaticAberration = true)
                },
                highlight = { Highlight.Default.copy(alpha = .62f) },
                shadow = { Shadow.Default.copy(radius = 6.dp, color = ComposeColor.Black, alpha = .3f) },
                innerShadow = null,
                onDrawSurface = { drawCircle(surface.copy(alpha = .58f)) },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            MiuixIcons.Regular.ChevronBackward,
            tr("depthBack", "返回"),
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
        )
    }
}
