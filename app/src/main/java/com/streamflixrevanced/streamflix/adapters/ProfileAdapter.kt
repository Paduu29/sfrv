package com.streamflixrevanced.streamflix.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.models.Profile
import com.streamflixrevanced.streamflix.utils.ProfileManager
import com.streamflixrevanced.streamflix.utils.loadProfileAvatar

class ProfileAdapter(
    private val onProfileClick: (Profile) -> Unit,
    private val onProfileLongClick: (Profile) -> Unit,
    private val layoutResId: Int = R.layout.item_profile_mobile,
) : ListAdapter<Profile, ProfileAdapter.ProfileViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val root = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        return ProfileViewHolder(root)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(item: View) : RecyclerView.ViewHolder(item) {
        private val ivProfileAvatar: ImageView = itemView.findViewById(R.id.iv_profile_avatar)
        private val tvProfileInitial: TextView = itemView.findViewById(R.id.tv_profile_initial)
        private val tvProfileName: TextView = itemView.findViewById(R.id.tv_profile_name)
        private val tvProfileCurrent: TextView? = itemView.findViewById(R.id.tv_profile_current)

        fun bind(profile: Profile) {
            itemView.isSelected = profile.id == ProfileManager.activeProfileId
            ivProfileAvatar.loadProfileAvatar(profile.avatarPath)
            tvProfileInitial.visibility = View.GONE
            tvProfileName.text = profile.name
            tvProfileCurrent?.apply {
                visibility = View.VISIBLE
                alpha = if (profile.id == ProfileManager.activeProfileId) 1f else 0f
                contentDescription = text
            }
            itemView.contentDescription = itemView.context.getString(
                R.string.profile_card_content_description,
                profile.name,
            )

            itemView.setOnClickListener { onProfileClick(profile) }
            itemView.setOnLongClickListener {
                onProfileLongClick(profile)
                true
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean =
            oldItem == newItem
    }
}
