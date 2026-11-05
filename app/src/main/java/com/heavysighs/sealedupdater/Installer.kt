package com.heavysighs.sealedupdater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Hands a verified APK to Android's PackageInstaller.
 * When this app holds the device owner's install delegation (the sealed
 * setup), the commit is silent. Without it (testing), Android shows its
 * normal install prompt, which InstallResultReceiver forwards to the screen.
 */
object Installer {
    fun install(context: Context, apk: File, packageName: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.openWrite("package", 0, apk.length()).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction("com.heavysighs.sealedupdater.INSTALL_RESULT")
        val pending = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        session.commit(pending.intentSender)
        session.close()
    }
}
