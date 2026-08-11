package com.streamflixrevanced.streamflix.adapters.viewholders

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.databinding.ItemProviderMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemProviderTvBinding
import com.streamflixrevanced.streamflix.models.Provider
import com.streamflixrevanced.streamflix.sync.CloudSyncHooks
import com.streamflixrevanced.streamflix.utils.ProviderChangeNotifier
import com.streamflixrevanced.streamflix.utils.UserPreferences
import com.streamflixrevanced.streamflix.utils.toActivity
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var provider: Provider

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        val logo = provider.logo.takeIf { it.isNotEmpty() }
        binding.ivProviderLogo.setLayerType(
            if (logo?.substringBefore('?')?.endsWith(".svg", ignoreCase = true) == true) {
                View.LAYER_TYPE_SOFTWARE
            } else {
                View.LAYER_TYPE_NONE
            },
            null,
        )

        Glide.with(context)
            .load(logo ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .fitCenter()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            setOnClickListener {
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        val logo = provider.logo.takeIf { it.isNotEmpty() }
        binding.ivProviderLogo.setLayerType(
            if (logo?.substringBefore('?')?.endsWith(".svg", ignoreCase = true) == true) {
                View.LAYER_TYPE_SOFTWARE
            } else {
                View.LAYER_TYPE_NONE
            },
            null,
        )

        Glide.with(context)
            .load(logo ?: R.drawable.ic_provider_default_logo)
            .error(R.drawable.ic_provider_default_logo)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
    }
    
    private fun toggleFavorite(provider: Provider) {
        provider.isFavorite = !provider.isFavorite
        val favorites = UserPreferences.favoriteProviders.toMutableSet()
        if (provider.isFavorite) {
            favorites.add(provider.name)
        } else {
            favorites.remove(provider.name)
        }
        UserPreferences.favoriteProviders = favorites
        CloudSyncHooks.providerFavorite(context, provider.name, provider.isFavorite)
        ProviderChangeNotifier.notifyProviderChanged()
    }
}
