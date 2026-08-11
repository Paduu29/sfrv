package com.streamflixrevanced.streamflix.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatarPath: String = DEFAULT_AVATAR_PATH,
    val createdAt: Long = System.currentTimeMillis(),
    val position: Int = 0,
) {
    companion object {
        const val DEFAULT_AVATAR_PATH = "blank_picture.jpg"
    }
}
