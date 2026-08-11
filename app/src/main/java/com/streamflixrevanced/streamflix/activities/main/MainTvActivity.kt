package com.streamflixrevanced.streamflix.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixrevanced.streamflix.BuildConfig
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.databinding.ActivityMainTvBinding
import com.streamflixrevanced.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixrevanced.streamflix.fragments.player.PlayerTvFragment
import com.streamflixrevanced.streamflix.ui.UpdateAppTvDialog
import com.streamflixrevanced.streamflix.providers.IptvProvider
import com.streamflixrevanced.streamflix.providers.Provider
import com.streamflixrevanced.streamflix.providers.Cine24hProvider
import com.streamflixrevanced.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixrevanced.streamflix.providers.ZaluknijProvider
import com.streamflixrevanced.streamflix.utils.AppLanguageManager
import com.streamflixrevanced.streamflix.utils.ProfileManager
import com.streamflixrevanced.streamflix.utils.loadProfileAvatar
import com.streamflixrevanced.streamflix.utils.ThemeManager
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.getCurrentFragment
import com.streamflixrevanced.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixrevanced.streamflix.providers.HdFullProvider
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog
    private lateinit var navController: androidx.navigation.NavController

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Il setup delle preferenze è già avvenuto in StreamFlixApp
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))
        
        super.onCreate(savedInstanceState)
        
        // Inizializza il provider con il context dell'attività per gestire eventuali bypass visibili
        AnimeOnlineNinjaProvider.init(this)
        Cine24hProvider.init(this)
        FilmyOnlineCcProvider.init(this)
        ZaluknijProvider.init(this)

        HdFullProvider.init(this)

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                binding.ivSplashOverlay.visibility = View.GONE
            }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            val activeProfile = ProfileManager.activeProfile
            if (activeProfile != null) {
                val navToProviders = intent.getBooleanExtra("NAV_TO_PROVIDERS", false)
                when {
                    navToProviders -> navController.navigate(R.id.providers)
                    UserPreferences.currentProvider != null -> navController.navigate(R.id.home)
                    else -> navController.navigate(R.id.providers)
                }
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavigationHeader()

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainTvActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.profiles -> {
                        if (UserPreferences.currentProvider != null) {
                            navigateToProviderHome(navController)
                        } else if (ProfileManager.activeProfile != null) {
                            navController.navigate(R.id.providers)
                        } else {
                            finish()
                        }
                    }
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.profiles -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateNavigationHeader()
        viewModel.checkUpdate()
    }

    private fun updateNavigationHeader() {
        binding.navMain.headerView?.apply {
            val header = ContentHeaderMenuMainTvBinding.bind(this)

            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() } ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .into(header.ivNavigationHeaderIcon)
            header.tvNavigationHeaderTitle.text = UserPreferences.currentProvider?.name
            header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_change_provider)

            val profile = ProfileManager.activeProfile
            if (profile != null) {
                header.ivHeaderProfileAvatar.loadProfileAvatar(profile.avatarPath)
                header.tvHeaderProfileInitial.visibility = View.GONE
                header.tvHeaderProfileName.text = profile.name
            }

            header.flHeaderProfileAvatar.setOnClickListener {
                if (navController.currentDestination?.id != R.id.profiles) {
                    navController.navigate(R.id.profiles)
                }
            }
            header.flHeaderProfileAvatar.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) binding.navMain.open()
            }

            header.ivNavigationHeaderIcon.setOnClickListener {
                navController.navigate(R.id.providers)
            }

            binding.navMain.menuView.getChildAt(0)?.nextFocusUpId = R.id.fl_header_profile_avatar

            setOnOpenListener {
                header.viewProviderProfileDivider.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    topMargin = (12 * resources.displayMetrics.density).toInt()
                }
                header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                header.tvHeaderProfileName.visibility = View.VISIBLE
                header.tvHeaderProfileSubtitle.visibility = View.VISIBLE
            }
            setOnCloseListener {
                header.viewProviderProfileDivider.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    topMargin = (24 * resources.displayMetrics.density).toInt()
                }
                header.tvNavigationHeaderTitle.visibility = View.GONE
                header.tvNavigationHeaderSubtitle.visibility = View.GONE
                header.tvHeaderProfileName.visibility = View.GONE
                header.tvHeaderProfileSubtitle.visibility = View.GONE
            }
        }
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        binding.navMain.setBackgroundColor(palette.tvNavBackground)
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(palette.tvNavBackground)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }
    
    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider || Provider.supportsLiveTv(provider))
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.profiles) {
                        inclusive = true
                    }
                }
            )
        }
    }

    /**
     * Recreate Home after a profile switch. Profile switching replaces the
     * Room database, so an existing HomeViewModel may still be collecting
     * flows from the closed database instance.
     */
    fun recreateProviderHome() {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return

        navController.navigate(
            R.id.home,
            null,
            navOptions {
                launchSingleTop = true
                popUpTo(R.id.home) {
                    inclusive = true
                }
            },
        )
    }
}
