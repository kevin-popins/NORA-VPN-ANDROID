package com.privatevpn.app.profiles.subscriptions

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = PrivateVpnDatabase.build(applicationContext)
        val repository = RoomSubscriptionRepository(
            database = database,
            appContext = applicationContext
        )

        try {
            return@withContext runCatching {
                val results = repository.refreshAllSubscriptions(force = false)
                val successCount = results.count { it.status != com.privatevpn.app.profiles.model.SubscriptionSyncStatus.ERROR }
                val errorCount = results.size - successCount
                Log.i(TAG, "Subscription auto-update finished: total=${results.size}, success=$successCount, error=$errorCount")
                if (results.isNotEmpty() && successCount == 0) Result.retry() else Result.success()
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Subscription auto-update failed: ${error::class.java.simpleName}")
                Result.retry()
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val TAG = "SubscriptionWorker"
        private const val WORK_NAME = "privatevpn_subscription_auto_update"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
                REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
