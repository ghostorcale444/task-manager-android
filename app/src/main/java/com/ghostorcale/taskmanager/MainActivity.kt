package com.ghostorcale.taskmanager

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class AppProc(
    val label: String,
    val packageName: String,
    val icon: Bitmap?,
    val isSystem: Boolean,
    val isRunningBg: Boolean,
    val lastUsed: Long,
    val versionName: String?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TaskManagerTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun TaskManagerTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF00E5C7),
        secondary = Color(0xFFB388FF),
        background = Color(0xFF120A24),
        surface = Color(0xFF1B1035)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(), context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

// Runs off the main thread. Icons are decoded + downscaled ONCE here so
// composables never touch Drawable->Bitmap conversion during recomposition.
suspend fun loadAllApps(context: Context): List<AppProc> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val runningPkgs = try {
        am.runningAppProcesses?.flatMap { it.pkgList.toList() }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    var usageMap = emptyMap<String, Long>()
    if (hasUsageAccess(context)) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 1000L * 60 * 60 * 24 * 14
        usageMap = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
            .associate { it.packageName to it.lastTimeUsed }
    }

    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
    val apps = pm.getInstalledApplications(flags)
    val iconSizePx = 96

    apps.map { ai: ApplicationInfo ->
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }
        val icon: Bitmap? = try {
            val d = pm.getApplicationIcon(ai)
            val bmp = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, iconSizePx, iconSizePx)
            d.draw(canvas)
            bmp
        } catch (e: Exception) { null }
        val versionName = try { pm.getPackageInfo(ai.packageName, 0).versionName } catch (e: Exception) { null }
        AppProc(
            label = label,
            packageName = ai.packageName,
            icon = icon,
            isSystem = isSystem,
            isRunningBg = runningPkgs.contains(ai.packageName),
            lastUsed = usageMap[ai.packageName] ?: 0L,
            versionName = versionName
        )
    }.sortedWith(compareByDescending<AppProc> { it.isRunningBg }.thenByDescending { it.lastUsed })
}

// Cheap poll — just the running-process set, no icon decoding or usage
// stats query. Safe to call every few seconds for a "live" feel.
suspend fun refreshRunningPkgs(context: Context): Set<String> = withContext(Dispatchers.IO) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    try {
        am.runningAppProcesses?.flatMap { it.pkgList.toList() }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }
}

fun setImmersive(view: android.view.View, hide: Boolean) {
    val controller = WindowInsetsControllerCompat(
        (view.context as ComponentActivity).window, view
    )
    if (hide) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

// Writes the given apps to a CSV in the app's cache dir, then opens the
// share sheet via FileProvider so the user can save/send it anywhere.
fun exportListToCsv(context: Context, apps: List<AppProc>) {
    val exportsDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = java.io.File(exportsDir, "task_manager_export_$stamp.csv")

    file.bufferedWriter().use { w ->
        w.write("Label,Package,System,Background,LastUsed,Version\n")
        apps.forEach { app ->
            val lastUsed = if (app.lastUsed > 0)
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(app.lastUsed))
            else ""
            fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            w.write(
                listOf(
                    esc(app.label), esc(app.packageName), app.isSystem, app.isRunningBg,
                    esc(lastUsed), esc(app.versionName ?: "")
                ).joinToString(",") + "\n"
            )
        }
    }

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Export app list"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val view = LocalView.current

    var apps by remember { mutableStateOf<List<AppProc>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppProc?>(null) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var focusMode by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading) {
        if (isLoading) {
            apps = loadAllApps(context)
            isLoading = false
        }
    }

    // Live update: every 4s, refresh just which apps are running in the
    // background. Cheap (no icon decode / usage-stats query), so it doesn't
    // cause the lag the old full-reload approach did.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            if (!isLoading && apps.isNotEmpty()) {
                val running = refreshRunningPkgs(context)
                apps = apps.map { it.copy(isRunningBg = running.contains(it.packageName)) }
            }
        }
    }

    LaunchedEffect(focusMode) { setImmersive(view, focusMode) }

    val filtered by remember(apps, showHidden, query) {
        derivedStateOf {
            apps.filter {
                (showHidden || !it.isSystem) &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF120A24), Color(0xFF1B1035), Color(0xFF241247))
                )
            )
    ) {
        // Static geometric accents — drawn once, no per-frame redraw. This
        // replaces the old animated full-screen blur, which was the main
        // source of lag.
        StaticGeometricAccents()

        Column(modifier = Modifier.fillMaxSize()) {
            if (!focusMode) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lucario", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xFF00E5C7).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF00E5C7))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Live", color = Color(0xFF00E5C7), fontSize = 11.sp)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = { focusMode = true }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "Focus mode", tint = Color.White)
                        }
                        IconButton(onClick = { isLoading = true }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                    }
                )

                if (!usageGranted) {
                    GlassCard(modifier = Modifier.padding(12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Enable usage access for accurate 'last used' data", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }) { Text("Open Settings") }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show hidden / system apps", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${filtered.size} apps  ·  ${apps.count { it.isRunningBg }} active in background",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { exportListToCsv(context, filtered) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, tint = Color(0xFF00E5C7))
                        Spacer(Modifier.width(4.dp))
                        Text("Export list", color = Color(0xFF00E5C7))
                    }
                }
            } else {
                // Focus mode: everything except the task list is hidden —
                // status bar, nav bar, and the app's own top bar/search/switch.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { focusMode = false }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit focus mode", tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Tap to exit focus mode", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF00E5C7))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    items(items = filtered, key = { it.packageName }) { app ->
                        ProcessRow(
                            app = app,
                            onDetails = { selected = app },
                            onKill = {
                                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                try { am.killBackgroundProcesses(app.packageName) } catch (e: Exception) {}
                            },
                            onForceStop = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = Uri.parse("package:${app.packageName}")
                                context.startActivity(intent)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${app.packageName}")
                    context.startActivity(intent)
                    selected = null
                }) { Text("Force stop…") }
            },
            title = { Text(app.label) },
            text = {
                Column {
                    Text("Package: ${app.packageName}")
                    Text("Version: ${app.versionName ?: "n/a"}")
                    Text("System app: ${if (app.isSystem) "yes (hidden-capable)" else "no"}")
                    Text("Background: ${if (app.isRunningBg) "active" else "idle"}")
                    if (app.lastUsed > 0) {
                        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        Text("Last used: ${fmt.format(Date(app.lastUsed))}")
                    }
                }
            }
        )
    }
}

@Composable
fun ProcessRow(app: AppProc, onDetails: () -> Unit, onKill: () -> Unit, onForceStop: () -> Unit) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            app.icon?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    app.packageName + if (app.isSystem) "  ·  hidden" else "",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
            if (app.isRunningBg) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF00E5C7))
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDetails) {
                Icon(Icons.Filled.Info, contentDescription = "Details", tint = Color.White.copy(alpha = 0.8f))
            }
            IconButton(onClick = onKill) {
                Icon(Icons.Filled.Close, contentDescription = "Kill background", tint = Color(0xFFFF6B6B))
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
    ) {
        content()
    }
}

// Static (non-animated) geometric shapes drawn once — no infinite
// animation, no per-frame blur. Cheap to render, still looks intentional.
@Composable
fun StaticGeometricAccents() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.TopStart)
                .offset((-80).dp, (-60).dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF6C4CE0).copy(alpha = 0.18f))
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.BottomEnd)
                .offset(70.dp, 90.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF00E5C7).copy(alpha = 0.12f))
        )
    }
}
