package com.android.settings.password.generate

import android.app.settings.SettingsEnums
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.annotation.XmlRes
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.SettingsPreferenceFragment
import com.google.android.setupdesign.GlifPreferenceLayout

private const val TAG = "BaseLockPasswordGenerationPreferenceFragment"

/**
 * Because of the SetupWizard theme set in GenerateLockPasswordActivity, every PreferenceFragment
 * will be inflated to be a [GlifPreferenceLayout]
 *
 * The documentation for tells us: Fragments using this layout _must_ delegate
 * [onCreateRecyclerView] to the implementation in this class:
 * {@link #onCreateRecyclerView(android.view.LayoutInflater, android.view.ViewGroup,
 * android.os.Bundle)}
 *
 * Don't do what I did and try to use a FragmentContainerView inside of a GlifLayout to hold a
 * PreferenceFragment.
 * - GlifLayouts have the two-pane view in landscape orientation, which effectively
 *   halves the horizontal screen space
 * - [GlifPreferenceLayout] is a subclass of GlifLayouts.
 * - Therefore, nesting a [GlifPreferenceLayout] inside of a GlifLayout will result in the
 *   PreferenceLayout being 1/2 * 1/2 = 1/4 of the width!
 */
abstract class BaseLockPasswordGenerationPreferenceFragment(
    @XmlRes private val prefResId: Int,
    val shouldGcOnDestroy: Boolean = false
) : SettingsPreferenceFragment() {
    protected val viewModel: GenerateLockPasswordViewModel by activityViewModels()

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (activity !is GenerateLockPasswordActivity) {
            throw SecurityException("Fragment contained in wrong activity")
        }

        addPreferencesFromResource(prefResId)
    }

    override fun getMetricsCategory(): Int = SettingsEnums.CHOOSE_LOCK_PASSWORD

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        // do this so the header can actually be setup in portrait
        val layout = parent as GlifPreferenceLayout
        return layout.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            // do this so that the preferences don't fade when you change them
            itemAnimator = null
        }
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()

        if (shouldGcOnDestroy) {
            Log.d(TAG, "onDestroy garbage collection")
            // Force a garbage collection immediately to remove remnant of user password shards
            // from memory.
            System.gc()
            System.runFinalization()
            System.gc()
        }
    }
}
