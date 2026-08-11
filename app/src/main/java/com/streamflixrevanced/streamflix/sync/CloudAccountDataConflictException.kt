package com.streamflixrevanced.streamflix.sync

/**
 * Local profile data was found while the profile is claimed by another cloud
 * account. The data must be preserved until the user explicitly resolves the
 * account conflict.
 */
class CloudAccountDataConflictException : IllegalStateException(
    "This profile contains local data owned by another cloud account. " +
        "The local data was preserved; resolve the account conflict before syncing.",
)
