package com.streamflixrevanced.streamflix.fragments.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.fragments.player.settings.PlayerSettingsView
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.databinding.ContentExoControllerTvBinding
import com.streamflixrevanced.streamflix.databinding.FragmentPlayerTvBinding
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.models.WatchItem
import com.streamflixrevanced.streamflix.providers.SerienStreamProvider
import com.streamflixrevanced.streamflix.providers.IptvProvider
import com.streamflixrevanced.streamflix.ui.PlayerTvView
import com.streamflixrevanced.streamflix.utils.SubtitleOffsetRenderersFactory
import com.streamflixrevanced.streamflix.utils.DnsResolver
import com.streamflixrevanced.streamflix.utils.NetworkClient
import com.streamflixrevanced.streamflix.utils.EpisodeManager
import com.streamflixrevanced.streamflix.utils.LiveChannelPlaybackQueue
import com.streamflixrevanced.streamflix.utils.LiveChannelMetadata
import com.streamflixrevanced.streamflix.utils.MediaServer
import com.streamflixrevanced.streamflix.utils.PlayerGestureHelper
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.UserDataCache
import com.streamflixrevanced.streamflix.sync.CloudSyncHooks
import com.streamflixrevanced.streamflix.utils.dp
import com.streamflixrevanced.streamflix.utils.getFileName
import com.streamflixrevanced.streamflix.utils.next
import com.streamflixrevanced.streamflix.utils.plus
import com.streamflixrevanced.streamflix.utils.setMediaServerId
import com.streamflixrevanced.streamflix.utils.setMediaServers
import com.streamflixrevanced.streamflix.utils.toSubtitleMimeType
import com.streamflixrevanced.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.util.Base64
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.streamflixrevanced.streamflix.utils.UserDataCache.toEpisode
import com.streamflixrevanced.streamflix.utils.UserDataCache.toMovie
import com.streamflixrevanced.streamflix.utils.IntroDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import com.streamflixrevanced.streamflix.extractors.TokenManager

class PlayerTvFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
        private const val NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED = 0.72f
        private const val NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED = 0.96f
        private const val FALLBACK_SKIP_INTRO_START_MS = 3_000L
        private const val FALLBACK_SKIP_INTRO_VISIBLE_DURATION_MS = 10_000L
    }

    private var _binding: FragmentPlayerTvBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerTvBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private lateinit var gestureHelper: PlayerGestureHelper
    private var playerListener: Player.Listener? = null

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private val failedServers = mutableSetOf<Video.Server>()
    private var sourceRecoveryInProgress = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false
    private var introDbSegments: IntroDb.Segments? = null
    private var introDbLookupEpisodeKey: String? = null
    private var introDbLookupComplete = false
    private var introDbLoadJob: Job? = null
    private var autoSkippedIntroEpisodeKey: String? = null
    private var fallbackSkipIntroVisibleUntilPositionMs: Long? = null
    private var liveZapChannels: List<LiveChannel> = emptyList()
    private var currentLiveChannelType: Video.Type.Episode? = null
    private var liveZapFallbackType: Video.Type.Episode? = null
    private var isLiveZapPending = false
    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(
                        Intent.EXTRA_CHOSEN_COMPONENT,
                        android.content.ComponentName::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i(
                    "ExternalPlayer",
                    "TV - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}"
                )
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = requireActivity().window
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }

        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN_TV")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializePlayer(false)
        initializeVideo()
        binding.pvPlayer.onMediaPreviousClicked = ::handleMediaPrevious
        binding.pvPlayer.onMediaNextClicked = ::handleMediaNext
        gestureHelper = PlayerGestureHelper(
            requireContext(),
            binding.pvPlayer,
            binding.llBrightness,
            binding.pbBrightness,
            binding.tvBrightnessPercentage,
            binding.llVolume,
            binding.pbVolume,
            binding.tvVolumePercentage
        )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {
                        failedServers.clear()
                        sourceRecoveryInProgress = false
                        showSourceStatus(getString(R.string.player_sources_loading_message))
                    }

                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        servers = state.servers
                        failedServers.clear()
                        sourceRecoveryInProgress = false

                        player.playlistMetadata = MediaMetadata.Builder()
                            .setTitle(state.toString())
                            .setMediaServers(state.servers.map {
                                MediaServer(
                                    id = it.id,
                                    name = it.name,
                                )
                            })
                            .build()
                        binding.settings.setOnServerSelectedListener { server ->
                            val selected = state.servers.firstOrNull {
                                it.id == server.id && it.name == server.name
                            } ?: state.servers.firstOrNull { it.id == server.id }
                            selected?.let(::selectServerManually)
                        }
                        val preferredServer = state.servers.firstOrNull {
                            it.name.equals(args.preferredServerName, ignoreCase = true)
                        }
                        tryServer(preferredServer ?: state.servers.first())
                    }

                    PlayerViewModel.State.NoServers -> {
                        showPlaybackUnavailable(R.string.player_no_sources_message)
                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        Log.e("PlayerTvFragment", "Unable to discover playback sources", state.error)
                        showPlaybackUnavailable(R.string.player_sources_load_failed_message)
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        // Do not give a prepared player an empty URI while an extractor is
                        // resolving. Media3 interprets it as a local file and raises ENOENT.
                        player.stop()
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        sourceRecoveryInProgress = false
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                        isLiveZapPending = false
                        liveZapFallbackType = null
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        sourceRecoveryInProgress = false
                        if (!shouldAutoAdvanceServer(state.server)) {
                            hideSourceStatus()
                            Toast.makeText(
                                requireContext(),
                                "This server needs manual completion. Pick another server when you're done.",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            recoverFromFailedServer(state.server, state.error)
                        }
                    }
                }
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
                viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
                    .collect { state ->
                        when (state) {
                            PlayerViewModel.SubtitleState.Loading -> {}
                            is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                                binding.settings.openSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
                                val currentPosition = player.currentPosition
                                val currentSubtitleConfigurations =
                                    player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                                        MediaItem.SubtitleConfiguration.Builder(it.uri)
                                            .setMimeType(it.mimeType)
                                            .setLabel(it.label)
                                            .setLanguage(it.language)
                                            .setSelectionFlags(0)
                                            .build()
                                    } ?: listOf()
                                player.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                        .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                        .setSubtitleConfigurations(
                                            currentSubtitleConfigurations
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(fileName)
                                                .setLanguage(state.subtitle.languageName)
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.languageName ?: fileName).substringBefore(" ")
                                state.subtitle.languageName?.let { lang ->
                                    UserPreferences.preferredSubtitleLanguage = lang
                                }
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.subFileName}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                                binding.settings.subDLSubtitles = state.subtitles
                            }

                            is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                            PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                            is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                                val fileName =
                                    state.uri.getFileName(requireContext()) ?: state.uri.toString()
                                val currentPosition = player.currentPosition
                                val currentSubtitleConfigurations =
                                    player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                                        MediaItem.SubtitleConfiguration.Builder(it.uri)
                                            .setMimeType(it.mimeType)
                                            .setLabel(it.label)
                                            .setLanguage(it.language)
                                            .setSelectionFlags(0)
                                            .build()
                                    } ?: listOf()
                                player.setMediaItem(
                                    MediaItem.Builder()
                                        .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                        .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                        .setSubtitleConfigurations(
                                            currentSubtitleConfigurations
                                                    + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                                .setMimeType(fileName.toSubtitleMimeType())
                                                .setLabel(
                                                    state.subtitle.releaseName
                                                        ?: state.subtitle.name ?: fileName
                                                )
                                                .setLanguage(
                                                    state.subtitle.lang ?: state.subtitle.language
                                                    ?: "Unknown"
                                                )
                                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                                .build()
                                        )
                                        .setMediaMetadata(player.mediaMetadata)
                                        .build()
                                )
                                UserPreferences.subtitleName =
                                    (state.subtitle.releaseName ?: state.subtitle.name
                                    ?: fileName).substringBefore(" ")
                                val subDLLang = state.subtitle.lang ?: state.subtitle.language
                                if (subDLLang != null) {
                                    UserPreferences.preferredSubtitleLanguage = subDLLang
                                }
                                player.seekTo(currentPosition)
                                player.play()
                            }

                            is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                                Toast.makeText(
                                    requireContext(),
                                    "${state.subtitle.name}: ${state.error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                        releasePlayer()
                        isSetupDone = false

                        val args = Bundle().apply {
                            putString("id", nextEpisode.id)
                            putSerializable("videoType", nextEpisode)
                            putString("title", nextEpisode.tvShow.title)
                            putString(
                                "subtitle",
                                "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                            )
                            putString("preferredServerName", currentServer?.name)
                        }

                        hideNextEpisodeOverlay()
                        findNavController().navigate(
                            R.id.player,
                            args,
                            NavOptions.Builder()
                                .setPopUpTo(
                                    findNavController().currentDestination?.id ?: return@collect,
                                    true
                                )
                                .setLaunchSingleTop(false)
                                .build()
                        )
                    }
                }
            }


        }

    override fun onPause() {
        super.onPause()

        if (::player.isInitialized) {
            try {
                player.pause()
            } catch (e: Exception) {
                Log.w("Player", "pause() ignored, player already released")
            }
        }

        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

        override fun onDestroyView() {
            super.onDestroyView()
            nextEpisodePrefetchJob?.cancel()
            introDbLoadJob?.cancel()
            releasePlayer()
            try {
                requireContext().unregisterReceiver(chooserReceiver)
            } catch (ignored: Exception) {
            }
            _binding = null
            isSetupDone = false
        }

    fun onBackPressed(): Boolean = when {


        (binding.pvPlayer as? PlayerTvView)?.isManualZoomEnabled == true -> {
            (binding.pvPlayer as? PlayerTvView)?.exitManualZoomMode()
            true
        }

        binding.settings.isVisible -> {
            binding.settings.onBackPressed()
        }

        binding.pvPlayer.controller.isVisible -> {
            binding.pvPlayer.hideController()
            true
        }

        else -> false
    }

    private fun showSourceStatus(message: String, server: Video.Server? = null) {
        if (binding.sourceStatusText.text.toString() != message) {
            binding.sourceStatusText.text = message
        }
        val serverIndex = server?.let { target ->
            servers.indexOfFirst { it.id == target.id && it.name == target.name }
                .takeIf { it >= 0 }
                ?: servers.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
        }
        binding.sourceStatusStep.apply {
            if (serverIndex == null || servers.isEmpty()) {
                isGone = true
            } else {
                isVisible = true
                val step = getString(
                    R.string.player_source_status_step,
                    serverIndex + 1,
                    servers.size,
                )
                if (text.toString() != step) text = step
            }
        }
        binding.sourceStatus.isVisible = true
    }

    private fun hideSourceStatus() {
        binding.sourceStatus.isGone = true
    }

    private fun tryServer(server: Video.Server, message: String? = null) {
        showSourceStatus(
            message ?: getString(R.string.player_source_trying, server.name),
            server,
        )
        viewModel.selectVideo(server)
    }

    private fun selectServerManually(server: Video.Server) {
        failedServers.clear()
        sourceRecoveryInProgress = false
        tryServer(server)
    }

    private fun recoverFromFailedServer(server: Video.Server, error: Exception? = null) {
        error?.let { Log.e("PlayerTvFragment", "Playback source failed: ${server.name}", it) }
        failedServers.add(server)
        val nextServer = nextUnfailedServerAfter(server)
        if (nextServer == null) {
            showPlaybackUnavailable(R.string.player_no_working_source_message)
            return
        }

        sourceRecoveryInProgress = true
        tryServer(
            nextServer,
            getString(R.string.player_source_trying_next, server.name, nextServer.name),
        )
    }

    private fun nextUnfailedServerAfter(server: Video.Server?): Video.Server? {
        if (servers.isEmpty()) return null
        val currentIndex = server?.let { current ->
            servers.indexOfFirst { it === current }
                .takeIf { it >= 0 }
                ?: servers.indexOf(current)
        } ?: -1

        for (offset in 1..servers.size) {
            val index = if (currentIndex >= 0) {
                (currentIndex + offset) % servers.size
            } else {
                offset - 1
            }
            val candidate = servers[index]
            if (candidate != server && candidate !in failedServers) return candidate
        }
        return null
    }

    private fun showPlaybackUnavailable(messageRes: Int) {
        hideSourceStatus()
        sourceRecoveryInProgress = false
        failedServers.clear()
        Toast.makeText(requireContext(), getString(messageRes), Toast.LENGTH_LONG).show()
        if (!recoverFromFailedLiveZap()) {
            findNavController().navigateUp()
        }
    }

    private fun isRetryablePlaybackError(error: PlaybackException): Boolean {
        // Media3 reserves the 2000 range for I/O failures. Only those transient failures get
        // one retry of the same source; other fatal media-source failures use server fallback.
        return error.errorCode in 2000..2999
    }

    private fun isSidecarSubtitleError(error: PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.any { cause ->
            cause.message?.contains("SubtitleParser failed", ignoreCase = true) == true ||
                cause.stackTrace.any { frame ->
                    frame.className.startsWith("androidx.media3.extractor.text.")
                }
        }

    private fun handleMediaPrevious(): Boolean {
        if (isLiveChannel(currentVideoTypeForUi())) {
            return zapFavoriteLiveChannel(-1)
        }
        return when (currentVideoTypeForUi()) {
            is Video.Type.Episode -> {
                if (!EpisodeManager.hasPreviousEpisode()) return false
                playPreviousEpisode()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun handleMediaNext(): Boolean {
        if (isLiveChannel(currentVideoTypeForUi())) {
            return zapFavoriteLiveChannel(1)
        }
        return when (currentVideoTypeForUi()) {
            is Video.Type.Episode -> {
                playNextEpisodeAcrossSeasons()
                true
            }
            is Video.Type.Movie -> false
        }
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = currentVideoTypeForUi() as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            persistCurrentEpisodeBeforeTransition()
            viewModel.playNextEpisode()
        }
    }


        private fun updatePlayerScale() {
            val videoSurfaceView = binding.pvPlayer.videoSurfaceView
            val playerResize = UserPreferences.playerResize

            // Let PlayerView handle aspect ratio changes via resizeMode. Manual scale transforms on the
            // underlying surface can leave stale geometry behind after a quality switch, which is what
            // causes smaller variants to render in the top-left corner.
            binding.pvPlayer.resizeMode = playerResize.resizeMode

            videoSurfaceView?.apply {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                pivotX = width / 2f
                pivotY = height / 2f

                (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    if (
                        params.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.height != FrameLayout.LayoutParams.MATCH_PARENT ||
                        params.gravity != Gravity.CENTER
                    ) {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                }

                requestLayout()
            }
            binding.pvPlayer.requestLayout()
        }

        private fun reloadCurrentVideoForQualityChange() {
            val video = currentVideo ?: return
            val server = currentServer ?: return
            val resumePosition = player.currentPosition
            val shouldPlay = player.isPlaying || player.playWhenReady

            initializePlayer(currentExtraBuffering, currentSoftwareDecoder)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()

            displayVideo(
                video = video,
                server = server,
                startPositionMs = resumePosition,
                shouldPlay = shouldPlay,
            )
        }

        private fun initializeVideo() {
            when (val type = args.videoType) {
                is Video.Type.Episode -> {
                    nextEpisodeOverlayDismissed = false
                    nextEpisodePrefetchTargetId = null

                    if (isLiveChannel(type)) {
                        currentLiveChannelType = type
                        EpisodeManager.clearEpisodes()
                        hideNextEpisodeOverlay()
                        loadLiveZapChannels()
                        updatePlayerHeader(type)
                    } else if (EpisodeManager.listIsEmpty(type)) {
                        EpisodeManager.clearEpisodes()
                        lifecycleScope.launch(Dispatchers.IO) {
                            EpisodeManager.addEpisodesFromDb(type, database)
                            withContext(Dispatchers.Main) {
                                EpisodeManager.setCurrentEpisode(type)
                                updatePlayerHeader(type)
                                setupEpisodeNavigationButtons()
                                refreshEpisodeNavigation(type)
                            }
                        }
                    } else {
                        EpisodeManager.setCurrentEpisode(type)
                        setupEpisodeNavigationButtons()
                        refreshEpisodeNavigation(type)
                    }
                }

                is Video.Type.Movie -> {
                    nextEpisodeOverlayDismissed = false
                    nextEpisodePrefetchTargetId = null
                    EpisodeManager.clearEpisodes()
                    hideNextEpisodeOverlay()
                }
            }
            setupEpisodeNavigationButtons()
            binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
            binding.pvPlayer.subtitleView?.apply {
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
                setStyle(UserPreferences.captionStyle)
                setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
            }
            binding.settings.setOnExtraBufferingSelectedListener {
                displayVideo(
                    currentVideo ?: return@setOnExtraBufferingSelectedListener,
                    currentServer ?: return@setOnExtraBufferingSelectedListener
                )
            }
            binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
                currentSoftwareDecoder = useSoftware
                displayVideo(
                    currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                    currentServer ?: return@setOnSoftwareDecoderSelectedListener
                )
            }

            updatePlayerHeader()

            binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.player_external_player_error_video),
                    Toast.LENGTH_SHORT
                ).show()
            }

            binding.pvPlayer.controller.binding.exoReplay.setOnClickListener {
                player.seekTo(0)
            }

            binding.pvPlayer.controller.binding.exoProgress.setKeyTimeIncrement(10_000)

            binding.pvPlayer.controller.binding.btnExoAspectRatio.setOnClickListener {
                val newResize = UserPreferences.playerResize.next()
                zoomToast?.cancel()
                zoomToast =
                    Toast.makeText(requireContext(), newResize.stringRes, Toast.LENGTH_SHORT)
                zoomToast?.show()

                UserPreferences.playerResize = newResize
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                updatePlayerScale()
            }

            binding.pvPlayer.controller.binding.exoSettings.setOnClickListener {
                binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
                binding.settings.show()
            }

            binding.btnSkipIntroOverlay.setOnClickListener {
                if (isIntroSkippingDisabled()) {
                    it.isGone = true
                    return@setOnClickListener
                }
                val targetPosition = currentIntroDbSegment()?.endMs
                    ?: (player.currentPosition + 85_000L)
                player.seekTo(
                    player.duration.takeIf { duration -> duration > 0L }
                        ?.let { duration -> targetPosition.coerceAtMost(duration) }
                        ?: targetPosition
                )
                autoSkippedIntroEpisodeKey = currentIntroDbEpisodeKey()
                it.visibility = View.GONE
            }
            (binding.pvPlayer as? PlayerTvView)?.onOverlayPrimaryAction = {
                val focusedView = binding.root.findFocus()
                val playerHasImplicitFocus = focusedView == null || focusedView === binding.pvPlayer
                if (binding.btnSkipIntroOverlay.isVisible && playerHasImplicitFocus) {
                    binding.btnSkipIntroOverlay.performClick()
                    true
                } else {
                    false
                }
            }

            binding.btnNextEpisodeAction.setOnClickListener {
                hideNextEpisodeOverlay()
                playNextEpisodeAcrossSeasons()
            }
            binding.btnNextEpisodeDismiss.setOnClickListener {
                nextEpisodeOverlayDismissed = true
                hideNextEpisodeOverlay()
            }
            binding.btnNextEpisodeAction.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeDismiss.hasFocus())
            }
            binding.btnNextEpisodeDismiss.setOnFocusChangeListener { _, hasFocus ->
                updateNextEpisodeOverlayAlpha(hasFocus || binding.btnNextEpisodeAction.hasFocus())
            }

            binding.settings.setOnLocalSubtitlesClickedListener {
                pickLocalSubtitle.launch(
                    arrayOf(
                        "text/plain",
                        "text/str",
                        "application/octet-stream",
                        MimeTypes.TEXT_UNKNOWN,
                        MimeTypes.TEXT_VTT,
                        MimeTypes.TEXT_SSA,
                        MimeTypes.APPLICATION_TTML,
                        MimeTypes.APPLICATION_MP4VTT,
                        MimeTypes.APPLICATION_SUBRIP,
                    )
                )
            }

            binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
                viewModel.downloadSubtitle(subtitle.openSubtitle)
            }
            binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
                viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
            }
            binding.settings.setOnQualitySelectedListener {
                reloadCurrentVideoForQualityChange()
            }
            binding.settings.setOnExtraBufferingSelectedListener {
                displayVideo(
                    currentVideo ?: return@setOnExtraBufferingSelectedListener,
                    currentServer ?: return@setOnExtraBufferingSelectedListener
                )
            }
            binding.settings.onManualZoomClicked = {
                binding.settings.hide()
                binding.pvPlayer.hideController()
                (binding.pvPlayer as? PlayerTvView)?.enterManualZoomMode()
                binding.pvPlayer.requestFocus()
            }
        }

        fun setupEpisodeNavigationButtons() {
            val btnPrevious = binding.pvPlayer.controller.binding.btnCustomPrev
            val btnNext = binding.pvPlayer.controller.binding.btnCustomNext

            val currentLiveChannel = currentVideoTypeForUi() as? Video.Type.Episode
            if (currentLiveChannel != null && isLiveChannel(currentLiveChannel)) {
                val currentId = currentLiveChannel.id
                val canZap = liveZapChannels.any { channel -> channel.id != currentId }
                btnPrevious.visibility = if (canZap) View.VISIBLE else View.GONE
                btnNext.visibility = if (canZap) View.VISIBLE else View.GONE
                btnPrevious.setOnClickListener { zapFavoriteLiveChannel(-1) }
                btnNext.setOnClickListener { zapFavoriteLiveChannel(1) }
                return
            }

            fun handleNavigationButton(
                button: ImageView,
                hasEpisode: () -> Boolean,
                playEpisode: () -> Unit
            ) {
                if (!hasEpisode()) {
                    button.visibility = View.GONE
                    return
                }

                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    if (!hasEpisode()) return@setOnClickListener
                    playEpisode()
                }
            }

            handleNavigationButton(
                btnPrevious,
                EpisodeManager::hasPreviousEpisode,
                ::playPreviousEpisode
            )
            handleNavigationButton(
                btnNext,
                EpisodeManager::hasNextEpisode,
                ::playNextEpisodeAcrossSeasons
            )
        }

        private fun playPreviousEpisode() {
            persistCurrentEpisodeBeforeTransition()
            viewModel.playPreviousEpisode()
        }

        private fun persistCurrentEpisodeBeforeTransition() {
            val videoType = currentVideoTypeForUi() as? Video.Type.Episode ?: return
            val episodeDao = database.episodeDao()
            val episode = episodeDao.getById(videoType.id) ?: return
            val hasFinished = player.hasFinished()
            val provider = UserPreferences.currentProvider

            val previouslyWatchedEpisodeIds = if (hasFinished) {
                episodeDao.getByTvShowId(videoType.tvShow.id)
                    .filter { it.isWatched }
                    .map { it.id }
            } else {
                emptyList()
            }

            if (hasFinished) {
                episode.isWatched = true
                episode.watchedDate = Calendar.getInstance()
                episode.watchHistory = null
            } else {
                episode.isWatched = false
                episode.watchedDate = null
                episode.watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = player.currentPosition,
                    durationMillis = player.duration,
                )
            }
            episodeDao.update(episode)

            if (hasFinished) {
                episodeDao.resetProgressionFromEpisode(videoType.id)
                provider?.let {
                    episodeDao.getByIds(previouslyWatchedEpisodeIds)
                        .filterNot { resetEpisode -> resetEpisode.isWatched }
                        .forEach { resetEpisode ->
                            CloudSyncHooks.episode(requireContext(), it, resetEpisode)
                        }
                    UserDataCache.removeEpisodeFromContinueWatching(requireContext(), it, episode.id)
                }
            } else {
                provider?.let {
                    UserDataCache.addEpisodeToContinueWatching(requireContext(), it, episode)
                }
            }

            episode.tvShow?.let { database.tvShowDao().getById(it.id) }?.let { tvShow ->
                val updatedTvShow = tvShow.copy().apply {
                    merge(tvShow)
                    isWatching = if (hasFinished) {
                        episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)
                    } else {
                        true
                    }
                }
                database.tvShowDao().update(updatedTvShow)
                provider?.let {
                    CloudSyncHooks.tvShow(requireContext(), it, updatedTvShow)
                }
            }
        }

        private fun decodeBase64Uri(uri: String): String? {
            return try {
                val parts = uri.split(",")
                if (parts.size == 2 && parts[0].contains(";base64")) {
                    val base64Data = parts[1]
                    val decodedBytes = Base64.getDecoder().decode(base64Data)
                    String(decodedBytes, Charsets.UTF_8)
                } else {
                    null
                }
            } catch (ignored: Exception) {
                null
            }
        }

        private fun extractUrlFromPlaylist(playlist: String): String? {
            return try {
                val lines = playlist.lines().map { it.trim() }
                lines.firstOrNull { it.startsWith("http") }
                    ?: lines.firstNotNullOfOrNull { line ->
                        val regex = """URI=["'](http[^"']+)["']""".toRegex()
                        regex.find(line)?.groupValues?.get(1)
                    }
            } catch (ignored: Exception) {
                null
            }
        }

        private fun displayVideo(
            video: Video,
            server: Video.Server,
            startPositionMs: Long? = null,
            shouldPlay: Boolean = true,
        ) {
            currentVideo = video
            currentServer = server
            updatePlayerHeader()
            val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled
            val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled
            val needsReinit =
                extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder
            if (needsReinit) {
                initializePlayer(extraBuffering, softwareDecoder)
                player.playlistMetadata = MediaMetadata.Builder()
                    .setTitle(resolvePlayerTitle())
                    .setMediaServers(servers.map {
                        MediaServer(
                            id = it.id,
                            name = it.name,
                        )
                    })
                    .build()
            }

            val currentPosition = when {
                isLiveZapPending -> 0L
                startPositionMs != null -> startPositionMs
                else -> player.currentPosition
            }

            httpDataSource.setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to userAgent,
                ) + (video.headers ?: emptyMap())
            )
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(video.source.toUri())
                    .setMimeType(video.type)
                    .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                        MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                            .setMimeType(subtitle.mimeType ?: subtitle.file.toSubtitleMimeType())
                            .setLabel(subtitle.label)
                            .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                            .build()
                    })
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setMediaServerId(server.id)
                            .build()
                    )
                    .build()
            )

            binding.pvPlayer.controller.binding.btnExoExternalPlayer.setOnClickListener {
                val videoTitle = when (val type = currentVideoTypeForUi()) {
                    is Video.Type.Movie -> type.title
                    is Video.Type.Episode -> "${type.tvShow.title} • S${type.season.number} E${type.number}"
                }

                var sourceUri: Uri
                val mimeType = "video/*"

                val initialSource = video.source

                if (initialSource.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                    val playlistContent = decodeBase64Uri(initialSource)
                    val extractedUrl =
                        if (playlistContent != null) extractUrlFromPlaylist(playlistContent) else null

                    if (extractedUrl != null) {
                        sourceUri = extractedUrl.toUri()
                        Log.i("ExternalPlayer", "Link reale estratto TV: $sourceUri")
                    } else {
                        try {
                            val file = File(requireContext().cacheDir, "stream.m3u8")
                            FileOutputStream(file).use {
                                it.write(
                                    playlistContent?.toByteArray() ?: ByteArray(0)
                                )
                            }
                            sourceUri = FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.provider",
                                file
                            )
                        } catch (ignored: Exception) {
                            sourceUri = initialSource.toUri()
                        }
                    }
                } else {
                    sourceUri = initialSource.toUri()
                }

                Log.i("ExternalPlayer", "Avvio intent TV con URI: $sourceUri e MIME: $mimeType")

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(sourceUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    putExtra("title", videoTitle)
                    putExtra("position", player.currentPosition.toInt())
                    putExtra("return_result", true)

                    video.headers?.forEach { (key, value) ->
                        putExtra(key, value)
                    }

                    putExtra(
                        "extra_headers",
                        video.headers?.map { "${it.key}: ${it.value}" }?.toTypedArray()
                    )

                    if (video.headers != null) {
                        val headersArray =
                            video.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                        putExtra("headers", headersArray)
                    }
                }

                try {
                    val receiverIntent = Intent("ACTION_PLAYER_CHOSEN_TV").apply {
                        setPackage(requireContext().packageName)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        requireContext(), 0, receiverIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        startActivity(
                            Intent.createChooser(
                                intent,
                                getString(R.string.player_external_player_title),
                                pendingIntent.intentSender
                            )
                        )
                    } else {
                        startActivity(
                            Intent.createChooser(
                                intent,
                                getString(R.string.player_external_player_title)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e("ExternalPlayer", "Errore selettore app TV", e)
                    startActivity(
                        Intent.createChooser(
                            intent,
                            getString(R.string.player_external_player_title)
                        )
                    )
                }
            }

            playerListener?.let(player::removeListener)
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_READY) {
                        if (!::player.isInitialized) return

                        binding.pvPlayer.controller.binding.exoPlayPause.nextFocusDownId = -1

                        val preferredSubLang = UserPreferences.preferredSubtitleLanguage
                        if (!preferredSubLang.isNullOrEmpty()) {
                            val trackGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                            for (group in trackGroups) {
                                for (i in 0 until group.length) {
                                    val format = group.getTrackFormat(i)
                                    if (format.language.equals(preferredSubLang, ignoreCase = true)) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                            .setOverrideForType(
                                                androidx.media3.common.TrackSelectionOverride(
                                                    group.mediaTrackGroup,
                                                    i
                                                )
                                            )
                                            .build()
                                        return
                                    }
                                }
                            }
                        }
                        player.play()
                        updatePlayerScale()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    super.onTracksChanged(tracks)
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val videoTracks = videoGroups.sumOf { it.length }
                    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    for (group in textGroups) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val lang = group.getTrackFormat(i).language
                                if (!lang.isNullOrBlank() && lang != "und" && UserPreferences.preferredSubtitleLanguage != lang) {
                                    UserPreferences.preferredSubtitleLanguage = lang
                                }
                            }
                        }
                    }
                    val selectedHeights = buildList {
                        videoGroups.forEach { group ->
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    add(group.getTrackFormat(i).height)
                                }
                            }
                        }
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    updatePlayerScale()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.pvPlayer.keepScreenOn = isPlaying

                    if (isPlaying) {
                        viewModel.markVideoPlaying(currentServer)
                        failedServers.clear()
                        sourceRecoveryInProgress = false
                        hideSourceStatus()
                        recordRecentlyWatchedStart()
                        ensureIntroDbSegmentsLoaded()
                        startProgressHandler()
                    } else {
                        stopProgressHandler()
                    }
                    val hasUri = player.currentMediaItem?.localConfiguration?.uri
                        ?.toString()?.isNotEmpty()
                        ?: false

                    if (!isPlaying && hasUri) {
                        val videoType = currentVideoTypeForUi()
                        val watchItem: WatchItem? = when (videoType) {
                            is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                            is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                        }

                        val hasFinished = player.hasFinished()
                        when {
                            player.hasStarted() && !player.hasFinished() -> {
                                watchItem?.isWatched = false
                                watchItem?.watchedDate = null
                                watchItem?.watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                    lastPlaybackPositionMillis = player.currentPosition,
                                    durationMillis = player.duration,
                                )
                            }

                            hasFinished -> {
                                watchItem?.isWatched = true
                                watchItem?.watchedDate = Calendar.getInstance()
                                watchItem?.watchHistory = null


                            }
                        }

                        when (videoType) {
                            is Video.Type.Movie -> {
                                val provider = UserPreferences.currentProvider ?: return
                                (watchItem as? Movie)?.let {
                                    database.movieDao().update(it)
                                    UserDataCache.syncMovieToCache(requireContext(), provider, it)
                                }
                            }

                            is Video.Type.Episode -> {
                                val provider = UserPreferences.currentProvider ?: return
                                (watchItem as? Episode)?.let { episode ->
                                    if (hasFinished) {
                                        val watchedEpisodeIds = database.episodeDao()
                                            .getByTvShowId(videoType.tvShow.id)
                                            .filter { it.isWatched }
                                            .map { it.id }
                                        database.episodeDao().update(episode)
                                        database.episodeDao()
                                            .resetProgressionFromEpisode(videoType.id)
                                        database.episodeDao().getByIds(watchedEpisodeIds)
                                            .filterNot { it.isWatched }
                                            .forEach { CloudSyncHooks.episode(requireContext(), provider, it) }
                                        UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, episode.id)
                                        queueNextEpisodeForContinueWatching(provider)
                                    } else {
                                        database.episodeDao().update(episode)
                                        UserDataCache.syncEpisodeToCache(requireContext(), provider, episode)
                                    }

                                    episode.tvShow?.let { tvShow ->
                                        database.tvShowDao().getById(tvShow.id)
                                    }?.let { tvShow ->
                                        val episodeDao = database.episodeDao()
                                        val isStillWatching =
                                            episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                                        val updatedTvShow = tvShow.copy().apply {
                                            merge(tvShow)
                                            isWatching =
                                                !player.hasReallyFinished() || isStillWatching
                                        }
                                        database.tvShowDao().update(updatedTvShow)
                                        CloudSyncHooks.tvShow(requireContext(), provider, updatedTvShow)
                                    }
                                }
                            }

                        }
                        if (player.hasReallyFinished()) {
                            if (UserPreferences.autoplay) {
                                playNextEpisodeAcrossSeasons(autoplay = true)
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    Log.e("PlayerTvFragment", "onPlayerError: ", error)

                    if (error.cause?.javaClass?.simpleName == "ExoTimeoutException" ||
                        error.message?.contains("release timed out") == true
                    ) {
                        return
                    }

                    if (isSidecarSubtitleError(error)) {
                        Log.w(
                            "PlayerTvFragment",
                            "Sidecar subtitle failed; keeping the current video source",
                        )
                        return
                    }

                    if (sourceRecoveryInProgress) {
                        Log.d("PlayerTvFragment", "Ignoring duplicate error during source recovery")
                        return
                    }

                    val failedServer = currentServer ?: return
                    sourceRecoveryInProgress = true
                    if (isRetryablePlaybackError(error) &&
                        viewModel.retryVideoAfterPlaybackError(failedServer)
                    ) {
                        showSourceStatus(
                            getString(R.string.player_source_retrying, failedServer.name),
                            failedServer,
                        )
                    } else {
                        recoverFromFailedServer(failedServer, error)
                    }
                }
            }
            player.addListener(listener)
            playerListener = listener

            if (startPositionMs != null) {
                player.seekTo(startPositionMs)
            } else if (currentPosition == 0L) {
                val videoType = args.videoType
                val provider = UserPreferences.currentProvider
                
                val watchItem: WatchItem? = when (videoType) {
                    is Video.Type.Movie -> {
                        // Room is the authoritative source for resume state. The
                        // cache can lag behind the row that the season screen reads.
                        val cachedMovie = if (provider != null) {
                            UserDataCache.read(requireContext(), provider)?.continueWatchingMovies
                                ?.find { it.id == videoType.id }?.toMovie()
                        } else null
                        val databaseMovie = database.movieDao().getById(videoType.id)
                        databaseMovie ?: cachedMovie
                    }
                    is Video.Type.Episode -> {
                        val cachedEpisode = if (provider != null) {
                            UserDataCache.read(requireContext(), provider)?.continueWatchingEpisodes
                                ?.find { it.id == videoType.id }?.toEpisode()
                        } else null
                        val databaseEpisode = database.episodeDao().getById(videoType.id)
                        databaseEpisode ?: cachedEpisode
                    }
                }
                
                val lastPlaybackPositionMillis = watchItem?.watchHistory
                        ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

                player.seekTo(lastPlaybackPositionMillis ?: 0)
            } else {
                player.seekTo(currentPosition)
            }

            player.prepare()
            player.playWhenReady = shouldPlay
        }


        private fun ExoPlayer.hasStarted(): Boolean {
            return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
        }

        private fun recordRecentlyWatchedStart() {
            val playedAtMillis = System.currentTimeMillis()
            when (val videoType = currentVideoTypeForUi()) {
                is Video.Type.Movie -> {
                    if (database.movieDao().markRecentlyWatched(videoType.id, playedAtMillis) == 0) {
                        database.movieDao().insert(
                            Movie(
                                id = videoType.id,
                                title = videoType.title,
                                released = videoType.releaseDate,
                                poster = videoType.poster,
                                imdbId = videoType.imdbId,
                            ).apply {
                                lastPlayedAtMillis = playedAtMillis
                            }
                        )
                    }
                    UserPreferences.currentProvider?.let { provider ->
                        database.movieDao().getById(videoType.id)?.let { movie ->
                            CloudSyncHooks.movie(requireContext(), provider, movie)
                        }
                    }
                }

                is Video.Type.Episode -> {
                    val liveChannel = isLiveChannel(videoType)
                    val canonicalTitle = if (liveChannel) {
                        LiveChannelMetadata.canonicalName(videoType.tvShow.id, videoType.tvShow.title)
                    } else {
                        videoType.tvShow.title
                    }
                    val canonicalPoster = if (liveChannel) {
                        LiveChannelMetadata.canonicalLogo(canonicalTitle, videoType.tvShow.poster)
                    } else {
                        videoType.tvShow.poster
                    }
                    val existingTvShow = database.tvShowDao().getById(videoType.tvShow.id)
                    val currentTvShow = TvShow(
                        id = videoType.tvShow.id,
                        title = canonicalTitle,
                        released = videoType.tvShow.releaseDate,
                        poster = canonicalPoster,
                        banner = if (liveChannel) canonicalPoster else videoType.tvShow.banner,
                        imdbId = if (liveChannel) null else videoType.tvShow.imdbId,
                    )
                    val storedTvShow = if (liveChannel) {
                        existingTvShow?.let { existing ->
                            currentTvShow.isFavorite = existing.isFavorite
                            currentTvShow.favoritedAtMillis = existing.favoritedAtMillis
                            currentTvShow.isWatching = existing.isWatching
                            currentTvShow.lastPlayedAtMillis = existing.lastPlayedAtMillis
                            currentTvShow.lastPlayedEpisodeId = existing.lastPlayedEpisodeId
                        }
                        database.tvShowDao().insert(currentTvShow)
                        currentTvShow
                    } else {
                        existingTvShow ?: currentTvShow.apply {
                            lastPlayedAtMillis = playedAtMillis
                            lastPlayedEpisodeId = videoType.id
                            database.tvShowDao().insert(this)
                        }
                    }

                    val existingEpisode = database.episodeDao().getById(videoType.id)
                    val currentEpisode = Episode(
                        id = videoType.id,
                        number = videoType.number,
                        title = videoType.title,
                        poster = if (liveChannel) canonicalPoster else videoType.poster,
                        overview = videoType.overview,
                        tvShow = storedTvShow,
                        season = Season(
                            number = videoType.season.number,
                            title = videoType.season.title.orEmpty(),
                        )
                    )
                    if (liveChannel) {
                        existingEpisode?.let(currentEpisode::merge)
                        database.episodeDao().insert(currentEpisode)
                    } else if (existingEpisode == null) {
                        database.episodeDao().insert(currentEpisode)
                    }
                    if (videoType.season.title.equals("Watch", ignoreCase = true)) {
                        UserPreferences.currentProvider?.let { provider ->
                            database.episodeDao().getById(videoType.id)?.let { episode ->
                                if (episode.watchHistory == null) {
                                    episode.isWatched = false
                                    episode.watchedDate = null
                                    episode.watchHistory = WatchItem.WatchHistory(
                                        lastEngagementTimeUtcMillis = playedAtMillis,
                                        lastPlaybackPositionMillis = 0L,
                                        durationMillis = player.duration.takeIf { it > 0 } ?: 0L,
                                    )
                                    database.episodeDao().update(episode)
                                }
                                UserDataCache.syncEpisodeToCache(requireContext(), provider, episode)
                            }
                        }
                    }
                    database.tvShowDao().markRecentlyWatched(
                        id = videoType.tvShow.id,
                        episodeId = videoType.id,
                        playedAtMillis = playedAtMillis,
                    )
                    UserPreferences.currentProvider?.let { provider ->
                        database.tvShowDao().getById(videoType.tvShow.id)?.let { show ->
                            CloudSyncHooks.tvShow(requireContext(), provider, show)
                        }
                    }
                }
            }
        }

        private fun ExoPlayer.hasFinished(): Boolean {
            val completionBuffer = minOf(
                2.minutes.inWholeMilliseconds,
                maxOf(30.seconds.inWholeMilliseconds, (this.duration * 0.05).toLong()),
            )
            return this.duration > 0 &&
                    this.currentPosition >= (this.duration - completionBuffer)
        }

        private fun ExoPlayer.hasReallyFinished(): Boolean {
            return this.duration > 0 &&
                    this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
        }

        private fun currentVideoTypeForUi(): Video.Type {
            currentLiveChannelType?.let { return it }
            return when (val type = args.videoType) {
                is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
                    ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
                    ?.let { currentEpisode ->
                        if (currentEpisode.season.number > 0 || type.season.number <= 0) {
                            currentEpisode
                        } else {
                            currentEpisode.copy(season = type.season)
                        }
                    }
                    ?: type
                is Video.Type.Movie -> type
            }
        }

        private fun isLiveChannel(videoType: Video.Type): Boolean =
            videoType is Video.Type.Episode && videoType.season.title == "Live TV"

        private fun isIntroSkippingDisabled(): Boolean =
            UserPreferences.currentProvider is IptvProvider || isLiveChannel(currentVideoTypeForUi())

        private fun loadLiveZapChannels() {
            val current = currentLiveChannelType ?: return
            val providerName = UserPreferences.currentProvider?.name
            val queuedChannels = LiveChannelPlaybackQueue.get(providerName, current.id)
            if (queuedChannels.isNotEmpty()) {
                liveZapChannels = queuedChannels
                setupEpisodeNavigationButtons()
                return
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val favorites = database.tvShowDao().getFavorites().first()
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    liveZapChannels = favorites.map { favorite ->
                        LiveChannel(
                            id = favorite.id,
                            name = favorite.title,
                            logo = favorite.poster,
                            providerName = providerName,
                            favoritedAtMillis = favorite.favoritedAtMillis,
                            isFavorite = true,
                        )
                    }
                    setupEpisodeNavigationButtons()
                }
            }
        }

        private fun zapFavoriteLiveChannel(direction: Int): Boolean {
            if (isLiveZapPending) return false
            val current = currentLiveChannelType ?: return false
            val candidates = liveZapChannels
            if (candidates.none { channel -> channel.id != current.id }) return false

            val currentIndex = candidates.indexOfFirst { channel -> channel.id == current.id }
            val targetIndex = when {
                currentIndex < 0 && direction > 0 -> 0
                currentIndex < 0 -> candidates.lastIndex
                else -> (currentIndex + direction + candidates.size) % candidates.size
            }
            val target = candidates[targetIndex]
            if (target.id == current.id) return false

            liveZapFallbackType = current
            currentLiveChannelType = target.toVideoType()
            isLiveZapPending = true
            hideNextEpisodeOverlay()
            updatePlayerHeader()
            setupEpisodeNavigationButtons()
            binding.pvPlayer.showController()
            viewModel.playEpisode(currentLiveChannelType!!)
            return true
        }

        private fun LiveChannel.toVideoType() = Video.Type.Episode(
            id = id,
            number = 1,
            title = currentProgram?.title ?: name,
            poster = logo,
            overview = overview,
            tvShow = Video.Type.Episode.TvShow(
                id = id,
                title = name,
                poster = logo,
                banner = logo,
                releaseDate = null,
                imdbId = null,
                currentProgram = currentProgram?.title ?: name,
            ),
            season = Video.Type.Episode.Season(number = 1, title = "Live TV"),
        )

        private fun recoverFromFailedLiveZap(): Boolean {
            if (!isLiveZapPending) return false
            currentLiveChannelType = liveZapFallbackType ?: currentLiveChannelType
            liveZapFallbackType = null
            isLiveZapPending = false
            updatePlayerHeader()
            setupEpisodeNavigationButtons()
            val previousVideo = currentVideo
            val previousServer = currentServer
            if (previousVideo != null && previousServer != null) {
                displayVideo(previousVideo, previousServer, startPositionMs = 0L)
            }
            return true
        }

        private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
            return when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
            }
        }

        private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
            return when (videoType) {
                is Video.Type.Movie -> args.subtitle
                is Video.Type.Episode -> {
                    val episodeTitle = videoType.tvShow.currentProgram
                        ?: videoType.title?.takeUnless { it.isBlank() }
                        ?: args.subtitle
                    if (videoType.season.title == "Live TV") {
                        episodeTitle
                    } else {
                        "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
                    }
                }
            }
        }

        private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
            binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
            binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
        }

        private fun queueNextEpisodeForContinueWatching(provider: com.streamflixrevanced.streamflix.providers.Provider) {
            val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
            val episodeDao = database.episodeDao()
            val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
                isWatched = false
                watchedDate = null
                watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = 0L,
                    durationMillis = 0L,
                )
            } ?: Episode(
                id = nextEpisode.id,
                number = nextEpisode.number,
                title = nextEpisode.title,
                poster = nextEpisode.poster,
                overview = nextEpisode.overview,
                tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                    id = nextEpisode.tvShow.id,
                    title = nextEpisode.tvShow.title,
                    poster = nextEpisode.tvShow.poster,
                    banner = nextEpisode.tvShow.banner,
                ),
                season = Season(
                    number = nextEpisode.season.number,
                    title = nextEpisode.season.title,
                ),
            ).apply {
                isWatched = false
                watchedDate = null
                watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = 0L,
                    durationMillis = 0L,
                )
            }

            episodeDao.save(persistedNextEpisode)
            UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
        }

        private fun startProgressHandler() {
            progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
            progressRunnable = Runnable {
                if (player.isPlaying) {
                    updateIntroSkipping()
                    updateNextEpisodeOverlay()
                }
                progressHandler.postDelayed(progressRunnable, 1000)
            }
            progressHandler.post(progressRunnable)
        }

        private fun stopProgressHandler() {
            if (::progressHandler.isInitialized) {
                progressHandler.removeCallbacks(progressRunnable)
            }
        }

        private fun updateNextEpisodeOverlay() {
            val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
                hideNextEpisodeOverlay()
                return
            }
            val duration = player.duration.takeIf { it > 0 } ?: run {
                hideNextEpisodeOverlay()
                return
            }
            val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)
            val outroStartMs = activeIntroDbSegments()?.outro?.startMs
                ?.takeIf { it in 0L until duration }

            if (nextEpisodeOverlayDismissed) {
                hideNextEpisodeOverlay()
                return
            }

            val shouldPrefetch = remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS ||
                    outroStartMs?.let {
                        player.currentPosition >= (it - 15_000L).coerceAtLeast(0L)
                    } == true
            if (shouldPrefetch) {
                ensureNextEpisodePrepared(currentEpisode)
            }

            val nextEpisode = EpisodeManager.peekNextEpisode()
            val overlayThresholdMs = maxOf(
                NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
                UserPreferences.autoplayBuffer * 1000L
            )
            val shouldShowOverlay = outroStartMs?.let { player.currentPosition >= it }
                ?: (remainingMs <= overlayThresholdMs)
            if (nextEpisode == null || remainingMs == 0L || !shouldShowOverlay) {
                hideNextEpisodeOverlay()
                return
            }

            showNextEpisodeOverlay(nextEpisode, remainingMs)
        }

        private fun currentIntroDbEpisodeKey(
            episode: Video.Type.Episode? = currentVideoTypeForUi() as? Video.Type.Episode,
        ): String? {
            if (UserPreferences.currentProvider is IptvProvider) return null
            episode ?: return null
            if (episode.season.number < 1 || episode.number < 1) return null
            if (episode.season.title.equals("Live TV", ignoreCase = true)) return null
            return "${episode.tvShow.id}:${episode.season.number}:${episode.number}"
        }

        private fun ensureIntroDbSegmentsLoaded() {
            val episode = currentVideoTypeForUi() as? Video.Type.Episode ?: return
            val episodeKey = currentIntroDbEpisodeKey(episode) ?: run {
                introDbLoadJob?.cancel()
                introDbLookupEpisodeKey = null
                introDbSegments = null
                autoSkippedIntroEpisodeKey = null
                fallbackSkipIntroVisibleUntilPositionMs = null
                introDbLookupComplete = true
                return
            }
            if (introDbLookupEpisodeKey == episodeKey) return

            introDbLoadJob?.cancel()
            introDbLookupEpisodeKey = episodeKey
            introDbLookupComplete = false
            introDbSegments = null
            autoSkippedIntroEpisodeKey = null
            fallbackSkipIntroVisibleUntilPositionMs = null
            introDbLoadJob = lifecycleScope.launch(Dispatchers.IO) {
                val result = IntroDb.getSegments(
                    imdbId = episode.tvShow.imdbId,
                    season = episode.season.number,
                    episode = episode.number,
                    title = episode.tvShow.title,
                    year = episode.tvShow.releaseDate?.take(4)?.toIntOrNull(),
                    language = UserPreferences.currentProvider?.language,
                )
                result?.imdbId?.takeIf { episode.tvShow.imdbId.isNullOrBlank() }?.let { imdbId ->
                    database.tvShowDao().saveImdbId(
                        id = episode.tvShow.id,
                        title = episode.tvShow.title,
                        poster = episode.tvShow.poster,
                        banner = episode.tvShow.banner,
                        released = episode.tvShow.releaseDate,
                        imdbId = imdbId,
                    )
                    EpisodeManager.updateTvShowImdbId(episode.tvShow.id, imdbId)
                }
                val segments = result?.segments
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null || currentIntroDbEpisodeKey() != episodeKey) {
                        return@withContext
                    }
                    introDbSegments = segments
                    introDbLookupComplete = true
                    if (player.isPlaying) {
                        updateIntroSkipping()
                        updateNextEpisodeOverlay()
                    }
                }
            }
        }

        private fun activeIntroDbSegments(): IntroDb.Segments? =
            introDbSegments?.takeIf { introDbLookupEpisodeKey == currentIntroDbEpisodeKey() }

        private fun currentIntroDbSegment(): IntroDb.Segment? =
            activeIntroDbSegments()?.intro?.takeIf { it.contains(player.currentPosition) }

        private fun updateIntroSkipping() {
            if (isIntroSkippingDisabled()) {
                showSkipIntroButton(false)
                return
            }
            val episodeKey = currentIntroDbEpisodeKey()
            val intro = activeIntroDbSegments()?.intro
            if (intro == null) {
                val fallbackVisibleUntil = if (
                    !UserPreferences.autoSkipIntro && introDbLookupComplete
                ) {
                    fallbackSkipIntroVisibleUntilPositionMs
                        ?: (maxOf(player.currentPosition, FALLBACK_SKIP_INTRO_START_MS) +
                                FALLBACK_SKIP_INTRO_VISIBLE_DURATION_MS).also {
                            fallbackSkipIntroVisibleUntilPositionMs = it
                        }
                } else {
                    null
                }
                val showFallback = !UserPreferences.autoSkipIntro &&
                        introDbLookupComplete &&
                        player.currentPosition >= FALLBACK_SKIP_INTRO_START_MS &&
                        fallbackVisibleUntil != null &&
                        player.currentPosition < fallbackVisibleUntil
                showSkipIntroButton(showFallback)
                return
            }

            val isInsideIntro = intro.contains(player.currentPosition)
            if (UserPreferences.autoSkipIntro) {
                if (isInsideIntro && autoSkippedIntroEpisodeKey != episodeKey) {
                    autoSkippedIntroEpisodeKey = episodeKey
                    player.seekTo(intro.endMs)
                    Toast.makeText(
                        requireContext(),
                        R.string.player_intro_automatically_skipped,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                showSkipIntroButton(false)
                return
            }
            showSkipIntroButton(isInsideIntro)
        }

        private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
            if (EpisodeManager.peekNextEpisode() != null) return
            if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
                return
            }

            nextEpisodePrefetchTargetId = currentEpisode.id
            nextEpisodePrefetchJob?.cancel()
            nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
                val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
                withContext(Dispatchers.Main) {
                    if (!isAdded || _binding == null) return@withContext
                    setupEpisodeNavigationButtons()
                    if (loaded && player.isPlaying) {
                        updateNextEpisodeOverlay()
                    }
                }
            }
        }

        private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
            updateNextEpisodeOverlayFocusBindings(true)
            binding.tvNextEpisodeMeta.text = getString(
                R.string.tv_show_item_season_number_episode_number,
                nextEpisode.season.number,
                nextEpisode.number
            )
            binding.tvNextEpisodeTitle.text = nextEpisode.title
                ?: getString(R.string.episode_number, nextEpisode.number)
            binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
                getString(
                    R.string.player_next_episode_autoplay_in,
                    ((remainingMs + 999L) / 1000L).toInt()
                )
            } else {
                getString(R.string.player_next_episode_ready)
            }

            Glide.with(this)
            .load(nextEpisode.poster ?: nextEpisode.tvShow.poster ?: R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivNextEpisodePoster)

            if (binding.layoutNextEpisodeOverlay.isGone) {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_in
                )
                updateNextEpisodeOverlayAlpha(
                    binding.btnNextEpisodeAction.hasFocus() || binding.btnNextEpisodeDismiss.hasFocus()
                )
                binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
                binding.layoutNextEpisodeOverlay.isVisible = true
                binding.btnNextEpisodeAction.post {
                    if (_binding == null || !binding.layoutNextEpisodeOverlay.isVisible) return@post
                    binding.btnNextEpisodeAction.requestFocus()
                }
            }
        }

        private fun hideNextEpisodeOverlay() {
            if (_binding == null) return
            updateNextEpisodeOverlayFocusBindings(false)
            if (binding.layoutNextEpisodeOverlay.isVisible) {
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_out
                )
                binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
                binding.layoutNextEpisodeOverlay.isGone = true
            }
        }

        private fun updateNextEpisodeOverlayAlpha(hasFocus: Boolean) {
            if (_binding == null) return
            binding.layoutNextEpisodeOverlay.alpha =
                if (hasFocus) NEXT_EPISODE_OVERLAY_ALPHA_FOCUSED
                else NEXT_EPISODE_OVERLAY_ALPHA_UNFOCUSED
        }

        private fun updateNextEpisodeOverlayFocusBindings(overlayVisible: Boolean) {
            val controllerBinding = binding.pvPlayer.controller.binding
            val overlayActionId = binding.btnNextEpisodeAction.id
            val overlayDismissId = binding.btnNextEpisodeDismiss.id

            controllerBinding.exoSettings.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.btnExoAspectRatio.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            controllerBinding.exoProgress.nextFocusUpId = View.NO_ID
            controllerBinding.btnCustomNext.nextFocusDownId = R.id.exo_progress
            controllerBinding.exoPlayPause.nextFocusDownId = R.id.exo_progress

            binding.btnSkipIntroOverlay.nextFocusLeftId = if (overlayVisible) overlayActionId else View.NO_ID
            binding.btnSkipIntroOverlay.nextFocusUpId = if (overlayVisible) overlayActionId else View.NO_ID
            binding.btnSkipIntroOverlay.nextFocusDownId = if (overlayVisible) overlayActionId else View.NO_ID

            binding.btnNextEpisodeAction.nextFocusLeftId = overlayDismissId
            binding.btnNextEpisodeAction.nextFocusRightId = overlayDismissId
            binding.btnNextEpisodeAction.nextFocusUpId = controllerBinding.exoPlayPause.id
            binding.btnNextEpisodeAction.nextFocusDownId =
                if (binding.btnSkipIntroOverlay.isVisible) binding.btnSkipIntroOverlay.id
                else controllerBinding.exoSettings.id

            binding.btnNextEpisodeDismiss.nextFocusLeftId = overlayActionId
            binding.btnNextEpisodeDismiss.nextFocusRightId = overlayActionId
            binding.btnNextEpisodeDismiss.nextFocusUpId = controllerBinding.exoPlayPause.id
            binding.btnNextEpisodeDismiss.nextFocusDownId =
                if (binding.btnSkipIntroOverlay.isVisible) binding.btnSkipIntroOverlay.id
                else controllerBinding.exoSettings.id
        }

        private fun showSkipIntroButton(show: Boolean) {
            val btnSkipIntro = binding.btnSkipIntroOverlay
            val shouldShow = show && !isIntroSkippingDisabled()
            if (shouldShow && btnSkipIntro.isGone) {
                val fadeIn = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_in
                )
                binding.pvPlayer.hideController()
                btnSkipIntro.startAnimation(fadeIn)
                btnSkipIntro.isVisible = true
                btnSkipIntro.bringToFront()
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
                btnSkipIntro.requestFocus()
                btnSkipIntro.post {
                    if (
                        _binding != null &&
                        btnSkipIntro.isVisible &&
                        !UserPreferences.autoSkipIntro
                    ) {
                        if (!btnSkipIntro.requestFocus()) {
                            btnSkipIntro.requestFocusFromTouch()
                        }
                    }
                }
            } else if (!shouldShow && btnSkipIntro.isVisible) {
                val fadeOut = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(),
                    R.anim.fade_out
                )
                btnSkipIntro.startAnimation(fadeOut)
                btnSkipIntro.isGone = true
                if (binding.layoutNextEpisodeOverlay.isVisible) {
                    updateNextEpisodeOverlayFocusBindings(true)
                }
            }
        }

        private var currentExtraBuffering = false
        private var currentSoftwareDecoder = false

        private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()

            val renderersFactory = SubtitleOffsetRenderersFactory(requireContext()).apply {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 || currentSoftwareDecoder) {
                    setEnableDecoderFallback(true)
                    if (currentSoftwareDecoder) {
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    }
                }
            }
            val baseBuilder = ExoPlayer.Builder(requireContext(), renderersFactory)

            return baseBuilder
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build()
        }

        private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder) {
            releasePlayer()
            currentExtraBuffering = extraBuffering
            currentSoftwareDecoder = softwareDecoder

            var tokenLogged = false
            val okHttpClient = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .addInterceptor { chain ->
                    var request = chain.request()
                    
                    if (currentVideo?.maintainToken == true) {
                        val latestQuery = TokenManager.latestQuery
                        if (latestQuery != null) {
                            val origHttpUrl = request.url
                            val updatedHttpUrl = origHttpUrl.newBuilder().query(latestQuery).build()
                            request = request.newBuilder().url(updatedHttpUrl).build()
                            if (!tokenLogged) {
                                android.util.Log.d("TokenManager", "[TV-INTERCEPTOR] Token successfully injected (applied to all segments)")
                                tokenLogged = true
                            }
                        } else {
                            android.util.Log.w("TokenManager", "[TV-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                        }
                    }
                    
                    chain.proceed(request)
                }
                .build()
            httpDataSource = OkHttpDataSource.Factory(okHttpClient)

            dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

            player = buildPlayer(extraBuffering).also { player ->
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )

                    val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                    if (lang == "es") {
                        player.trackSelectionParameters =
                            player.trackSelectionParameters.buildUpon()
                                .setPreferredAudioLanguage("spa")
                                .build()
                    }

                    mediaSession = MediaSession.Builder(requireContext(), player)
                        .build()
                }

            binding.pvPlayer.player = player
            binding.settings.player = player
            binding.settings.subtitleView = binding.pvPlayer.subtitleView
            binding.settings.onSubtitlesClicked = {
                viewModel.getSubtitles(args.videoType)
            }
        }

        private fun releasePlayer() {
            stopProgressHandler()
            binding.pvPlayer.player = null
            binding.settings.player = null
            binding.settings.subtitleView = null
            if (::player.isInitialized) {
                player.release()
            }
            if (::mediaSession.isInitialized) {
                mediaSession.release()
            }
        }

            // 🔴 restore episode context BEFORE reload
    private fun shouldAutoAdvanceServer(server: Video.Server): Boolean {
        val host = runCatching { Uri.parse(server.src).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return host != "powvideo.org" &&
            host != "powwideo.org" &&
            host != "streamplay.to" &&
            host != "straemplay.org"
    }


    }
