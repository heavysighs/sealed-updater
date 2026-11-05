package com.heavysighs.sealedupdater

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * The entire interface: one status readout and one "Check now" button
 * (mainly for testing - the scheduled job does the real work).
 * There are no settings, deliberately.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Sealed Updater"
            textSize = 22f
        }
        val sub = TextView(this).apply {
            text = "Fetches one signed list from one fixed address and installs " +
                "verified updates for the allowlisted apps only. There are no settings."
            textSize = 14f
        }
        val button = Button(this).apply { text = "Check now" }
        statusView = TextView(this).apply { textSize = 13f }
        button.setOnClickListener {
            button.isEnabled = false
            statusView.text = "Checking..."
            thread {
                val result = Updater.runCheck(applicationContext)
                runOnUiThread {
                    statusView.text = result
                    button.isEnabled = true
                }
            }
        }
        layout.addView(title)
        layout.addView(sub)
        layout.addView(button)
        layout.addView(ScrollView(this).apply { addView(statusView) })
        setContentView(layout)
        // Schedule only once the UI exists, so a scheduler problem can
        // never stop the app from opening.
        UpdateJobService.schedule(applicationContext)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("updater", MODE_PRIVATE)
        val at = prefs.getLong("lastRunAt", 0)
        val last = prefs.getString("lastRun", null)
        var text = if (at == 0L || last == null) {
            "No check has run yet."
        } else {
            "Last check: ${DateFormat.getDateTimeInstance().format(Date(at))}\n\n$last"
        }
        val schedErr = prefs.getString("scheduleError", null)
        if (schedErr != null) text += "\n\nScheduler error: $schedErr"
        val lastInstall = prefs.getString("lastInstall", null)
        val lastInstallAt = prefs.getLong("lastInstallAt", 0)
        if (lastInstall != null) {
            text += "\n\nLast install: " +
                "${DateFormat.getDateTimeInstance().format(Date(lastInstallAt))}\n$lastInstall"
        }
        statusView.text = text
    }
}
