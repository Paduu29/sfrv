package com.streamflixrevanced.streamflix.fragments.settings

import android.graphics.Color
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.R as AppCompatR
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.streamflixrevanced.streamflix.R
import com.streamflixrevanced.streamflix.sync.CloudAccountAlreadyLinkedException
import com.streamflixrevanced.streamflix.sync.CloudSyncManager
import com.streamflixrevanced.streamflix.sync.CloudSyncProgress
import com.streamflixrevanced.streamflix.sync.SupabaseProvider
import com.streamflixrevanced.streamflix.sync.SupabaseConfigError
import com.streamflixrevanced.streamflix.sync.SupabaseConfigValidation
import com.streamflixrevanced.streamflix.sync.SupabaseSettings
import com.streamflixrevanced.streamflix.utils.ProfileManager
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object CloudAccountSettingsController {
    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val status = findPreference("cloud_account_status") ?: return
        val signIn = findPreference("cloud_sign_in")
        val signUp = findPreference("cloud_sign_up")
        val signOut = findPreference("cloud_sign_out")
        val syncNow = findPreference("cloud_sync_now")
        val configuration = findPreference("supabase_configuration")

        fun refresh() {
            val configured = SupabaseProvider.isConfigured
            val email = CloudSyncManager.currentUserEmail()
            status.summary = when {
                !configured -> fragment.getString(R.string.supabase_config_setup_required)
                email != null -> fragment.getString(R.string.cloud_sync_signed_in_as, email)
                else -> fragment.getString(R.string.cloud_sync_signed_out)
            }
            signIn?.isVisible = email == null
            signUp?.isVisible = email == null
            signOut?.isVisible = email != null
            syncNow?.isVisible = email != null
            signIn?.isEnabled = configured
            signUp?.isEnabled = configured
            status.isEnabled = configured
            configuration?.summary = SupabaseSettings.config?.let { config ->
                fragment.getString(R.string.supabase_config_summary_configured, config.url)
            } ?: fragment.getString(R.string.supabase_config_summary_required)
        }

        configuration?.setOnPreferenceClickListener {
            showSupabaseConfigurationDialog(fragment, scope, ::refresh)
            true
        }

        signIn?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_in) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    CloudSyncManager.signIn(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    R.string.cloud_sync_sign_in_success
                }
            }
            true
        }

        signUp?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_up) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    val signedIn = CloudSyncManager.signUp(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    if (signedIn) R.string.cloud_sync_sign_up_success else R.string.cloud_sync_confirm_email
                }
            }
            true
        }

        signOut?.setOnPreferenceClickListener {
            runAction(fragment, scope, ::refresh) {
                CloudSyncManager.signOut(fragment.requireContext())
                R.string.cloud_sync_sign_out_success
            }
            true
        }

        syncNow?.setOnPreferenceClickListener {
            runProgressAction(fragment, scope, ::refresh) { onProgress ->
                CloudSyncManager.syncNow(fragment.requireContext(), onProgress)
                R.string.cloud_sync_success
            }
            true
        }

        refresh()
        if (SupabaseProvider.isConfigured) {
            ProfileManager.activeProfileId?.let { profileId ->
                scope.launch {
                    runCatching {
                        SupabaseProvider.clientFor(profileId).auth.awaitInitialization()
                    }
                    if (fragment.isAdded && ProfileManager.activeProfileId == profileId) {
                        refresh()
                    }
                }
            }
        }
    }

    private fun showSupabaseConfigurationDialog(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
    ) {
        val context = fragment.requireContext()
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val primaryTextColor = Color.WHITE
        val secondaryTextColor = Color.rgb(189, 189, 189)
        val actionTextColor = resolveThemeColor(context, AppCompatR.attr.colorAccent, primaryTextColor)
        val current = SupabaseSettings.config
        val url = EditText(context).apply {
            id = android.view.View.generateViewId()
            hint = context.getString(R.string.supabase_config_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(current?.url.orEmpty())
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        val key = EditText(context).apply {
            id = android.view.View.generateViewId()
            hint = context.getString(R.string.supabase_config_publishable_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(current?.publishableKey.orEmpty())
            transformationMethod = PasswordTransformationMethod.getInstance()
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        val avatarBucket = EditText(context).apply {
            id = android.view.View.generateViewId()
            hint = context.getString(R.string.supabase_config_avatar_bucket_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setText(current?.avatarBucket.orEmpty())
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        val showKey = CheckBox(context).apply {
            id = android.view.View.generateViewId()
            setText(R.string.supabase_config_show_key)
            setTextColor(primaryTextColor)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                key.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
                key.setSelection(key.text.length)
            }
        }
        url.nextFocusDownId = key.id
        key.nextFocusUpId = url.id
        key.nextFocusDownId = avatarBucket.id
        avatarBucket.nextFocusUpId = key.id
        avatarBucket.nextFocusDownId = showKey.id
        showKey.nextFocusUpId = avatarBucket.id

        val warning = TextView(context).apply {
            setText(R.string.supabase_config_change_warning)
            setTextColor(secondaryTextColor)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(url, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(key, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(avatarBucket, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(showKey, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(warning, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.supabase_config_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.supabase_config_save, null)
            .create()
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(actionTextColor)
            positiveButton.setTextColor(actionTextColor)
            showKey.nextFocusDownId = positiveButton.id
            positiveButton.nextFocusUpId = showKey.id
            positiveButton.setOnClickListener {
                val validation = SupabaseSettings.validate(
                    url = url.text.toString(),
                    publishableKey = key.text.toString(),
                    avatarBucket = avatarBucket.text.toString(),
                )
                if (validation is SupabaseConfigValidation.Invalid) {
                    Toast.makeText(
                        context,
                        validation.error.messageResource(),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setOnClickListener
                }

                positiveButton.isEnabled = false
                scope.launch {
                    try {
                        CloudSyncManager.updateSupabaseConfiguration(
                            context = context,
                            url = url.text.toString(),
                            publishableKey = key.text.toString(),
                            avatarBucket = avatarBucket.text.toString(),
                        )
                        dialog.dismiss()
                        refresh()
                        Toast.makeText(context, R.string.supabase_config_saved, Toast.LENGTH_LONG).show()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        positiveButton.isEnabled = true
                        showError(fragment, error)
                    }
                }
            }
            url.requestFocus()
        }
        dialog.show()
    }

    private fun resolveThemeColor(context: android.content.Context, attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!context.theme.resolveAttribute(attr, typedValue, true)) return fallback
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun SupabaseConfigError.messageResource(): Int = when (this) {
        SupabaseConfigError.INVALID_URL -> R.string.supabase_config_invalid_url
        SupabaseConfigError.MISSING_PUBLISHABLE_KEY -> R.string.supabase_config_missing_key
        SupabaseConfigError.INVALID_PUBLISHABLE_KEY -> R.string.supabase_config_invalid_key
        SupabaseConfigError.SERVICE_ROLE_KEY -> R.string.supabase_config_service_role_rejected
    }

    private fun showCredentialsDialog(
        fragment: Fragment,
        titleRes: Int,
        onSubmit: (String, String) -> Unit,
    ) {
        val context = fragment.requireContext()
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val email = EditText(context).apply {
            id = android.view.View.generateViewId()
            hint = context.getString(R.string.cloud_sync_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val password = EditText(context).apply {
            id = android.view.View.generateViewId()
            hint = context.getString(R.string.cloud_sync_password_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        // Android TV's D-pad focus search is not reliable for views created inside
        // an AlertDialog. Keep the two fields in an explicit vertical focus chain.
        email.nextFocusDownId = password.id
        password.nextFocusUpId = email.id
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(email, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(titleRes, null)
            .create()
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            password.nextFocusDownId = positiveButton.id
            positiveButton.nextFocusUpId = password.id
            positiveButton.setOnClickListener {
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                if (!emailValue.contains('@') || passwordValue.length < 6) {
                    Toast.makeText(context, R.string.cloud_sync_invalid_credentials, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSubmit(emailValue, passwordValue)
            }
            email.requestFocus()
        }
        dialog.show()
    }

    private fun runProgressAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend ((CloudSyncProgress) -> Unit) -> Int,
    ) {
        val context = fragment.requireContext()
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        )
        val message = TextView(context).apply {
            setText(R.string.cloud_sync_progress_connecting)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(
                progressBar,
                LinearLayout.LayoutParams(
                    (72 * context.resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = padding
                },
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.cloud_sync_progress_title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        scope.launch {
            try {
                val resultMessage = action { progress ->
                    updateProgress(fragment, progressBar, message, progress)
                }
                dialog.dismiss()
                refresh()
                Toast.makeText(
                    fragment.requireContext(),
                    resultMessage,
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (fragment.isAdded) showError(fragment, error)
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun updateProgress(
        fragment: Fragment,
        progressBar: ProgressBar,
        message: TextView,
        progress: CloudSyncProgress,
    ) {
        when (progress.stage) {
            CloudSyncProgress.Stage.AUTHENTICATING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_authenticating)
            }
            CloudSyncProgress.Stage.CHECKING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_checking_cloud)
            }
            CloudSyncProgress.Stage.PREPARING_LOCAL -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_preparing_local)
            }
            CloudSyncProgress.Stage.MERGING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_merging)
            }
            CloudSyncProgress.Stage.UPLOADING -> {
                progressBar.isIndeterminate = false
                progressBar.max = progress.total.coerceAtLeast(1)
                progressBar.progress = progress.current
                message.text = fragment.getString(
                    R.string.cloud_sync_progress_uploading,
                    progress.current,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.APPLYING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.text = fragment.resources.getQuantityString(
                    R.plurals.cloud_sync_progress_applying_cloud,
                    progress.total,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.FINALIZING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_finalizing)
            }
        }
    }

    private fun runAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend () -> Int,
    ) {
        scope.launch {
            runCatching { action() }
                .onSuccess { message ->
                    refresh()
                    Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
                }
                .onFailure { error ->
                    showError(fragment, error)
                }
        }
    }

    private fun showError(fragment: Fragment, error: Throwable) {
        if (error is CloudAccountAlreadyLinkedException) {
            AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.cloud_sync_title)
                .setMessage(
                    fragment.getString(
                        R.string.cloud_sync_account_used_by_profile,
                        error.linkedProfileName,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .setCancelable(true)
                .show()
            return
        }

        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(
                R.string.cloud_sync_error,
                error.message ?: error.javaClass.simpleName,
            ),
            Toast.LENGTH_LONG,
        ).show()
    }
}
