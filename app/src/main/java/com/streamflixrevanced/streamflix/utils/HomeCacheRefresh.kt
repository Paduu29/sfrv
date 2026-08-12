package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamflixrevanced.streamflix.providers.HdFullProvider
import java.util.concurrent.TimeUnit

object HomeCacheRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "home-cache-background-refresh"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<HomeCacheRefreshWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class HomeCacheRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val provider = UserPreferences.currentProvider ?: return Result.success()
        if (provider === HdFullProvider && !HdFullProvider.hasConfiguredCredentials()) {
            Log.d(TAG, "Skipping HDFull home refresh because credentials are not configured")
            return Result.success()
        }

        return runCatching {
            HomeCacheStore.write(applicationContext, provider, provider.getHome())
            Result.success()
        }.getOrElse { error ->
            Log.w(TAG, "Could not refresh the home cache for ${provider.name}", error)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "HomeCacheRefresh"
    }
}
