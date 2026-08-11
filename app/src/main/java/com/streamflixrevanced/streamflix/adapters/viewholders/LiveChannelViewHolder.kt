package com.streamflixrevanced.streamflix.adapters.viewholders

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.databinding.ItemLiveChannelMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemLiveChannelSwiperMobileBinding
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.Video
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixrevanced.streamflix.ui.ShowOptionsTvDialog
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.TvLogoRepository
import com.streamflixrevanced.streamflix.utils.LiveChannelPlaybackQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope

class LiveChannelViewHolder(
    private val binding: ViewBinding,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(
        channel: LiveChannel,
        onClick: ((LiveChannel) -> Unit)? = null,
        zappingChannels: (() -> List<LiveChannel>)? = null,
    ) {
        // This holder is shared by mobile and TV layouts. Keep it focusable for
        // D-pad navigation, but do not let the first touchscreen tap be spent
        // moving focus onto the card instead of opening the stream.
        binding.root.isFocusableInTouchMode = false

        when (binding) {
            is ItemLiveChannelMobileBinding -> {
                bindCard(
                    binding.ivLiveChannelLogo,
                    binding.ivLiveChannelFavorite,
                    binding.tvLiveChannelName,
                    binding.tvLiveChannelCurrent,
                    binding.tvLiveChannelNext,
                    binding.pbLiveChannelProgress,
                    channel,
                )
            }
            is ItemLiveChannelSwiperMobileBinding -> {
                bindPoster(binding.ivLiveChannelLogo, channel)
                binding.tvLiveChannelName.text = TvLogoRepository.displayName(channel.name)
                binding.tvLiveChannelCurrent.text = currentText(channel)
                binding.tvLiveChannelNext.text = nextText(channel)
                binding.pbLiveChannelProgress.apply {
                    isVisible = channel.progressPercent != null
                    progress = channel.progressPercent ?: 0
                }
                binding.btnLiveChannelWatch.setOnClickListener {
                    openPlayer(channel, onClick, zappingChannels)
                }
            }
        }

        binding.root.setOnClickListener { openPlayer(channel, onClick, zappingChannels) }
        binding.root.setOnLongClickListener {
            when (channel.itemType) {
                AppAdapter.Type.LIVE_CHANNEL_TV_ITEM,
                AppAdapter.Type.LIVE_CHANNEL_GRID_TV_ITEM -> ShowOptionsTvDialog(binding.root.context, channel).show()
                else -> ShowOptionsMobileDialog(binding.root.context, channel).show()
            }
            true
        }
    }

    private fun bindCard(
        logo: android.widget.ImageView,
        favorite: android.widget.ImageView,
        name: android.widget.TextView,
        current: android.widget.TextView,
        next: android.widget.TextView,
        progress: android.widget.ProgressBar,
        channel: LiveChannel,
    ) {
        bindPoster(logo, channel)
        favorite.isVisible = channel.isFavorite
        favorite.setOnClickListener { toggleFavorite(channel) }
        name.text = TvLogoRepository.displayName(channel.name)
        current.text = currentText(channel)
        next.text = nextText(channel)
        progress.isVisible = channel.progressPercent != null
        progress.progress = channel.progressPercent ?: 0
    }

    private fun toggleFavorite(channel: LiveChannel) {
        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val database = AppDatabase.getInstance(binding.root.context)
            val existing = database.tvShowDao().getById(channel.id)
            val newValue = !(existing?.isFavorite ?: channel.isFavorite)
            val saved = existing ?: TvShow(
                id = channel.id,
                title = channel.name,
                poster = channel.logo,
                banner = channel.logo,
                overview = channel.overview,
            )
            database.tvShowDao().upsertFavorite(saved, newValue)
            UserPreferences.currentProvider?.let { provider ->
                database.tvShowDao().getById(channel.id)?.let { persisted ->
                    com.streamflixrevanced.streamflix.utils.UserDataCache.syncTvShowToCache(
                        binding.root.context,
                        provider,
                        persisted,
                    )
                }
            }
            channel.isFavorite = newValue
            channel.favoritedAtMillis = if (newValue) System.currentTimeMillis() else null
            owner.lifecycleScope.launch(Dispatchers.Main) {
                binding.root.findViewById<View>(R.id.iv_live_channel_favorite)?.isVisible = newValue
            }
        }
    }

    private fun bindPoster(view: View, channel: LiveChannel) {
        val repositoryLogos = TvLogoRepository.urls(
            channel.name,
            TvLogoRepository.countryCode(channel.providerName ?: UserPreferences.currentProvider?.name),
        )
        // Prefer the canonical repository match over a Vavoo variant-specific
        // URL. This makes "13th Street .c" and "13th Street .s" share one logo.
        loadPoster(view as android.widget.ImageView, repositoryLogos + listOfNotNull(channel.logo))
    }

    private fun currentText(channel: LiveChannel): String {
        val context = binding.root.context
        val program = channel.currentProgram?.title
            ?: context.getString(R.string.live_channel_no_program)
        return context.getString(R.string.live_channel_now, program)
    }

    private fun nextText(channel: LiveChannel): String {
        val context = binding.root.context
        return context.getString(
            if (channel.nextProgram == null) R.string.live_channel_next_unavailable
            else R.string.live_channel_next,
            channel.nextProgram?.title ?: "",
        )
    }

    private fun loadPoster(imageView: android.widget.ImageView, urls: List<String>, index: Int = 0) {
        val url = urls.getOrNull(index)
        Glide.with(imageView)
            .load(url)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (index + 1 < urls.size) {
                        imageView.post {
                            loadPoster(imageView, urls, index + 1)
                        }
                        return true
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .error(R.drawable.glide_fallback_cover)
            .into(imageView)
    }

    private fun openPlayer(
        channel: LiveChannel,
        onClick: ((LiveChannel) -> Unit)?,
        zappingChannels: (() -> List<LiveChannel>)?,
    ) {
        onClick?.let {
            it(channel)
            return
        }

        LiveChannelPlaybackQueue.set(
            providerName = channel.providerName ?: UserPreferences.currentProvider?.name,
            channels = zappingChannels?.invoke().orEmpty().ifEmpty { listOf(channel) },
        )

        val currentTitle = channel.currentProgram?.title ?: channel.name
        val videoType = Video.Type.Episode(
            id = channel.id,
            number = 1,
            title = currentTitle,
            poster = channel.logo,
            overview = channel.nextProgram?.let {
                binding.root.context.getString(R.string.live_channel_next, it.title)
            },
            tvShow = Video.Type.Episode.TvShow(
                id = channel.id,
                title = channel.name,
                poster = channel.logo,
                banner = channel.logo,
                releaseDate = null,
                imdbId = null,
                currentProgram = currentTitle,
            ),
            season = Video.Type.Episode.Season(number = 1, title = "Live TV"),
        )
        binding.root.findNavController().navigate(R.id.player, Bundle().apply {
            putString("id", channel.id)
            putString("title", channel.name)
            putString("subtitle", currentTitle)
            putSerializable("videoType", videoType)
        })
    }
}
