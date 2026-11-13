package com.aiden.essentialmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class EssentialKeyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var monitorJob: Job? = null
    private var geteventProcess: Process? = null

    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0

    private val resolveTaps = Runnable {
        val count = tapCount
        tapCount = 0
        dispatch(count)
    }

    // ---- Flashlight (torch) ----
    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }
    private val torchCameraId: String? by lazy { findTorchCameraId() }
    @Volatile private var torchOn = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) torchOn = enabled
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runBlocking { Config.seedDefaultsIfEmpty(this@EssentialKeyService) }
        cameraManager.registerTorchCallback(torchCallback, handler)
        createNotificationChannel()
        _isRunning.value = true
        Log.i(TAG, "EssentialKeyService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startMonitoring()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        cameraManager.unregisterTorchCallback(torchCallback)
        _isRunning.value = false
        Log.i(TAG, "EssentialKeyService destroyed")
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = serviceScope.launch {
            Log.i(TAG, "Starting Shizuku getevent loop...")
            while (isActive) {
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "Shizuku pingBinder failed, waiting 3s...")
                    delay(3000)
                    continue
                }

                try {
                    val targetDevice = "/dev/input/event0"
                    Log.i(TAG, "Spawning getevent on $targetDevice")
                    val process = ShizukuUtil.spawnProcess(arrayOf("getevent", "-lt", targetDevice))
                    geteventProcess = process

                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?

                    while (isActive) {
                        line = reader.readLine() ?: break
                        parseEventLine(line)
                    }

                    val exitCode = process.waitFor()
                    Log.w(TAG, "getevent process exited with code $exitCode")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in getevent monitor loop: ${e.message}", e)
                } finally {
                    geteventProcess?.destroy()
                    geteventProcess = null
                }

                if (isActive) {
                    delay(2000)
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        geteventProcess?.destroy()
        geteventProcess = null
    }

    private fun parseEventLine(line: String) {
        // Sample lines:
        // /dev/input/event0: EV_KEY       00fa                 DOWN
        // /dev/input/event0: EV_KEY       00fa                 UP
        // [   95395.281224] /dev/input/event0: EV_KEY       00fa                 DOWN
        if (!line.contains("EV_KEY")) return
        if (!line.contains("00fa") && !line.contains("250")) return

        if (line.contains("DOWN")) {
            Log.i(TAG, "Essential Key DOWN detected: $line")
            handler.post {
                tapCount++
                handler.removeCallbacks(resolveTaps)
                handler.postDelayed(resolveTaps, TAP_DEBOUNCE_MS)
                Log.i(TAG, "Tap registered (running count=$tapCount)")
            }
        } else if (line.contains("UP")) {
            Log.d(TAG, "Essential Key UP detected: $line")
        }
    }

    private fun dispatch(count: Int) {
        val target = Config.readBlocking(this).forCount(count)
        Log.i(TAG, "Resolved tapCount=$count -> ${target ?: "none"}")
        when (target) {
            null -> { /* unmapped slot */ }
            Config.ACTION_FLASHLIGHT -> toggleTorch()
            else -> launchApp(target)
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        val component = intent?.component

        if (Shizuku.pingBinder()) {
            try {
                val cmd = if (component != null) {
                    arrayOf("am", "start", "-n", component.flattenToShortString(), "-f", "0x10000000")
                } else {
                    arrayOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                }
                Log.i(TAG, "Launching $pkg via Shizuku: ${cmd.joinToString(" ")}")
                val process = ShizukuUtil.spawnProcess(cmd)
                serviceScope.launch {
                    val code = process.waitFor()
                    Log.i(TAG, "Launch $pkg finished with exit code $code")
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch $pkg via Shizuku: ${e.message}")
            }
        }

        if (intent == null) {
            Log.w(TAG, "No launch intent for '$pkg'")
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "Launched $pkg via context")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg: ${e.message}")
        }
    }

    private fun findTorchCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Torch id lookup failed: $e")
        null
    }

    private fun toggleTorch() {
        val id = torchCameraId
        if (id == null) {
            Log.w(TAG, "No flash unit available")
            return
        }
        try {
            val next = !torchOn
            cameraManager.setTorchMode(id, next)
            torchOn = next
            Log.i(TAG, "Torch -> $next")
        } catch (e: Exception) {
            Log.w(TAG, "setTorchMode failed: $e")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_running)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification_running))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "EMAP"
        const val CHANNEL_ID = "essential_mapper_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.aiden.essentialmapper.STOP"
        const val TAP_DEBOUNCE_MS = 400L

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, EssentialKeyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, EssentialKeyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
