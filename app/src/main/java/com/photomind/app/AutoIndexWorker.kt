package com.photomind.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        if (!hasGalleryPermission(applicationContext)) return Result.success()

        val repository = PhotoRepository(applicationContext)
        val latch = CountDownLatch(1)
        var failed = false
        repository.indexAll(object : PhotoRepository.IndexCallback {
            override fun onStarted(total: Int) = Unit
            override fun onProgress(done: Int, total: Int, indexed: Int, skipped: Int, aiProblems: Int, saveFailed: Int) = Unit
            override fun onFinished(summary: PhotoRepository.IndexSummary) {
                failed = summary.errorMessage != null
                latch.countDown()
            }
        })

        return try {
            val completed = latch.await(8, TimeUnit.MINUTES)
            if (!completed) repository.cancelIndexing()
            if (!completed || failed) Result.retry() else Result.success()
        } catch (_: InterruptedException) {
            repository.cancelIndexing()
            Result.retry()
        } finally {
            repository.close()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK = "photomind-auto-index"
        private const val UNIQUE_IMMEDIATE_WORK = "photomind-retag-reindex"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoIndexWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Called after any manual tag/person-name change. Incremental DB flags make unchanged photos skip. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoIndexWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun hasGalleryPermission(context: Context): Boolean = when {
            Build.VERSION.SDK_INT >= 34 ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            Build.VERSION.SDK_INT >= 33 ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            else ->
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
