package com.streamflixrevanced.streamflix.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.utils.CustomTabHelper
import com.streamflixrevanced.streamflix.utils.EpisodeManager
import com.streamflixrevanced.streamflix.utils.OpenSubtitles
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.streamflixrevanced.streamflix.utils.SubDL
import com.streamflixrevanced.streamflix.utils.SubtitleLanguageFilter

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode
    private var activeServerLoad: Job? = null
    private var activeVideoLoad: Job? = null
    private val playbackRetryAttempted = mutableSetOf<Video.Server>()

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId,
                currentProgram = ep.tvShow.currentProgram
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String): Job {
        activeVideoLoad?.cancel()
        activeServerLoad?.cancel()
        lastVideoType = videoType
        lastId = id
        playbackRetryAttempted.clear()

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
            _state.emit(State.LoadingServers)
            try {
                val provider = UserPreferences.currentProvider
                    ?: throw IllegalStateException("No provider selected")
                val servers = provider.getServers(id, videoType)
                ensureActive()

                if (servers.isEmpty()) {
                    Log.w("PlayerViewModel", "No streaming servers found for this title")
                    _state.emit(State.NoServers)
                    return@launch
                }

                Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${provider.name}")
                Log.i(
                    "StreamFlixES",
                    "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}",
                )
                Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
                _state.emit(State.SuccessLoadingServers(servers))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("PlayerViewModel", "Errore ricerca server: ", error)
                _state.emit(State.FailedLoadingServers(error))
            }
        }

        activeServerLoad = job
        job.start()
        return job
    }

    fun getVideo(server: Video.Server): Job {
        activeVideoLoad?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
            _state.emit(State.LoadingVideo(server))
            try {
                val video = loadVideo(server)
                ensureActive()
                if (video.source.isBlank()) {
                    throw IllegalStateException("No playable source returned by ${server.name}")
                }

                // Preserve a provider-selected subtitle. Otherwise, restore the user's subtitle
                // preference except for Spanish providers, where forced tracks are provider-owned.
                val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
                val hasDefaultAlready = video.subtitles.any { it.default }
                if (!hasDefaultAlready && currentProviderLang != "es") {
                    if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                        video.subtitles
                            .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                            ?.default = true
                    }
                }

                Log.d("PlayerViewModel", "Estrazione video completata con successo")
                _state.emit(State.SuccessLoadingVideo(video, server))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("PlayerViewModel", "Errore estrazione video: ", error)
                _state.emit(State.FailedLoadingVideo(error, server))
            }
        }
        activeVideoLoad = job
        job.start()
        return job
    }

    fun selectVideo(server: Video.Server): Job {
        playbackRetryAttempted.remove(server)
        return getVideo(server)
    }

    fun retryVideoAfterPlaybackError(server: Video.Server?): Boolean {
        if (server == null || !playbackRetryAttempted.add(server)) return false
        getVideo(server)
        return true
    }

    fun markVideoPlaying(server: Video.Server?) {
        if (server != null) playbackRetryAttempted.remove(server)
    }

    private suspend fun loadVideo(server: Video.Server): Video {
        val provider = UserPreferences.currentProvider!!
        val firstAttempt = runCatching { provider.getVideo(server) }
        firstAttempt.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        val firstVideo = firstAttempt.getOrNull()
        if (firstVideo != null && firstVideo.source.isNotBlank()) {
            return firstVideo
        }

        if (!isBrowserGuardedServer(server)) {
            return firstAttempt.getOrThrow()
        }

        Log.w(
            "PlayerViewModel",
            "Retrying browser-guarded server after failure: ${server.name}",
            firstAttempt.exceptionOrNull()
        )
        delay(750)
        val secondVideo = provider.getVideo(server)
        if (secondVideo.source.isBlank()) {
            throw IllegalStateException("No source found")
        }
        return secondVideo
    }

    private fun isBrowserGuardedServer(server: Video.Server): Boolean {
        val host = runCatching { Uri.parse(server.src).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "powvideo.org" ||
            host == "powwideo.org" ||
            host == "streamplay.to" ||
            host == "straemplay.org" ||
            host == "filemoon.site" ||
            host == "filemoon.sx" ||
            host == "megacloud.blog" ||
            host == "videostr.net" ||
            host == "vidguard.to" ||
            host == "rabbitstream.net" ||
            host == "dokicloud.one"
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val selectedLanguages = UserPreferences.subtitleLanguages
                val subtitles = if (!SubtitleLanguageFilter.shouldSearch(selectedLanguages)) {
                    emptyList()
                } else {
                    searchOpenSubtitles(
                        videoType = videoType,
                        languageIds = SubtitleLanguageFilter.openSubtitlesLanguageIds(selectedLanguages),
                    )
                }
                val filteredSubtitles = subtitles
                    .filter { SubtitleLanguageFilter.allowsOpenSubtitle(it, selectedLanguages) }
                    .sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${filteredSubtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(filteredSubtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val selectedLanguages = UserPreferences.subtitleLanguages
                val subtitles = if (!SubtitleLanguageFilter.shouldSearch(selectedLanguages)) {
                    emptyList()
                } else when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv",
                            languages = SubtitleLanguageFilter.subDlQuery(selectedLanguages),
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie",
                            languages = SubtitleLanguageFilter.subDlQuery(selectedLanguages),
                        )
                    }
                }
                val filteredSubtitles = subtitles.filter {
                    SubtitleLanguageFilter.allowsSubDLSubtitle(it, selectedLanguages)
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${filteredSubtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(filteredSubtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    private suspend fun searchOpenSubtitles(
        videoType: Video.Type,
        languageIds: List<String>?,
    ): List<OpenSubtitles.Subtitle> {
        // The legacy REST endpoint accepts only one sublanguageid per request.
        // A null list means "All languages", which remains one unfiltered request.
        val requestLanguageIds: List<String?> = languageIds ?: listOf(null)
        if (requestLanguageIds.isEmpty()) return emptyList()

        val results = coroutineScope {
            requestLanguageIds.map { languageId ->
                async {
                    runCatching {
                        when (videoType) {
                            is Video.Type.Episode -> OpenSubtitles.search(
                                query = videoType.tvShow.title,
                                season = videoType.season.number,
                                episode = videoType.number,
                                subLanguageId = languageId,
                            )
                            is Video.Type.Movie -> OpenSubtitles.search(
                                query = videoType.title,
                                subLanguageId = languageId,
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val successfulResults = results.mapNotNull { it.getOrNull() }
        if (successfulResults.isEmpty()) {
            throw results.mapNotNull { it.exceptionOrNull() }.first()
        }

        return successfulResults
            .flatten()
            .distinctBy { subtitle ->
                subtitle.idSubtitleFile
                    ?: subtitle.idSubtitle
                    ?: subtitle.idSubMovieFile
                    ?: subtitle.subDownloadLink.takeIf { it.isNotBlank() }
                    ?: "${subtitle.subFileName}|${subtitle.subLanguageID}"
            }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data object NoServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
