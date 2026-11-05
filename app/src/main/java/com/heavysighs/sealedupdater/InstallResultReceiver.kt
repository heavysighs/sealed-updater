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
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only happens when the app does NOT hold the install delegation
            // (i.e. testing on an unsealed phone): show Android's prompt.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(PackageInstaller.EXTRA_INTENT)
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
