package com.android.settings.password.generate

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

private const val TAG = "BaseLockPasswordGenerationFragment"

abstract class BaseLockPasswordGenerationFragment(
    @LayoutRes private val resId: Int,
    val shouldGcOnDestroy: Boolean = true
) : Fragment() {
    protected val viewModel: GenerateLockPasswordViewModel by activityViewModels()

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (activity !is GenerateLockPasswordActivity) {
            throw SecurityException("Fragment contained in wrong activity")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(resId, container, false)
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
