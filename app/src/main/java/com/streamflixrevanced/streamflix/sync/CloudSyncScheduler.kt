package com.streamflixrevanced.streamflix.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.streamflixrevanced.streamflix.utils.ProfileManager

object CloudSyncScheduler {
    fun enqueue(context: Context) {
        val profileId = ProfileManager.activeProfileId ?: return
        val userId = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(context, profileId)
            ?: return
        enqueue(context, profileId, userId)
    }

    fun enqueue(context: Context, profileId: String, userId: String) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInputData(
                workDataOf(
                    CloudSyncWorker.PROFILE_ID to profileId,
                    CloudSyncWorker.USER_ID to userId,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(profileTag(profileId))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "cloud-user-state-$userId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelProfile(context: Context, profileId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelAllWorkByTag(profileTag(profileId))
    }

    internal fun profileTag(profileId: String): String = "cloud-profile-$profileId"
}
