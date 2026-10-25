// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.depthwallpaper

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.os4.systemuihook.AppList
import btm.m.os4.systemuihook.AppPage
import btm.m.os4.systemuihook.GlassDialogButton
import btm.m.os4.systemuihook.OverlayDropdownPreference
import btm.m.os4.systemuihook.WindowDialog
import btm.m.os4.systemuihook.tr
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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

private sealed interface LabGeneration {
    data object Idle : LabGeneration
    data class Running(val progress: Float) : LabGeneration
    data class Done(val projectId: String) : LabGeneration
    data class Failed(val message: String) : LabGeneration
}

@Composable
fun DepthWallpaperLabPage(
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenPreview: (String) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DepthWallpaperStore(context) }
    val settingsStore = remember(context) { DepthWallpaperSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.settings) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var generation by remember { mutableStateOf<LabGeneration>(LabGeneration.Idle) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var generationToken by remember { mutableIntStateOf(0) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var historyCount by remember { mutableIntStateOf(store.projects().size) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val generating = generation is LabGeneration.Running
    val resultId = (generation as? LabGeneration.Done)?.projectId
    val progress = (generation as? LabGeneration.Running)?.progress ?: 0f

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
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        generationToken += 1
        val token = generationToken
        val job = scope.launch {
            generation = LabGeneration.Running(.01f)
            try {
                val useMatting = settings.useMatting &&
                    DepthResourceStore.isReady(context, DepthResources.MATTING)
                val result = DepthWallpaperGenerator.generate(context, bitmap, model, useMatting) { value ->
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
                    return@launch
                }
                val project = withContext(Dispatchers.IO) {
                    store.save(bitmap, result.depth, result.layers, model, sourceName)
                }
                result.depth.recycle()
                result.layers.mask.recycle()
                result.layers.background.recycle()
                if (generationToken == token) {
                    generation = LabGeneration.Done(project.id)
                    historyCount = store.projects().size
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
        AppList(padding, scroll, 28) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    ArrowPreference(
                        title = tr("depthHistory", "历史生成"),
                        summary = tr("depthHistoryCount", "%s 个生成记录").format(historyCount),
                        onClick = {
                            historyCount = store.projects().size
                            onOpenHistory()
                        },
                    )
                    OverlayDropdownPreference(
                        title = tr("depthModel", "模型"),
                        items = DepthModel.entries.map { it.displayName },
                        selectedIndex = DepthModel.entries.indexOf(DepthModel.byId(settings.modelId)),
                        enabled = !generating,
                        onSelectedIndexChange = { index ->
                            val model = DepthModel.entries.getOrElse(index) { DepthModel.DEPTH_ANYTHING_V2_SMALL }
                            settingsStore.update { it.copy(modelId = model.id) }
                            settings = settingsStore.settings
                        },
                    )
                    ArrowPreference(
                        title = tr("depthModelDownloads", "资源下载"),
                        summary = tr("depthModelDownloadsSummary", "下载深度模型、抠图模型与 SF Pro 字体"),
                        onClick = onOpenDownloads,
                    )
                    OverlayDropdownPreference(
                        title = tr("depthRenderMode", "渲染模式"),
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { pickImage.launch(arrayOf("image/*")) },
                            modifier = Modifier.weight(1f),
                            enabled = !generating,
                            cornerRadius = 50.dp,
                            colors = ButtonDefaults.buttonColorsPrimary(),
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
                }
            }
            item {
                DepthSourceCard(
                    bitmap = source,
                    generating = generating,
                    progress = progress,
                    resultId = resultId,
                    onOpenPreview = onOpenPreview,
                )
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
private fun DepthSourceCard(
    bitmap: Bitmap?,
    generating: Boolean,
    progress: Float,
    resultId: String?,
    onOpenPreview: (String) -> Unit,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val ratio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (2f / 3f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(0.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val targetHeight = maxWidth / ratio.coerceIn(.3f, 4f)
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(460, easing = FastOutSlowInEasing),
                label = "depthSourceCardHeight",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
                    .clickable(enabled = resultId != null && !generating) {
                        resultId?.let(onOpenPreview)
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
                            tr("depthSourceEmptyTitle", "还没有图片"),
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
    back: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DepthWallpaperStore(context) }
    var projects by remember { mutableStateOf(store.projects()) }
    var pendingDelete by remember { mutableStateOf<DepthWallpaperProject?>(null) }
    AppPage(tr("depthHistory", "历史生成"), back) { padding, scroll ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
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
            AppList(padding, scroll, 28) {
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

private sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val downloaded: Long, val total: Long, val speed: Long) : ModelDownloadState
    data object Done : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

@Composable
fun DepthWallpaperDownloadsPage(
    back: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember(context) { DepthWallpaperSettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.settings) }
    val states = remember {
        mutableStateMapOf<String, ModelDownloadState>().apply {
            DepthModel.entries.forEach { model ->
                put(model.id, if (DepthModelStore.isReady(context, model)) ModelDownloadState.Done else ModelDownloadState.Idle)
            }
            listOf(DepthResources.MATTING).forEach { resource ->
                put(resource.id, if (DepthResourceStore.isReady(context, resource)) ModelDownloadState.Done else ModelDownloadState.Idle)
            }
        }
    }
    val jobs = remember { mutableStateMapOf<String, Job>() }

    fun update(transform: (DepthWallpaperSettings) -> DepthWallpaperSettings) {
        settingsStore.update(transform)
        settings = settingsStore.settings
    }

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

    AppPage(tr("depthModelDownloads", "资源下载"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(
                        tr("depthDownloadsIntro", "深度模型、抠图模型与 SF Pro 字体按需下载，均在本机离线使用。资源来自项目 GitHub Pages，校验通过前保持禁用。"),
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
            item {
                ResourceCard(
                    title = tr("depthMattingResource", "轻量抠图模型"),
                    summary = tr("depthMattingResourceSummary", "U2Netp · 4.4 MB，为主体明确的照片提供蒙版；透明立绘仍优先使用 Alpha。"),
                    sizeText = formatBytes(DepthResources.MATTING.sizeBytes),
                    state = states[DepthResources.MATTING.id] ?: ModelDownloadState.Idle,
                    downloadLabel = tr("depthDownloadResource", "下载"),
                    deleteLabel = tr("depthDeleteResource", "删除"),
                    onDownload = { startResourceDownload(DepthResources.MATTING) },
                    onCancel = { jobs[DepthResources.MATTING.id]?.cancel() },
                    onDelete = {
                        DepthResourceStore.delete(context, DepthResources.MATTING)
                        states[DepthResources.MATTING.id] = ModelDownloadState.Idle
                        update { it.copy(useMatting = false) }
                    },
                ) {
                    if (states[DepthResources.MATTING.id] == ModelDownloadState.Done) {
                        SwitchPreference(
                            title = tr("depthUseMatting", "使用抠图模型"),
                            summary = tr("depthUseMattingSummary", "生成时用抠图模型提取主体，未开启则使用深度派生蒙版"),
                            checked = settings.useMatting,
                            onCheckedChange = { checked -> update { it.copy(useMatting = checked) } },
                        )
                    }
                }
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
    content: @Composable ColumnScope.() -> Unit = {},
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
            content()
        }
    }
}

private class DepthPreviewData(
    val original: Bitmap?,
    val depth: Bitmap?,
    val mask: Bitmap?,
    val background: Bitmap?,
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
    var subjectDepth by remember { mutableStateOf(.7f) }
    var hasLayers by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }
    val tiltState = rememberSpatialTilt()
    val drag = remember { mutableStateOf(Offset.Zero) }
    val strength = .78f
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
            DepthPreviewData(
                original = store.loadOriginal(id),
                depth = store.loadDepth(id),
                mask = if (layered) store.loadMask(id) else null,
                background = if (layered) store.loadBackground(id) else null,
                subjectDepth = project?.subjectDepth ?: .7f,
                hasLayers = layered,
            )
        }
        original = loaded.original
        depth = loaded.depth
        mask = loaded.mask
        background = loaded.background
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
                .layerBackdrop(backdrop)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        val next = Offset(
                            drag.value.x + amount.x / size.width * 2.4f,
                            drag.value.y + amount.y / size.height * 2.4f,
                        )
                        drag.value = Offset(next.x.coerceIn(-1.5f, 1.5f), next.y.coerceIn(-1.5f, 1.5f))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            drag.value = Offset.Zero
                            tiltState.reset()
                        },
                    )
                },
        ) {
            val image = original
            val depthMap = depth
            val maskMap = mask
            val backgroundMap = background
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
                mode == DepthRenderMode.FINAL && maskMap != null && backgroundMap != null -> LayeredSpatialCanvas(
                    image = image,
                    background = backgroundMap,
                    depth = depthMap,
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

        Box(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp, start = 12.dp),
        ) {
            SpatialGlassBackButton(backdrop, back)
        }

        AnimatedVisibility(
            visible = hintVisible && interactive,
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
                    if (tiltState.hasSensor) {
                        tr("depthPreviewHint", "转动手机体验景深与 3D 效果 · 双击重置视角")
                    } else {
                        tr("depthPreviewHintNoSensor", "拖动画面体验空间效果 · 双击重置视角")
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
