package io.github.jaytech64.piastre.spike.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jaytech64.piastre.spike.capture.Capture
import io.github.jaytech64.piastre.spike.capture.CaptureStore
import io.github.jaytech64.piastre.spike.capture.Classifier
import io.github.jaytech64.piastre.spike.capture.NotificationAccess
import io.github.jaytech64.piastre.spike.capture.Sighting
import io.github.jaytech64.piastre.spike.capture.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeApp() {
    val context = LocalContext.current
    val store = remember { CaptureStore.get(context) }
    val captures by store.captures.collectAsState()
    val sightings by store.sightings.collectAsState()
    val verdicts = remember(captures) { Classifier.classify(captures) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportMessage = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(store.exportJson().toByteArray())
                    } ?: error("could not open destination")
                    "Exported ${captures.size} captures"
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Discard the corpus?") },
            text = { Text("Deletes all ${captures.size} captures and every app sighting. Export first if you still need them.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    exportMessage = null
                    confirmClear = false
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Piastre — Spike") },
                actions = {
                    TextButton(onClick = { exportLauncher.launch("piastre-fixtures.json") }) {
                        Text("Export")
                    }
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AccessCard(context, captures.size, captures.firstOrNull()?.postedAtUtcMillis)

            exportMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LedgerSummary(captures.size, verdicts)

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Captures (${captures.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Apps (${sightings.size})") },
                )
            }

            when (selectedTab) {
                0 -> CaptureList(captures, verdicts)
                else -> SightingList(sightings) { packageName, watched ->
                    store.setWatched(packageName, watched)
                }
            }
        }
    }
}

@Composable
private fun AccessCard(context: Context, captureCount: Int, lastCaptureAt: Long?) {
    var granted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = NotificationAccess.isGranted(context) }

    // Access can be granted or revoked entirely outside this app, so the status
    // must be re-read on every resume. Sampling it once lets the card claim we are
    // listening after access was pulled — the exact way a capture app goes silently
    // deaf without telling you.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationAccess.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (granted) "Listening for notifications" else "Notification access is OFF",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (granted) {
                    "$captureCount captured" + (lastCaptureAt?.let { ", last at ${formatTime(it)}" } ?: "")
                } else {
                    "Nothing can be captured until you grant access in Settings."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!granted) {
                FilledTonalButton(
                    onClick = { launcher.launch(NotificationAccess.settingsIntent()) },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Grant notification access")
                }
            } else {
                TextButton(onClick = { NotificationAccess.requestRebind(context) }) {
                    Text("Force rebind")
                }
            }
        }
    }
}

@Composable
private fun CaptureList(captures: List<Capture>, verdicts: List<Verdict>) {
    if (captures.isEmpty()) {
        EmptyState(
            "No captures yet.",
            "Grant access, then pay for something with your phone. Only watched packages are recorded.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(captures.zip(verdicts)) { (capture, verdict) -> CaptureCard(capture, verdict) }
    }
}

@Composable
private fun CaptureCard(capture: Capture, verdict: Verdict) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (verdict is Verdict.Transaction) {
            // The same container as the ledger card above: a row tinted like the
            // verdict is a row the verdict counted.
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(capture.appLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(formatTime(capture.postedAtUtcMillis), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "${capture.packageName} · ${capture.deviceLocale}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerdictBadge(verdict)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Field("title", capture.title)
            Field("text", capture.text)
            Field("bigText", capture.bigText)
            Field("subText", capture.subText)
            Field("template", capture.template?.substringAfterLast('.'))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide raw JSON" else "Show raw JSON")
            }
            if (expanded) {
                Text(
                    capture.rawJson,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SightingList(sightings: List<Sighting>, onWatchChange: (String, Boolean) -> Unit) {
    if (sightings.isEmpty()) {
        EmptyState(
            "No apps seen yet.",
            "Every app that posts a notification appears here — package name and count only, never content.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sightings) { sighting ->
            Card(Modifier.fillMaxWidth().clickable { onWatchChange(sighting.packageName, !sighting.watched) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sighting.appLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            sighting.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${sighting.count} notifications · ${sighting.capturedCount} captured · last ${formatTime(sighting.lastSeenUtcMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = sighting.watched,
                        onCheckedChange = { onWatchChange(sighting.packageName, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun formatTime(epochMillis: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(epochMillis))

private fun money(minor: Long): String = "%,.2f $".format(minor / 100.0)

@Composable
private fun LedgerSummary(captureCount: Int, verdicts: List<Verdict>) {
    if (captureCount == 0) return
    val transactions = verdicts.filterIsInstance<Verdict.Transaction>()
    val total = transactions.sumOf { it.amountMinor }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Parser verdict", style = MaterialTheme.typography.labelSmall)
            Text(
                "$captureCount notifications  ->  ${transactions.size} transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Total ${money(total)} — if this count is higher than the purchases you actually made, a duplicate slipped through.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun VerdictBadge(verdict: Verdict) {
    val (label, color) = when (verdict) {
        is Verdict.Transaction -> "TRANSACTION  ${money(verdict.amountMinor)}" to MaterialTheme.colorScheme.onTertiaryContainer
        is Verdict.Duplicate -> "DUPLICATE  ${money(verdict.amountMinor)} — already counted" to MaterialTheme.colorScheme.onSurfaceVariant
        is Verdict.Review -> "REVIEW  ${verdict.reason}" to MaterialTheme.colorScheme.tertiary
        is Verdict.Ignored -> "IGNORED  ${verdict.reason}" to MaterialTheme.colorScheme.onSurfaceVariant
        Verdict.Unhandled -> "NO PARSER" to MaterialTheme.colorScheme.error
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(top = 6.dp),
    )
}
