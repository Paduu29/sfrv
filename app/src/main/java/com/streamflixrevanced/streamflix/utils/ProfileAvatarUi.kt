package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.models.Profile
import com.streamflixrevanced.streamflix.sync.SupabaseSettings
import kotlinx.coroutines.launch

fun ImageView.loadProfileAvatar(avatarPath: String?) {
    val fallback = Glide.with(this)
        .load(R.drawable.blank_picture)
        .centerCrop()
        .transform(CircleCrop())

    val avatarUrl = avatarPath
        ?.takeIf(ProfileAvatarRepository::isAllowedAvatarPath)
        ?.toAvatarPublicUrl()
    Glide.with(this)
        .load(avatarUrl ?: R.drawable.blank_picture)
        .centerCrop()
        .transform(CircleCrop())
        .error(fallback)
        .into(this)
}

private fun String.toAvatarPublicUrl(): String? {
    val config = SupabaseSettings.config ?: return null
    val baseUrl = config.url.trimEnd('/')
    val bucket = config.avatarBucket.orEmpty()
    if (baseUrl.isEmpty() || bucket.isEmpty() || !ProfileAvatarRepository.isAllowedAvatarPath(this)) {
        return null
    }
    val encodedPath = split('/').joinToString("/") { Uri.encode(it) }
    return "$baseUrl/storage/v1/object/public/${Uri.encode(bucket)}/$encodedPath"
}

object ProfileAvatarPicker {
    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        selectedPath: String,
        onAvatarSelected: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val isTelevision = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val content = FrameLayout(context).apply {
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), 0)
            minimumHeight = (280 * density).toInt()
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
        }
        content.addView(
            progress,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_avatar_picker_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        if (isTelevision) {
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.96f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        lifecycleOwner.lifecycleScope.launch {
            val result = runCatching { ProfileAvatarRepository.getAvailableAvatars() }
            if (!dialog.isShowing) return@launch
            content.removeAllViews()

            val avatars = result.getOrNull().orEmpty()
            if (avatars.size <= 1 && result.isFailure) {
                content.addView(errorView(context, result.exceptionOrNull()?.message))
                return@launch
            }

            val columns = if (isTelevision) {
                ((context.resources.configuration.screenWidthDp * 0.85f) / 100f)
                    .toInt()
                    .coerceIn(5, 8)
            } else {
                3
            }
            val avatarSizeDp = if (isTelevision) 84 else 96
            val initialPosition = avatars.indexOf(selectedPath).coerceAtLeast(0)
            val gridLayoutManager = GridLayoutManager(context, columns).apply {
                scrollToPositionWithOffset(initialPosition, 0)
            }
            val recyclerView = RecyclerView(context).apply {
                layoutManager = gridLayoutManager
                adapter = AvatarAdapter(avatars, selectedPath, avatarSizeDp) { path ->
                    onAvatarSelected(path)
                    dialog.dismiss()
                }
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                if (isTelevision) {
                    setPadding(0, 0, (16 * density).toInt(), 0)
                    addItemDecoration(TvScrollBarDecoration(context))
                }
            }
            content.addView(
                recyclerView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private class TvScrollBarDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val density = context.resources.displayMetrics.density
        private val barWidth = 4f * density
        private val edgeMargin = 4f * density
        private val minimumThumbHeight = 32f * density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
        }

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            super.onDrawOver(canvas, parent, state)

            val scrollRange = parent.computeVerticalScrollRange()
            val scrollExtent = parent.computeVerticalScrollExtent()
            if (scrollRange <= scrollExtent || scrollExtent <= 0) return

            val trackTop = parent.paddingTop.toFloat() + edgeMargin
            val trackBottom = parent.height.toFloat() - parent.paddingBottom - edgeMargin
            val trackHeight = trackBottom - trackTop
            if (trackHeight <= 0f) return

            val left = parent.width.toFloat() - edgeMargin - barWidth
            val right = left + barWidth
            val radius = barWidth / 2f
            canvas.drawRoundRect(RectF(left, trackTop, right, trackBottom), radius, radius, trackPaint)

            val thumbHeight = (trackHeight * scrollExtent / scrollRange)
                .coerceAtLeast(minimumThumbHeight)
                .coerceAtMost(trackHeight)
            val scrollableRange = (scrollRange - scrollExtent).toFloat()
            val scrollOffset = parent.computeVerticalScrollOffset()
                .coerceIn(0, scrollRange - scrollExtent)
                .toFloat()
            val thumbTop = trackTop + (trackHeight - thumbHeight) * (scrollOffset / scrollableRange)
            canvas.drawRoundRect(
                RectF(left, thumbTop, right, thumbTop + thumbHeight),
                radius,
                radius,
                thumbPaint,
            )
        }
    }

    private fun errorView(context: Context, detail: String?): TextView = TextView(context).apply {
        text = context.getString(R.string.profile_avatar_load_error) +
            detail?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(24, 24, 24, 24)
    }

    private class AvatarAdapter(
        private val avatars: List<String>,
        private val selectedPath: String,
        private val avatarSizeDp: Int,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<AvatarAdapter.AvatarViewHolder>() {

        private val initialFocusPosition = avatars.indexOf(selectedPath).coerceAtLeast(0)
        private var initialFocusRequested = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
            val density = parent.resources.displayMetrics.density
            val root = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ((avatarSizeDp + 12) * density).toInt(),
                ).apply { setMargins(4, 4, 4, 4) }
                isClickable = true
                isFocusable = true
            }
            val image = ImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (avatarSizeDp * density).toInt(),
                    (avatarSizeDp * density).toInt(),
                    Gravity.CENTER,
                )
                isDuplicateParentStateEnabled = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                val padding = (6 * density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            root.addView(image)
            return AvatarViewHolder(root, image)
        }

        override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
            holder.bind(avatars[position])
            if (!initialFocusRequested && position == initialFocusPosition) {
                holder.itemView.post {
                    if (holder.bindingAdapterPosition == initialFocusPosition &&
                        holder.itemView.requestFocus()
                    ) {
                        initialFocusRequested = true
                    }
                }
            }
        }

        override fun getItemCount(): Int = avatars.size

        inner class AvatarViewHolder(
            itemView: View,
            private val image: ImageView,
        ) : RecyclerView.ViewHolder(itemView) {
            fun bind(path: String) {
                val selected = path == selectedPath
                image.background = avatarBackground(image, selected)
                image.contentDescription = path.substringAfterLast('/')
                image.loadProfileAvatar(path)
                itemView.contentDescription = image.contentDescription
                itemView.scaleX = 1f
                itemView.scaleY = 1f
                itemView.setOnClickListener { onClick(path) }
                itemView.setOnFocusChangeListener { view, hasFocus ->
                    val scale = if (hasFocus) 1.08f else 1f
                    view.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
                }
            }
        }

        private fun avatarBackground(view: View, selected: Boolean): StateListDrawable {
            val density = view.resources.displayMetrics.density
            fun ring(widthDp: Int, color: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((widthDp * density).toInt(), color)
            }

            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ring(5, Color.WHITE))
                addState(intArrayOf(android.R.attr.state_pressed), ring(5, Color.WHITE))
                addState(
                    intArrayOf(),
                    ring(if (selected) 3 else 1, if (selected) 0xFF7DB3E8.toInt() else 0x55FFFFFF),
                )
            }
        }
    }
}
