package com.aiden.essentialmapper

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuUtil {
    private const val TAG = "EMAP"

    val TARGET_PACKAGES = listOf(
        "com.nothing.ntessentialspace",
        "com.nothing.ntessentialrecorder",
        "com.nothing.essentialintelligence",
        "com.essentialintelligence"
    )

    fun spawnProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        return method.invoke(null, cmd, null, null) as Process
    }

    suspend fun exec(cmd: Array<String>): Int = withContext(Dispatchers.IO) {
        try {
            val process = spawnProcess(cmd)
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed for ${cmd.joinToString(" ")}: ${e.message}", e)
            -1
        }
    }

    fun getInstalledTargetPackages(ctx: Context): List<String> {
        val pm = ctx.packageManager
        return TARGET_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun isPackageDisabled(ctx: Context, pkg: String): Boolean {
        return try {
            val state = ctx.packageManager.getApplicationEnabledSetting(pkg)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (e: Exception) {
            false
        }
    }

    fun areTargetsDisabled(ctx: Context): Boolean {
        val installed = getInstalledTargetPackages(ctx)
        if (installed.isEmpty()) return false
        return installed.all { isPackageDisabled(ctx, it) }
    }

    suspend fun setTargetsDisabled(ctx: Context, disable: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) return@withContext false
        val installed = getInstalledTargetPackages(ctx)
        var allSuccess = true
        for (pkg in installed) {
            val cmd = if (disable) {
                arrayOf("pm", "disable-user", "--user", "0", pkg)
            } else {
                arrayOf("pm", "enable", pkg)
            }
            Log.i(TAG, "Running: ${cmd.joinToString(" ")}")
            val result = exec(cmd)
            if (result != 0) {
                allSuccess = false
                Log.w(TAG, "Command failed with code $result for $pkg")
            }
        }
        allSuccess
    }
}
