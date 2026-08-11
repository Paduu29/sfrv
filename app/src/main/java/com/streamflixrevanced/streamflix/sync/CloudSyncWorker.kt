package com.streamflixrevanced.streamflix.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamflixrevanced.streamflix.utils.ProfileManager

class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val profileId = inputData.getString(PROFILE_ID)
            ?: return Result.failure()
        val expectedUserId = inputData.getString(USER_ID)
            ?: return Result.failure()
        if (!SupabaseProvider.isConfigured) {
            Result.success()
        } else if (ProfileManager.activeProfileId != profileId) {
            // The queue is profile-scoped and will be scheduled again when this
            // profile becomes active. Never apply remote data to a background profile.
            Result.success()
        } else {
            CloudSyncManager.syncProfile(
                applicationContext,
                profileId,
                expectedUserId,
            )
            // A missing or changed profile session is not transient worker failure.
            // Pending mutations remain profile-scoped and are scheduled after login.
            Result.success()
        }
    } catch (_: Throwable) {
        Result.retry()
    }

    companion object {
        const val PROFILE_ID = "profile_id"
        const val USER_ID = "user_id"
    }
}
