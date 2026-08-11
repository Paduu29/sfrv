package com.streamflixrevanced.streamflix.utils

import com.streamflixrevanced.streamflix.models.Profile
import com.streamflixrevanced.streamflix.sync.SupabaseProvider
import com.streamflixrevanced.streamflix.sync.SupabaseSettings
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.storage

object ProfileAvatarRepository {
    private val imageExtensions = setOf("jpg", "jpeg", "png")

    suspend fun getAvailableAvatars(): List<String> {
        val bucketName = SupabaseSettings.config?.avatarBucket.orEmpty()
        if (bucketName.isBlank()) return listOf(Profile.DEFAULT_AVATAR_PATH)
        val profileId = ProfileManager.activeProfileId
            ?: error("No local profile is active")
        val bucket = SupabaseProvider.clientFor(profileId).storage.from(bucketName)
        val paths = listImagePaths(bucket)
            .asSequence()
            .distinct()
            .sorted()
            .toList()
        return buildList {
            add(Profile.DEFAULT_AVATAR_PATH)
            addAll(paths.filterNot { it == Profile.DEFAULT_AVATAR_PATH })
        }
    }

    fun isAllowedAvatarPath(path: String): Boolean {
        val segments = path.split('/')
        return !path.startsWith('/') &&
            segments.all { it.isNotBlank() && it != "." && it != ".." } &&
            isImagePath(path)
    }

    private fun isImagePath(path: String): Boolean =
        path.substringAfterLast('.', missingDelimiterValue = "").lowercase() in imageExtensions

    private suspend fun listImagePaths(
        bucket: BucketApi,
        prefix: String = "",
        visitedFolders: MutableSet<String> = mutableSetOf(),
    ): List<String> {
        if (!visitedFolders.add(prefix)) return emptyList()

        val items = mutableListOf<FileObject>()
        var pageOffset = 0
        var pageSize: Int
        do {
            val page = bucket.list(prefix) {
                limit = PAGE_SIZE
                offset = pageOffset
                sortBy("name", "asc")
            }
            items.addAll(page)
            pageSize = page.size
            pageOffset += pageSize
        } while (pageSize == PAGE_SIZE)

        val paths = mutableListOf<String>()
        items.forEach { item ->
            val path = listOf(prefix, item.name).filter { it.isNotBlank() }.joinToString("/")
            if (item.id == null) {
                paths.addAll(listImagePaths(bucket, path, visitedFolders))
            } else if (isImagePath(path)) {
                paths.add(path)
            }
        }
        return paths
    }

    private const val PAGE_SIZE = 100
}
