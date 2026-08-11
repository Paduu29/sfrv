package com.streamflixrevanced.streamflix.sync

class CloudAccountAlreadyLinkedException(
    val linkedProfileName: String,
) : IllegalStateException()
