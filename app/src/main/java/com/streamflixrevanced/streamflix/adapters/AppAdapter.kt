package com.streamflixrevanced.streamflix.adapters

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.streamflixrevanced.streamflix.adapters.viewholders.CategoryViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.EpisodeViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.GenreViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.MovieViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.PeopleViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.ProviderViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.SeasonViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.TvShowViewHolder
import com.streamflixrevanced.streamflix.adapters.viewholders.LiveChannelViewHolder
import com.streamflixrevanced.streamflix.databinding.ContentCategorySwiperMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentCategorySwiperTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieCastMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieCastTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieDirectorsMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieDirectorsTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieRecommendationsMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieRecommendationsTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentMovieTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowCastMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowCastTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowDirectorsMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowDirectorsTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowRecommendationsMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowRecommendationsTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowSeasonsMobileBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowSeasonsTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentTvShowTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemCategoryMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemCategorySwiperMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemCategoryTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemEpisodeContinueWatchingTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemEpisodeMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemEpisodeTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemFavoriteSectionHeaderBinding
import com.streamflixrevanced.streamflix.databinding.ItemGenreGridMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemGenreGridTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemLoadingBinding
import com.streamflixrevanced.streamflix.databinding.ItemLiveChannelMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemLiveChannelSwiperMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemMovieGridMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemMovieGridTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemMovieMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemMovieTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemPeopleMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemPeopleTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemProviderMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemProviderTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemSeasonMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemSeasonTvBinding
import com.streamflixrevanced.streamflix.databinding.ItemTvShowGridBinding
import com.streamflixrevanced.streamflix.databinding.ItemTvShowGridMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemTvShowMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemTvShowTvBinding
import com.streamflixrevanced.streamflix.models.Category
import com.streamflixrevanced.streamflix.models.Episode
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.models.Movie
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.People
import com.streamflixrevanced.streamflix.models.Provider
import com.streamflixrevanced.streamflix.models.Season
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.fragments.favorites.FavoriteSectionHeader
import java.util.IdentityHashMap

