package com.example.kmptest.presentation.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kmptest.domain.model.ClockError
import com.example.kmptest.domain.model.ItemStatus
import com.example.kmptest.domain.model.ProcessingMode
import com.example.kmptest.domain.model.QueueItem
import com.example.kmptest.domain.model.Stage
import com.example.kmptest.presentation.theme.GlassButton
import com.example.kmptest.presentation.theme.ContentMaxWidth
import com.example.kmptest.presentation.theme.GlassCard
import com.example.kmptest.presentation.theme.centeredContent
import com.example.kmptest.presentation.theme.GlassButton
import com.example.kmptest.presentation.theme.SectionTitle
import com.example.kmptest.presentation.theme.SuccessGreen
import com.example.kmptest.presentation.i18n.fill
import com.example.kmptest.presentation.i18n.str
import com.example.kmptest.presentation.platform.LocalPlatform
import com.example.kmptest.presentation.resources.Res
import com.example.kmptest.presentation.resources.ic_logo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen(onOpenSettings: () -> Unit, vm: MainViewModel = koinViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val running = ui.queue.running

    fun addVideos() = scope.launch { vm.addVideos(platform.pickVideos()) }
    fun chooseFolder() = scope.launch { platform.pickOutputFolder()?.let(vm::setOutputFolder) }
    fun startClicked() = scope.launch {
        if (!ui.texts.valid || platform.beforeStart()) vm.start()
    }

    val items = ui.queue.items
    val runningIndex = items.indexOfFirst { it.status == ItemStatus.RUNNING }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Header(onOpenSettings) },
        bottomBar = {
            ActionBar(
                running = running,
                progress = ui.queue.overallFraction,
                status = statusLine(items.getOrNull(runningIndex), items.size, runningIndex),
                canStart = items.any { it.status != ItemStatus.DONE },
                onStart = { startClicked() },
                onCancel = vm::cancel,
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
        val side = maxOf(16.dp, (maxWidth - ContentMaxWidth) / 2)  // centres the column on a wide window; the list still scrolls anywhere
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (items.isEmpty()) {
                item { EmptyState(onAdd = { addVideos() }) }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassButton(str("add_videos"), onClick = { addVideos() }, enabled = !running, tonal = true)
                        if (items.any { it.status == ItemStatus.DONE } && items.any { it.status != ItemStatus.DONE }) {
                            TextButton(onClick = vm::clearDone, enabled = !running) { Text(str("clear_done")) }
                        }
                        TextButton(onClick = vm::clear, enabled = !running) { Text(str("clear")) }
                    }
                }
                items(items, key = { it.source.uri }) { item ->
                    VideoCard(item, canRemove = !running, onRemove = { vm.remove(item.source) })
                }
            }
            item {
                OutputFolderCard(
                    folder = ui.settings.outputFolderUri?.takeIf { it.isNotBlank() },
                    suffix = ui.texts.suffix,
                    enabled = !running,
                    onChoose = { chooseFolder() },
                    onDefault = { vm.setOutputFolder(null) },
                    onSuffix = vm::onSuffix,
                    onOpen = { platform.openFolder(ui.settings.outputFolderUri, items.map { it.source }) },
                )
            }
            item { ModeCard(ui.settings.options.mode, enabled = !running, onChange = vm::setMode) }
            item { OptionsCard(ui, enabled = !running, vm) }
        }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), Color.Transparent)),
        ),
    ) {
        Row(
            Modifier.centeredContent().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(Res.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)))
            Spacer(Modifier.width(12.dp))
            Text(str("app_title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = str("settings_title"), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(painterResource(Res.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)))
            Text(str("empty_hint"), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GlassButton(str("add_videos"), onClick = onAdd, tonal = true)
        }
    }
}

@Composable
private fun statusLine(running: QueueItem?, total: Int, index: Int): String {
    if (running == null) return str("ready")
    val p = running.progress
    val step = when (p?.stage) {
        Stage.DETECT -> str("step_detect")
        Stage.RENDER -> str("step_render")
        else -> str("step_analyze")
    }
    return str("status_line")
        .fill("n" to index + 1, "total" to total, "name" to running.source.name, "step" to step, "percent" to ((p?.fraction ?: 0f) * 100).toInt())
}

