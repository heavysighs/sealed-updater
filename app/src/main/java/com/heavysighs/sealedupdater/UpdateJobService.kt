package com.heavysighs.sealedupdater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Runs the check roughly twice a day. Persisted, so it survives reboots. */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            Updater.runCheck(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4711

        fun schedule(context: Context) {
            val scheduler =
                context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(12 * 60 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            scheduler.schedule(job)
        }
    }
}
