package com.streamflixrevanced.streamflix.database.dao

import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.TvShow

/** Latest user-state marker used when merging a JSON backup into local data. */
internal fun Movie.backupStateTimestamp(): Long = listOfNotNull(
    favoritedAtMillis,
    lastPlayedAtMillis,
    watchedDate?.timeInMillis,
    watchHistory?.lastEngagementTimeUtcMillis,
).maxOrNull() ?: 0L

internal fun TvShow.backupStateTimestamp(): Long = listOfNotNull(
    favoritedAtMillis,
    lastPlayedAtMillis,
).maxOrNull() ?: 0L

internal fun Episode.backupStateTimestamp(): Long = listOfNotNull(
    watchedDate?.timeInMillis,
    watchHistory?.lastEngagementTimeUtcMillis,
).maxOrNull() ?: 0L