@Composable
private fun ActionBar(running: Boolean, progress: Float, status: String, canStart: Boolean, onStart: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, shadowElevation = 12.dp) {
        Column(Modifier.centeredContent().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.primaryContainer, strokeCap = StrokeCap.Round)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (running) {
                    GlassButton(str("cancel"), onClick = onCancel, tonal = true)
                } else {
                    GlassButton(str("start"), onClick = onStart, enabled = canStart)
                }
                Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ItemStatus) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ItemStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
            ItemStatus.DONE -> Box(Modifier.size(30.dp).clip(CircleShape).background(SuccessGreen), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            ItemStatus.FAILED -> Box(Modifier.size(30.dp).clip(CircleShape).background(colors.error), contentAlignment = Alignment.Center) {
                Text("!", color = colors.onError, fontWeight = FontWeight.Bold)
            }
            else -> Box(Modifier.size(14.dp).clip(CircleShape).background(colors.outline.copy(alpha = 0.55f)))
        }
    }
}

@Composable
private fun VideoCard(item: QueueItem, canRemove: Boolean, onRemove: () -> Unit) {
    GlassCard {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            StatusBadge(item.status)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.source.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                val (label, color) = when (item.status) {
                    ItemStatus.QUEUED -> str("st_queued") to MaterialTheme.colorScheme.onSurfaceVariant
                    ItemStatus.RUNNING -> str("st_running") to MaterialTheme.colorScheme.primary
                    ItemStatus.DONE -> str("st_done") to SuccessGreen
                    ItemStatus.FAILED -> str("st_failed") to MaterialTheme.colorScheme.error
                    ItemStatus.CANCELLED -> str("st_cancelled") to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
                if (item.status == ItemStatus.RUNNING) {
                    LinearProgressIndicator(progress = { item.progress?.overall ?: 0f }, modifier = Modifier.fillMaxWidth().padding(end = 14.dp), trackColor = MaterialTheme.colorScheme.primaryContainer, strokeCap = StrokeCap.Round)
                }
                val detail = when {
                    item.status == ItemStatus.DONE && item.result != null -> {
                        val r = item.result!!
                        val base = str("r_ok").fill("src" to r.sourceFrames, "unique" to r.uniqueFrames, "secs" to Math.round(r.outputSeconds))
                        val span = r.clockSpanSeconds?.let { " (" + str("r_span").fill("span" to it) + ")" }.orEmpty()
                        "${item.outputName.orEmpty()}\n$base$span"
                    }
                    item.clockError != null -> str(clockErrorKey(item.clockError!!))
                    item.errorMessage != null -> item.errorMessage
                    else -> null
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canRemove) {
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = str("remove"), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun clockErrorKey(e: ClockError): String = when (e) {
    ClockError.NO_REGION -> "err_no_region"
    ClockError.NO_CYCLE -> "err_no_cycle"
    ClockError.TOO_SHORT -> "err_too_short"
    ClockError.NO_DIGITS -> "err_no_digits"
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    GlassCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun OutputFolderCard(
    folder: String?, suffix: String, enabled: Boolean,
    onChoose: () -> Unit, onDefault: () -> Unit, onSuffix: (String) -> Unit, onOpen: () -> Unit,
) {
    val platform = LocalPlatform.current
    SectionCard(str("out_dir")) {
        Text(
            folder?.let(platform::folderLabel) ?: str(platform.defaultOutputTextKey),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(str("browse"), onClick = onChoose, enabled = enabled, tonal = true)
            if (platform.canOpenFolder) GlassButton(str("open_dir"), onClick = onOpen, tonal = true)
            if (folder != null) TextButton(onClick = onDefault, enabled = enabled) { Text(str("use_default")) }
        }
        OutlinedTextField(
            value = suffix,
            onValueChange = onSuffix,
            label = { Text(str("opt_suffix")) },
            singleLine = true,
            enabled = enabled,
            isError = suffix.isEmpty(),
            supportingText = if (suffix.isEmpty()) ({ Text(str("w_suffix_empty")) }) else null,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModeCard(mode: ProcessingMode, enabled: Boolean, onChange: (ProcessingMode) -> Unit) {
    SectionCard(str("mode_group")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((value, text) in listOf(ProcessingMode.PLAIN to "mode_plain", ProcessingMode.CLOCK to "mode_clock")) {
                GlassCard(selected = mode == value, enabled = enabled, onClick = { onChange(value) }) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == value, onClick = null, enabled = enabled, modifier = Modifier.padding(8.dp))
                        Text(str(text), modifier = Modifier.padding(end = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(ui: MainUiState, enabled: Boolean, vm: MainViewModel) {
    SectionCard(str("opt_group")) {
        val bad = ui.showInvalidOptions
        NumberField(str("opt_fps"), ui.texts.fps, enabled, bad && ui.texts.fpsValue == null, false, vm::onFps)
        NumberField(str("opt_threshold"), ui.texts.threshold, enabled, bad && ui.texts.thresholdValue == null, true, vm::onThreshold)
        NumberField(str("opt_crf"), ui.texts.crf, enabled, bad && ui.texts.crfValue == null, false, vm::onCrf)
        Text(str("hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean, error: Boolean, decimal: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
