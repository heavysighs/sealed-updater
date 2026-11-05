package com.heavysighs.sealedupdater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "?"
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        Log.i("SealedUpdater", "Install result for $pkg: status=$status $msg")

        // Record the outcome where the status screen can show it, so an
        // install never succeeds or fails silently out of sight.
        val label = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "waiting for your confirmation"
            PackageInstaller.STATUS_SUCCESS -> "installed OK"
            PackageInstaller.STATUS_FAILURE -> "FAILED"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILED (blocked by policy)"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILED (aborted)"
            PackageInstaller.STATUS_FAILURE_INVALID -> "FAILED (invalid APK)"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILED (conflict with existing app)"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILED (not enough storage)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILED (incompatible)"
            else -> "status $status"
        }
        context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
            .putString("lastInstall", "$pkg: $label $msg".trim())
            .putLong("lastInstallAt", System.currentTimeMillis())
            .apply()

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only happens when the app does NOT hold the install delegation
            // (i.e. testing on an unsealed phone): show Android's prompt.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
