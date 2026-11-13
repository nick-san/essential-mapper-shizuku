package com.aiden.essentialmapper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap)

private suspend fun loadLaunchableApps(ctx: Context): List<AppInfo> =
    withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { ri ->
                AppInfo(
                    pkg = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
                )
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

class MainActivity : ComponentActivity() {

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.i("EMAP", "Shizuku permission result: $granted")
                updateShizukuState()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i("EMAP", "Shizuku binder received")
        updateShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.i("EMAP", "Shizuku binder dead")
        updateShizukuState()
    }

    private var shizukuState by mutableStateOf(ShizukuStatus(isAlive = false, isGranted = false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        updateShizukuState()

        setContent {
            MaterialTheme {
                ConfigScreen(
                    shizukuStatus = shizukuState,
                    onRequestShizukuPermission = { requestShizukuPermission() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuState()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun updateShizukuState() {
        val alive = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        val granted = if (alive) {
            try {
                if (Shizuku.isPreV11()) {
                    false
                } else {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }
            } catch (e: Throwable) {
                false
            }
        } else {
            false
        }
        shizukuState = ShizukuStatus(isAlive = alive, isGranted = granted)
    }

    private fun requestShizukuPermission() {
        if (!shizukuState.isAlive) return
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        } catch (e: Throwable) {
            Log.e("EMAP", "Failed to request Shizuku permission", e)
        }
    }

    companion object {
        const val REQUEST_CODE_SHIZUKU = 1002
    }
}

data class ShizukuStatus(val isAlive: Boolean, val isGranted: Boolean)

@Composable
private fun ConfigScreen(
    shizukuStatus: ShizukuStatus,
    onRequestShizukuPermission: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyMap by Config.flow(ctx).collectAsState(initial = KeyMap())
    val isRunning by EssentialKeyService.isRunning.collectAsState()
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var picker by remember { mutableStateOf<TapSlot?>(null) }

    var isSinglePressUnlocked by remember { mutableStateOf(ShizukuUtil.areTargetsDisabled(ctx)) }
    var isTogglingUnlock by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                EssentialKeyService.start(ctx)
            }
        }
    )

    LaunchedEffect(Unit) {
        Config.seedDefaultsIfEmpty(ctx)
        apps = loadLaunchableApps(ctx)
        isSinglePressUnlocked = ShizukuUtil.areTargetsDisabled(ctx)
    }

    LaunchedEffect(shizukuStatus.isGranted) {
        isSinglePressUnlocked = ShizukuUtil.areTargetsDisabled(ctx)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EssentialMapper (Shizuku)", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Map Essential Key events via Shizuku without AccessibilityService.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Shizuku & Service Status Card
            StatusCard(
                shizukuStatus = shizukuStatus,
                isServiceRunning = isRunning,
                onRequestPermission = onRequestShizukuPermission,
                onToggleService = {
                    if (isRunning) {
                        EssentialKeyService.stop(ctx)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            EssentialKeyService.start(ctx)
                        }
                    }
                }
            )

            // Single Press Unlock Card (pm disable-user)
            SinglePressUnlockCard(
                isShizukuGranted = shizukuStatus.isGranted,
                isUnlocked = isSinglePressUnlocked,
                isLoading = isTogglingUnlock,
                onToggle = { enableUnlock ->
                    scope.launch {
                        isTogglingUnlock = true
                        ShizukuUtil.setTargetsDisabled(ctx, enableUnlock)
                        isSinglePressUnlocked = ShizukuUtil.areTargetsDisabled(ctx)
                        isTogglingUnlock = false
                    }
                }
            )

            HorizontalDivider()

            Text("Key Mapping", style = MaterialTheme.typography.titleMedium)

            TapSlot.entries.forEach { slot ->
                val value = keyMap.forSlot(slot)
                SlotRow(
                    slot = slot,
                    valueLabel = slotDisplay(value, apps),
                    icon = apps?.find { it.pkg == value }?.icon,
                    enabled = apps != null,
                    onClick = { picker = slot },
                )
            }

            if (apps == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading apps…")
                }
            }
        }
    }

    val slot = picker
    val loaded = apps
    if (slot != null && loaded != null) {
        AppPickerDialog(
            title = slot.label,
            apps = loaded,
            current = keyMap.forSlot(slot),
            onPick = { pkg ->
                scope.launch { Config.set(ctx, slot, pkg) }
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

@Composable
private fun StatusCard(
    shizukuStatus: ShizukuStatus,
    isServiceRunning: Boolean,
    onRequestPermission: () -> Unit,
    onToggleService: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shizukuText = when {
                !shizukuStatus.isAlive -> "Shizuku: Not running"
                !shizukuStatus.isGranted -> "Shizuku: Connected (Permission needed)"
                else -> "Shizuku: Connected & Authorized"
            }
            Text(shizukuText, style = MaterialTheme.typography.bodyMedium)

            val serviceText = if (isServiceRunning) "Service: Running" else "Service: Stopped"
            Text(serviceText, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shizukuStatus.isAlive && !shizukuStatus.isGranted) {
                    Button(onClick = onRequestPermission) {
                        Text("Grant Shizuku Permission")
                    }
                }

                if (shizukuStatus.isGranted) {
                    Button(
                        onClick = onToggleService,
                        colors = if (isServiceRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors()
                    ) {
                        Text(if (isServiceRunning) "Stop Service" else "Start Service")
                    }
                }
            }
        }
    }
}

@Composable
private fun SinglePressUnlockCard(
    isShizukuGranted: Boolean,
    isUnlocked: Boolean,
    isLoading: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("単押し解放 (Nothing純正無効化)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isUnlocked) "Nothing純正アプリが無効化されています (単押しを完全に利用可能)"
                    else "Nothing純正アプリが有効です (単押しでEssential Spaceが起動)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = isUnlocked,
                    onCheckedChange = onToggle,
                    enabled = isShizukuGranted
                )
            }
        }
    }
}

private fun slotDisplay(value: String?, apps: List<AppInfo>?): String = when {
    value == null -> "None"
    value == Config.ACTION_FLASHLIGHT -> "Flashlight (toggle)"
    else -> apps?.find { it.pkg == value }?.label ?: value
}

@Composable
private fun SlotRow(
    slot: TapSlot,
    valueLabel: String,
    icon: ImageBitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(slot.label, style = MaterialTheme.typography.labelMedium)
                Text(valueLabel, style = MaterialTheme.typography.bodyLarge)
            }
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<AppInfo>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        PickRow("None", null, selected = current == null) { onPick(null) }
                    }
                    item {
                        PickRow(
                            "Flashlight (toggle)",
                            null,
                            selected = current == Config.ACTION_FLASHLIGHT,
                        ) { onPick(Config.ACTION_FLASHLIGHT) }
                    }
                    items(apps, key = { it.pkg }) { a ->
                        PickRow(a.label, a.icon, selected = current == a.pkg) { onPick(a.pkg) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(
    label: String,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Spacer(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
