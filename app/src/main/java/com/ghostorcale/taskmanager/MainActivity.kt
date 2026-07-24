package com.ghostorcale.taskmanager

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.*

data class AppProc(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val isRunningBg: Boolean,
    val lastUsed: Long,
    val versionName: String?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

fun loadAllApps(context: Context): List<AppProc> {
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

    return apps.map { ai: ApplicationInfo ->
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }
        val icon = try { pm.getApplicationIcon(ai) } catch (e: Exception) { null }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(loadAllApps(context)) }
    var showHidden by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppProc?>(null) }
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }

    val filtered = apps.filter {
        (showHidden || !it.isSystem) &&
        (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GeometricBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Task Manager", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { apps = loadAllApps(context) }) {
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

            Text(
                "${filtered.size} apps  ·  ${apps.count { it.isRunningBg }} active in background",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(filtered) { app ->
                    ProcessRow(
                        app = app,
                        onDetails = { selected = app },
                        onKill = {
                            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            try { am.killBackgroundProcesses(app.packageName) } catch (e: Exception) {}
                            apps = loadAllApps(context)
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

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
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
                    bitmap = it.toBitmap(96, 96).asImageBitmap(),
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

@Composable
fun GeometricBackground() {
    val infinite = rememberInfiniteTransition(label = "bg")
    val angle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing)),
        label = "angle"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF120A24), Color(0xFF1B1035), Color(0xFF241247))
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize().blur(60.dp)) {
            rotate(angle, pivot = Offset(size.width * 0.2f, size.height * 0.15f)) {
                drawCircle(
                    color = Color(0xFF6C4CE0).copy(alpha = 0.35f),
                    radius = size.minDimension * 0.35f,
                    center = Offset(size.width * 0.2f, size.height * 0.15f)
                )
            }
            rotate(-angle, pivot = Offset(size.width * 0.85f, size.height * 0.8f)) {
                drawCircle(
                    color = Color(0xFF00E5C7).copy(alpha = 0.22f),
                    radius = size.minDimension * 0.3f,
                    center = Offset(size.width * 0.85f, size.height * 0.8f)
                )
            }
        }
    }
}
