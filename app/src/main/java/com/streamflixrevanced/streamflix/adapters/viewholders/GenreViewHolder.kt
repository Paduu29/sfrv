package com.streamflixrevanced.streamflix.adapters.viewholders

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.databinding.ItemGenreGridMobileBinding
import com.streamflixrevanced.streamflix.databinding.ItemGenreGridTvBinding
import com.streamflixrevanced.streamflix.models.Genre


class GenreViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var genre: Genre

    fun bind(genre: Genre, onClick: ((Genre) -> Unit)? = null) {
        this.genre = genre

        when (_binding) {
            is ItemGenreGridMobileBinding -> displayGridMobileItem(_binding, onClick)
            is ItemGenreGridTvBinding -> displayGridTvItem(_binding, onClick)
        }
    }


    private fun displayGridMobileItem(
        binding: ItemGenreGridMobileBinding,
        onClick: ((Genre) -> Unit)?,
    ) {
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            (background as? GradientDrawable)?.setColor(colors[bindingAdapterPosition % colors.size])

            setOnClickListener {
                if (onClick != null) {
                    onClick(genre)
                    return@setOnClickListener
                }
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
        }

        binding.tvGenreName.text = genre.name
    }

    private fun displayGridTvItem(
        binding: ItemGenreGridTvBinding,
        onClick: ((Genre) -> Unit)?,
    ) {
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            (background as? GradientDrawable)?.setColor(colors[bindingAdapterPosition % colors.size])

            setOnClickListener {
                if (onClick != null) {
                    onClick(genre)
                    return@setOnClickListener
                }
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }

        binding.tvGenreName.text = genre.name
    }
}