class AppAdapter(
    val items: MutableList<Item> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
    }

    init {
        setHasStableIds(true)
    }

    // --- LISTENERS AÑADIDOS AQUÍ ---
    var onMovieClickListener: ((Movie) -> Unit)? = null
    var onTvShowClickListener: ((TvShow) -> Unit)? = null
    var onMovieLongClickListener: ((Movie) -> Unit)? = null
    var onTvShowLongClickListener: ((TvShow) -> Unit)? = null
    var onMovieKeyListener: ((Movie, KeyEvent) -> Boolean)? = null
    var onTvShowKeyListener: ((TvShow, KeyEvent) -> Boolean)? = null
    var onLiveChannelClickListener: ((LiveChannel) -> Unit)? = null
    var isItemSelectedListener: ((Item) -> Boolean)? = null
    var onGenreClickListener: ((Genre) -> Unit)? = null
    var onPeopleClickListener: ((People) -> Unit)? = null
    var onEpisodeClickListener: ((Episode) -> Unit)? = null
    var onSeasonClickListener: ((Season) -> Unit)? = null
    var onProviderClickListener: ((Provider) -> Unit)? = null
    // ---------------------------------
    interface Item {
        var itemType: Type
    }

    enum class Type {
        CATEGORY_MOBILE_ITEM,
        CATEGORY_TV_ITEM,

        CATEGORY_MOBILE_SWIPER,
        CATEGORY_TV_SWIPER,

        EPISODE_MOBILE_ITEM,
        EPISODE_TV_ITEM,
        EPISODE_CONTINUE_WATCHING_MOBILE_ITEM,
        EPISODE_CONTINUE_WATCHING_TV_ITEM,

        FOOTER,

        FAVORITE_SECTION_HEADER,

        GENRE_GRID_MOBILE_ITEM,
        GENRE_GRID_TV_ITEM,

        HEADER,

        LOADING_ITEM,

        MOVIE_MOBILE_ITEM,
        MOVIE_TV_ITEM,
        MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
        MOVIE_CONTINUE_WATCHING_TV_ITEM,
        MOVIE_GRID_MOBILE_ITEM,
        MOVIE_GRID_TV_ITEM,
        MOVIE_SWIPER_MOBILE_ITEM,

        MOVIE_MOBILE,
        MOVIE_TV,
        MOVIE_DIRECTORS_MOBILE,
        MOVIE_DIRECTORS_TV,
        MOVIE_CAST_MOBILE,
        MOVIE_CAST_TV,
        MOVIE_RECOMMENDATIONS_MOBILE,
        MOVIE_RECOMMENDATIONS_TV,

        PEOPLE_MOBILE_ITEM,
        PEOPLE_TV_ITEM,

        PROVIDER_MOBILE_ITEM,
        PROVIDER_TV_ITEM,

        SEASON_MOBILE_ITEM,
        SEASON_TV_ITEM,

        TV_SHOW_MOBILE_ITEM,
        TV_SHOW_TV_ITEM,
        TV_SHOW_CONTINUE_WATCHING_MOBILE_ITEM,
        TV_SHOW_CONTINUE_WATCHING_TV_ITEM,
        TV_SHOW_GRID_MOBILE_ITEM,
        TV_SHOW_GRID_TV_ITEM,
        TV_SHOW_SWIPER_MOBILE_ITEM,

        TV_SHOW_MOBILE,
        TV_SHOW_TV,
        TV_SHOW_SEASONS_MOBILE,
        TV_SHOW_SEASONS_TV,
        TV_SHOW_DIRECTORS_MOBILE,
        TV_SHOW_DIRECTORS_TV,
        TV_SHOW_CAST_MOBILE,
        TV_SHOW_CAST_TV,
        TV_SHOW_RECOMMENDATIONS_MOBILE,
        TV_SHOW_RECOMMENDATIONS_TV,

        LIVE_CHANNEL_MOBILE_ITEM,
        LIVE_CHANNEL_TV_ITEM,
        LIVE_CHANNEL_GRID_MOBILE_ITEM,
        LIVE_CHANNEL_GRID_TV_ITEM,
        LIVE_CHANNEL_SWIPER_MOBILE_ITEM,
    }

    private data class NestedState(
        val layoutState: Parcelable?,
        val focusAnchor: CategoryViewHolder.ChildFocusAnchor? = null,
    )

    private val states = mutableMapOf<String, NestedState>()
    private val boundIdentities = IdentityHashMap<RecyclerView.ViewHolder, String>()
    private var itemIdentities: List<String> = emptyList()
    private var itemIdentityCounts: MutableMap<String, Int> = mutableMapOf()
    private var itemStableIds: LongArray = longArrayOf()

    var isLoading = false
    private var header: Header<ViewBinding>? = null
    private var onLoadMoreListener: (() -> Unit)? = null
    private var footer: Footer<ViewBinding>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (Type.entries[viewType]) {
            Type.CATEGORY_MOBILE_ITEM -> CategoryViewHolder(
                ItemCategoryMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.CATEGORY_TV_ITEM -> CategoryViewHolder(
                ItemCategoryTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.CATEGORY_MOBILE_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.CATEGORY_TV_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.EPISODE_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.FOOTER -> FooterViewHolder(
                footer!!.binding(parent)
            )
            Type.FAVORITE_SECTION_HEADER -> FavoriteSectionHeaderViewHolder(
                ItemFavoriteSectionHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.GENRE_GRID_MOBILE_ITEM -> GenreViewHolder(
                ItemGenreGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.GENRE_GRID_TV_ITEM -> GenreViewHolder(
                ItemGenreGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.HEADER -> HeaderViewHolder(
                header!!.binding(parent)
            )

            Type.LOADING_ITEM -> LoadingViewHolder(
                ItemLoadingBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
            Type.MOVIE_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
            Type.MOVIE_TV_ITEM -> MovieViewHolder(
                ItemMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_GRID_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_GRID_TV_ITEM -> MovieViewHolder(
                ItemMovieGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_SWIPER_MOBILE_ITEM -> MovieViewHolder(
                ItemCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.MOVIE_MOBILE -> MovieViewHolder(
                ContentMovieMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_TV -> MovieViewHolder(
                ContentMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_DIRECTORS_MOBILE -> MovieViewHolder(
                ContentMovieDirectorsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_DIRECTORS_TV -> MovieViewHolder(
                ContentMovieDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CAST_MOBILE -> MovieViewHolder(
                ContentMovieCastMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CAST_TV -> MovieViewHolder(
                ContentMovieCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_RECOMMENDATIONS_MOBILE -> MovieViewHolder(
                ContentMovieRecommendationsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_RECOMMENDATIONS_TV -> MovieViewHolder(
                ContentMovieRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PEOPLE_MOBILE_ITEM -> PeopleViewHolder(
                ItemPeopleMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.PEOPLE_TV_ITEM -> PeopleViewHolder(
                ItemPeopleTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PROVIDER_MOBILE_ITEM -> ProviderViewHolder(
                ItemProviderMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.PROVIDER_TV_ITEM -> ProviderViewHolder(
                ItemProviderTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.SEASON_MOBILE_ITEM -> SeasonViewHolder(
                ItemSeasonMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.SEASON_TV_ITEM -> SeasonViewHolder(
                ItemSeasonTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.TV_SHOW_MOBILE_ITEM,
            Type.TV_SHOW_CONTINUE_WATCHING_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_TV_ITEM,
            Type.TV_SHOW_CONTINUE_WATCHING_TV_ITEM -> TvShowViewHolder(
                ItemTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_GRID_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowGridMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_GRID_TV_ITEM -> TvShowViewHolder(
                ItemTvShowGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_SWIPER_MOBILE_ITEM -> TvShowViewHolder(
                ItemCategorySwiperMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.TV_SHOW_MOBILE -> TvShowViewHolder(
                ContentTvShowMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_TV -> TvShowViewHolder(
                ContentTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_SEASONS_MOBILE -> TvShowViewHolder(
                ContentTvShowSeasonsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_SEASONS_TV -> TvShowViewHolder(
                ContentTvShowSeasonsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_DIRECTORS_MOBILE -> TvShowViewHolder(
                ContentTvShowDirectorsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_DIRECTORS_TV -> TvShowViewHolder(
                ContentTvShowDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_CAST_MOBILE -> TvShowViewHolder(
                ContentTvShowCastMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_CAST_TV -> TvShowViewHolder(
                ContentTvShowCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_MOBILE -> TvShowViewHolder(
                ContentTvShowRecommendationsMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_TV -> TvShowViewHolder(
                ContentTvShowRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.LIVE_CHANNEL_MOBILE_ITEM -> LiveChannelViewHolder(
                ItemLiveChannelMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            Type.LIVE_CHANNEL_TV_ITEM -> LiveChannelViewHolder(
                ItemLiveChannelMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            Type.LIVE_CHANNEL_GRID_MOBILE_ITEM -> LiveChannelViewHolder(
                ItemLiveChannelMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            Type.LIVE_CHANNEL_GRID_TV_ITEM -> LiveChannelViewHolder(
                ItemLiveChannelMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            Type.LIVE_CHANNEL_SWIPER_MOBILE_ITEM -> LiveChannelViewHolder(
                ItemLiveChannelSwiperMobileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position >= itemCount - 5 && !isLoading) {
            onLoadMoreListener?.invoke()
            isLoading = true
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        val identity = itemIdentities.getOrNull(adjustedPosition)
        if (identity != null) {
            boundIdentities[holder] = identity
        } else {
            boundIdentities.remove(holder)
        }
        when (holder) {
            is CategoryViewHolder -> holder.bind(
                items[adjustedPosition] as Category,
                onMovieClickListener,
                onTvShowClickListener,
                onMovieLongClickListener,
                onTvShowLongClickListener,
            )
            is EpisodeViewHolder -> holder.bind(
                items[adjustedPosition] as Episode
            ) // Tu original no pasaba listener, lo respeto
            is FooterViewHolder -> footer?.bind?.invoke(holder.binding)
            is FavoriteSectionHeaderViewHolder -> holder.bind(
                items[adjustedPosition] as FavoriteSectionHeader
            )
            is GenreViewHolder -> holder.bind(
                items[adjustedPosition] as Genre,
                onGenreClickListener,
            )
            is HeaderViewHolder -> header?.bind?.invoke(holder.binding)
            is MovieViewHolder -> holder.bind(
                items[adjustedPosition] as Movie,
                onMovieClickListener,
                onMovieLongClickListener,
                onMovieKeyListener,
                isItemSelectedListener?.invoke(items[adjustedPosition]) == true,
            ) // Los listeners se manejan dentro del ViewHolder
            is PeopleViewHolder -> holder.bind(
                items[adjustedPosition] as People
            ) // Tu original no pasaba listener, lo respeto
            is ProviderViewHolder -> holder.bind(
                items[adjustedPosition] as Provider
            ) // Tu original no pasaba listener, lo respeto
            is SeasonViewHolder -> holder.bind(
                items[adjustedPosition] as Season
            ) // Tu original no pasaba listener, lo respeto
            is TvShowViewHolder -> holder.bind(
                items[adjustedPosition] as TvShow,
                onTvShowClickListener,
                onTvShowLongClickListener,
                onTvShowKeyListener,
                isItemSelectedListener?.invoke(items[adjustedPosition]) == true,
            ) // Los listeners se manejan dentro del ViewHolder
            is LiveChannelViewHolder -> holder.bind(
                items[adjustedPosition] as LiveChannel,
                onLiveChannelClickListener,
                zappingChannels = { items.filterIsInstance<LiveChannel>() },
            )
        }

        // Restored state is single-use. Keeping it around would re-apply an
        // old scroll/focus position on every later metadata-only rebind.
        val state = identity?.let(states::remove)
        if (state != null) {
            when (holder) {
                is CategoryViewHolder -> {
                    holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state.layoutState)
                    state.focusAnchor?.let(holder::restoreChildFocus)
                }
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state.layoutState)
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state.layoutState)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (PAYLOAD_SELECTION in payloads) {
            val adjustedPosition = header?.let { position - 1 } ?: position
            val selected = items.getOrNull(adjustedPosition)
                ?.let { isItemSelectedListener?.invoke(it) }
                ?: false
            when (holder) {
                is MovieViewHolder -> holder.setItemSelected(selected)
                is TvShowViewHolder -> holder.setItemSelected(selected)
                else -> super.onBindViewHolder(holder, position, payloads)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun notifyItemSelectionChanged(position: Int) {
        if (position in items.indices) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    override fun getItemCount(): Int = items.size +
            (header?.let { 1 } ?: 0) +
            (onLoadMoreListener?.let { 1 } ?: 0) +
            (footer?.let { 1 } ?: 0)

    override fun getItemId(position: Int): Long {
        if (header != null && position == 0) return Long.MIN_VALUE

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in itemStableIds.indices) {
            return itemStableIds[adjustedPosition]
        }

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Long.MIN_VALUE + 1
        }

        if (footer != null && position == itemCount - 1) {
            return Long.MIN_VALUE + 2
        }

        return RecyclerView.NO_ID
    }

    override fun getItemViewType(position: Int): Int {
        if (header != null && position == 0) {
            return Type.HEADER.ordinal
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in items.indices) {
            return items[adjustedPosition].itemType.ordinal
        }

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Type.LOADING_ITEM.ordinal
        }

        if (footer != null && position == itemCount - 1) {
            return Type.FOOTER.ordinal
        }

        return Type.LOADING_ITEM.ordinal
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)

        val identity = boundIdentities.remove(holder) ?: return

        val layoutState = when (holder) {
            is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            else -> null
        }
        val focusAnchor = (holder as? CategoryViewHolder)?.captureChildFocus()

        if (layoutState != null || focusAnchor != null) {
            states[identity] = NestedState(layoutState, focusAnchor)
        } else {
            states.remove(identity)
        }
    }

    fun onSaveInstanceState(recyclerView: RecyclerView) {
        val headerOffset = if (header != null) 1 else 0
        for (itemPosition in items.indices) {
            val holder = recyclerView.findViewHolderForAdapterPosition(itemPosition + headerOffset)
                ?: continue
            val identity = itemIdentities.getOrNull(itemPosition) ?: continue

            val layoutState = when (holder) {
                is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                else -> null
            }
            val focusAnchor = (holder as? CategoryViewHolder)?.captureChildFocus()

            if (layoutState != null || focusAnchor != null) {
                states[identity] = NestedState(layoutState, focusAnchor)
            } else {
                states.remove(identity)
            }
        }
    }


    fun submitList(list: List<Item>): Boolean {
        val oldItems = items.toList()
        val newItemCount = list.size

        if (oldItems.isNotEmpty() &&
            oldItems.size <= newItemCount &&
            oldItems == list.subList(0, oldItems.size)
        ) {
            val appendedItems = list.subList(oldItems.size, newItemCount)
            if (appendedItems.isEmpty()) {
                return false
            }

            val appendedIdentityState = appendedItems.buildIdentityState(itemIdentityCounts)

            items.addAll(appendedItems)
            itemIdentities = itemIdentities + appendedIdentityState.identities
            itemIdentityCounts = appendedIdentityState.counts
            itemStableIds = itemStableIds + appendedIdentityState.stableIds

            notifyItemRangeInserted(
                oldItems.size + (header?.let { 1 } ?: 0),
                appendedItems.size
            )
            return true
        }

        val oldIdentities = itemIdentities
        val newIdentityState = list.buildIdentityState()
        val newIdentities = newIdentityState.identities

        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size

            override fun getNewListSize() = list.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = list[newItemPosition]
                return oldIdentities.getOrNull(oldItemPosition) == newIdentities.getOrNull(newItemPosition) &&
                        oldItem::class == newItem::class
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = list[newItemPosition]
                return oldItem == newItem
            }
        })

        items.clear()
        items.addAll(list)
        itemIdentities = newIdentities
        itemIdentityCounts = newIdentityState.counts
        itemStableIds = newIdentityState.stableIds
        states.keys.retainAll(newIdentities.toSet())
        result.dispatchUpdatesTo(this)
        return true
    }

    /**
     * Replaces structurally unrelated content without emitting individual DiffUtil
     * removals. This is used while a Leanback grid is detached so its layout model
     * cannot retain positions from the previous dataset.
     */
    fun replaceListWithoutDiff(list: List<Item>) {
        val identityState = list.buildIdentityState()
        states.clear()
        boundIdentities.clear()
        items.clear()
        items.addAll(list)
        itemIdentities = identityState.identities
        itemIdentityCounts = identityState.counts
        itemStableIds = identityState.stableIds
        notifyDataSetChanged()
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in items.indices || toPosition !in items.indices || fromPosition == toPosition) return

        java.util.Collections.swap(items, fromPosition, toPosition)

        itemIdentities = itemIdentities.toMutableList().also {
            java.util.Collections.swap(it, fromPosition, toPosition)
        }
        val stableId = itemStableIds[fromPosition]
        itemStableIds[fromPosition] = itemStableIds[toPosition]
        itemStableIds[toPosition] = stableId

        notifyItemMoved(fromPosition, toPosition)
    }

    fun replaceItemOrder(newItems: List<Item>) {
        if (newItems.size != items.size) return
        newItems.forEachIndexed { targetIndex, desiredItem ->
            var currentIndex = items.indexOfFirst { it === desiredItem }
            if (currentIndex < 0) return
            while (currentIndex > targetIndex) {
                moveItem(currentIndex, currentIndex - 1)
                currentIndex--
            }
            while (currentIndex < targetIndex) {
                moveItem(currentIndex, currentIndex + 1)
                currentIndex++
            }
        }
    }


    fun <T : ViewBinding> setHeader(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        this.header = Header(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
    }

    fun setOnLoadMoreListener(onLoadMoreListener: (() -> Unit)?) {
        if (this.onLoadMoreListener != null && onLoadMoreListener == null) {
            this.onLoadMoreListener = null
            notifyItemRemoved(items.size)
        } else {
            this.onLoadMoreListener = onLoadMoreListener
        }
    }

    fun <T : ViewBinding> setFooter(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        this.footer = Footer(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
    }


    private class HeaderViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private class FavoriteSectionHeaderViewHolder(
        private val binding: ItemFavoriteSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: FavoriteSectionHeader) {
            binding.tvFavoriteSectionTitle.text = header.title
        }
    }

    private data class Header<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private class LoadingViewHolder(
        binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private class FooterViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private data class Footer<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private data class IdentityState(
        val identities: List<String>,
        val counts: MutableMap<String, Int>,
        val stableIds: LongArray,
    )

    private fun List<Item>.buildIdentityState(
        startingCounts: Map<String, Int> = emptyMap()
    ): IdentityState {
        val occurrenceCounts = startingCounts.toMutableMap()
        val identities = ArrayList<String>(size)
        val stableIds = LongArray(size)

        forEachIndexed { index, item ->
            val baseKey = item.baseIdentityKey()
            val key = "${item.itemType.ordinal}:$baseKey"
            val occurrenceIndex = occurrenceCounts.getOrDefault(key, 0)
            occurrenceCounts[key] = occurrenceIndex + 1

            val identity = "$key:$occurrenceIndex"
            identities.add(identity)
            stableIds[index] = identity.fold(1125899906842597L) { acc, char ->
                31L * acc + char.code
            }
        }

        return IdentityState(
            identities = identities,
            counts = occurrenceCounts,
            stableIds = stableIds,
        )
    }

    private fun Item.baseIdentityKey(): String = when (this) {
        is Category -> "category:$stableKey"
        is Episode -> "episode:${id}"
        is FavoriteSectionHeader -> "favorite-header:${section.key}"
        is Genre -> "genre:${id}"
        is LiveChannel -> "live-channel:${id}"
        is Movie -> "movie:${id}"
        is People -> "people:${id}"
        is Provider -> "provider:${name}"
        is Season -> "season:${id}"
        is TvShow -> "tvshow:${id}"
        else -> "item:${itemType.name}"
    }

    fun logicalIdentityAt(position: Int): String? =
        items.getOrNull(position)?.baseIdentityKey()

    fun positionOfLogicalIdentity(identity: String): Int =
        items.indexOfFirst { item -> item.baseIdentityKey() == identity }
}
