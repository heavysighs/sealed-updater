package com.heavysighs.sealedupdater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole updater in one object. Deliberately dumb:
 * fetch ONE signed list from ONE fixed address, verify it, and for each entry
 * run the same five checks before handing the file to the installer.
 * Any failed check means: no install, keep the current version.
 * One app's failure never stops the others from being processed.
 */
object Updater {
    private const val TAG = "SealedUpdater"

    const val FEED_URL =
        "https://raw.githubusercontent.com/heavysighs/sealed-updater/main/feed/list.json"
    private const val SIG_URL = "$FEED_URL.sig"

    // ECDSA P-256 public key (SubjectPublicKeyInfo DER, base64) matching the
    // private key held only by the feed pipeline.
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2EhMT7lTQJn42GbEz00KMd00I+iE/I3b3OFohQ1hnPGHzwoQ1t7Ow2BfkaEDAcFbnLgyZKLM5JR7d7zuCLqUfg=="

    // The only packages this updater may ever touch. Fixed in code on purpose:
    // there is no settings screen and no way to extend this list from the phone.
    val ALLOWLIST = setOf(
        "com.guiforge.bati",
        "org.breezyweather",
        "app.clearsms",
        "neth.iecal.curbox",
        "de.binauralbeats.app.foss",
        "io.github.ronynn.karui",
        "xyz.linerly.nontrinsic",
        "com.infinity.readz",
        "com.heavysighs.sealedupdater"
    )

    // The scheduled job and a manual "Check now" must never run at once:
    // both would write the same download files.
    private val running = AtomicBoolean(false)

    fun runCheck(context: Context): String {
        if (!running.compareAndSet(false, true)) {
            return "A check is already running."
        }
        try {
            return runCheckLocked(context)
        } finally {
            running.set(false)
        }
    }

    private fun runCheckLocked(context: Context): String {
        val log = StringBuilder()
        fun note(s: String) {
            Log.i(TAG, s)
            log.append(s).append('\n')
        }
        try {
            val listBytes = download(FEED_URL)
            val sigBytes = Base64.decode(
                String(download(SIG_URL), Charsets.UTF_8).trim(), Base64.DEFAULT
            )
            if (!verifySignature(listBytes, sigBytes)) {
                note("Feed signature INVALID - refusing to continue.")
                return finish(context, log)
            }
            note("Feed signature OK.")

            val apps = JSONObject(String(listBytes, Charsets.UTF_8)).getJSONArray("apps")
            var started = 0
            for (i in 0 until apps.length()) {
                val e = apps.getJSONObject(i)
                val pkg = try {
                    e.getString("package")
                } catch (ex: Exception) {
                    note("Feed entry $i has no package name, skipped")
                    continue
                }
                try {
                    if (processEntry(context, e, pkg) { note(it) }) started++
                } catch (ex: Exception) {
                    note("$pkg: check failed (${ex.javaClass.simpleName}: ${ex.message}), skipped")
                }
            }
            note("Done. Updates started: $started")
        } catch (ex: Exception) {
            note("Check failed: ${ex.javaClass.simpleName}: ${ex.message}")
        }
        return finish(context, log)
    }

    /** Runs the five checks for one feed entry. Returns true if an install started. */
    private fun processEntry(
        context: Context,
        e: JSONObject,
        pkg: String,
        note: (String) -> Unit
    ): Boolean {
        val newVc = e.getLong("versionCode")

        // Check 1: allowlist
        if (pkg !in ALLOWLIST) {
            note("$pkg: not on the allowlist, skipped")
            return false
        }
        // Check 2: already installed. The sealed build only ever updates;
        // the SETUP build (setup day only) may also install the first copy,
        // verified exactly the same way, so it becomes the installer of
        // record and later updates go through silently.
        val installed = try {
            context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (ex: Exception) {
            null
        }
        if (installed == null && !BuildConfig.SETUP_MODE) {
            note("$pkg: not installed, skipped")
            return false
        }
        // Check 3: actually newer
        if (installed != null && newVc <= installed.longVersionCode) {
            note("$pkg: up to date (${installed.longVersionCode})")
            return false
        }
        note(
            if (installed == null) "$pkg: SETUP install of $newVc, downloading..."
            else "$pkg: update ${installed.longVersionCode} -> $newVc, downloading..."
        )
        val apk = File(context.cacheDir, "update-$pkg.apk")
        downloadTo(e.getString("url"), apk)

        // Check 4: file hash matches the signed list
        if (sha256Hex(apk) != e.getString("sha256")) {
            note("$pkg: APK hash mismatch, refused")
            apk.delete()
            return false
        }
        // Check 5: the APK really is this app, this version, signed by
        // the signer named in the list AND by the signer of the copy
        // already installed (genuine upstream updates only).
        val archive = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
        )
        if (archive == null || archive.packageName != pkg || archive.longVersionCode != newVc) {
            note("$pkg: APK identity mismatch, refused")
            apk.delete()
            return false
        }
        val newCert = certSha256(archive)
        if (newCert != e.getString("certSha256")) {
            note("$pkg: signer is not the one named in the signed list, refused")
            apk.delete()
            return false
        }
        if (installed != null && newCert != certSha256(installed)) {
            note("$pkg: signer differs from the installed app, refused")
            apk.delete()
            return false
        }
        note("$pkg: verified, installing...")
        Installer.install(context, apk, pkg)
        return true
    }

    private fun finish(context: Context, log: StringBuilder): String {
        val out = log.toString()
        context.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
            .putString("lastRun", out)
            .putLong("lastRunAt", System.currentTimeMillis())
            .apply()
        return out
    }

    private fun verifySignature(data: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(key)
        s.update(data)
        s.verify(sig)
    } catch (e: Exception) {
        false
    }

    private fun connect(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "SealedUpdater/1.3")
        return c
    }

    private fun download(url: String): ByteArray =
        connect(url).inputStream.use { it.readBytes() }

    private fun downloadTo(url: String, dest: File) {
        connect(url).inputStream.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certSha256(info: PackageInfo): String {
        val signing = info.signingInfo ?: return ""
        val sigs = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory ?: signing.apkContentsSigners
        }
        val first = sigs?.firstOrNull() ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
