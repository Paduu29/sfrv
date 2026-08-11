package com.streamflixrevanced.streamflix.fragments.tv_shows

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.adapters.AppAdapter
import com.streamflixrevanced.streamflix.database.AppDatabase
import com.streamflixrevanced.streamflix.databinding.FragmentTvShowsMobileBinding
import com.streamflixrevanced.streamflix.models.TvShow
import com.streamflixrevanced.streamflix.models.LiveChannel
import com.streamflixrevanced.streamflix.models.Genre
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.ui.SpacingItemDecoration
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.dp
import com.streamflixrevanced.streamflix.utils.viewModelsFactory
import com.streamflixrevanced.streamflix.utils.CacheUtils
import com.streamflixrevanced.streamflix.utils.LiveChannelGrouping
import kotlinx.coroutines.launch

class TvShowsMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { TvShowsViewModel(database) }

    private val appAdapter = AppAdapter()
    private var liveChannelGroups: List<LiveChannelGrouping.Group> = emptyList()
    private var selectedLiveChannelGroupId: String? = null
    private val liveChannelScrollStates = mutableMapOf<String, Parcelable?>()
    private var liveGroupScrollState: Parcelable? = null
    private lateinit var liveGroupBackCallback: OnBackPressedCallback

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeTvShows()
        liveGroupBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                rememberLiveChannelPosition()
                selectedLiveChannelGroupId = null
                displayLiveChannelGroups()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, liveGroupBackCallback)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    TvShowsViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayTvShows(state.tvShows, state.liveChannels, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixrevanced.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getTvShows()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getTvShows() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixrevanced.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    doRetry()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        rememberLiveChannelPosition()
        super.onDestroyView()
        _binding = null
    }

    override fun onStop() {
        rememberLiveChannelPosition()
        super.onStop()
    }


    private fun initializeTvShows() {
        binding.rvTvShows.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                onGenreClickListener = { genre -> openLiveChannelGroup(genre.id) }
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun displayTvShows(tvShows: List<TvShow>, liveChannels: List<LiveChannel>, hasMore: Boolean) {
        if (liveChannels.isNotEmpty()) {
            liveChannelGroups = LiveChannelGrouping.group(
                liveChannels,
                UserPreferences.currentProvider?.language.orEmpty(),
            )
            val selectedGroup = liveChannelGroups.firstOrNull { it.id == selectedLiveChannelGroupId }
            if (selectedGroup != null) {
                rememberLiveChannelPosition()
                displayLiveChannelGroup(selectedGroup.id)
            } else if (liveChannelGroups.isNotEmpty()) {
                selectedLiveChannelGroupId = null
                displayLiveChannelGroups()
            } else {
                liveGroupBackCallback.isEnabled = false
                submitLiveChannels(liveChannels)
            }
        } else {
            liveChannelGroups = emptyList()
            selectedLiveChannelGroupId = null
            liveGroupBackCallback.isEnabled = false
            appAdapter.submitList(
                tvShows.onEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM }
            )
        }

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayLiveChannelGroups() {
        liveGroupBackCallback.isEnabled = false
        val items = liveChannelGroups.map { group ->
            Genre(group.id, group.name).apply {
                itemType = AppAdapter.Type.GENRE_GRID_MOBILE_ITEM
            }
        }
        appAdapter.submitList(items)
        binding.rvTvShows.post {
            val savedState = liveGroupScrollState
            if (savedState != null) {
                binding.rvTvShows.layoutManager?.onRestoreInstanceState(savedState)
            } else {
                binding.rvTvShows.scrollToPosition(0)
            }
        }
    }

    private fun openLiveChannelGroup(groupId: String) {
        liveGroupScrollState = binding.rvTvShows.layoutManager?.onSaveInstanceState()
        displayLiveChannelGroup(groupId)
    }

    private fun displayLiveChannelGroup(groupId: String) {
        val group = liveChannelGroups.firstOrNull { it.id == groupId } ?: return
        selectedLiveChannelGroupId = group.id
        liveGroupBackCallback.isEnabled = true
        submitLiveChannels(
            channels = group.channels,
            savedState = liveChannelScrollStates[group.id],
        )
    }

    private fun submitLiveChannels(
        channels: List<LiveChannel>,
        savedState: Parcelable? = null,
    ) {
        appAdapter.submitList(
            channels.onEach { it.itemType = AppAdapter.Type.LIVE_CHANNEL_GRID_MOBILE_ITEM }
        )
        binding.rvTvShows.post {
            if (savedState != null) {
                binding.rvTvShows.layoutManager?.onRestoreInstanceState(savedState)
            } else {
                binding.rvTvShows.scrollToPosition(0)
            }
        }
    }

    private fun rememberLiveChannelPosition() {
        val groupId = selectedLiveChannelGroupId ?: return
        val currentBinding = _binding ?: return
        liveChannelScrollStates[groupId] =
            currentBinding.rvTvShows.layoutManager?.onSaveInstanceState()
    }
}
