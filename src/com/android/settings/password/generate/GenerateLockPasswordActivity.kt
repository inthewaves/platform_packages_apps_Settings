package com.android.settings.password.generate

import android.app.admin.DevicePolicyManager
import android.app.admin.PasswordMetrics
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.android.internal.widget.LockPatternUtils
import com.android.settings.R
import com.android.settings.SettingsActivity
import com.android.settings.SetupWizardUtils
import com.android.settings.password.ChooseLockPassword
import com.google.android.setupdesign.util.ThemeHelper
import kotlinx.coroutines.flow.filterNotNull

class GenerateLockPasswordActivity : SettingsActivity() {

    private val viewModel: GenerateLockPasswordViewModel by viewModels(
        factoryProducer = { GenerateLockPasswordViewModel.Factory }
    )

    override fun getIntent(): Intent {
        val intent = Intent(super.getIntent())
        intent.putExtra(EXTRA_SHOW_FRAGMENT, GenerateLockPasswordHostFragment::class.java.name)
        return intent
    }

    override fun isValidFragment(fragmentName: String?): Boolean {
        return GenerateLockPasswordHostFragment::class.java.name == fragmentName
    }

    override fun isToolbarEnabled(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(SetupWizardUtils.getTheme(this, intent))
        ThemeHelper.trySetDynamicColor(this)
        super.onCreate(savedInstanceState)
        findViewById<View>(R.id.content_parent).fitsSystemWindows = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val callback: OnBackPressedCallback = object : OnBackPressedCallback(true ) {
            override fun handleOnBackPressed() {
                viewModel.onBackPressed()
            }
        }
        onBackPressedDispatcher.addCallback(callback)

        val passwordType = intent.getIntExtra(
            LockPatternUtils.PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
        )
        val isAlphaMode = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC == passwordType ||
                DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC == passwordType ||
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX == passwordType

        // following how ChooseLockPassword obtains these parameters
        val complexity = intent.getIntExtra(
            ChooseLockPassword.EXTRA_KEY_MIN_COMPLEXITY,
            DevicePolicyManager.PASSWORD_COMPLEXITY_NONE
        )
        val minMetrics: PasswordMetrics = intent.getParcelableExtra(
            ChooseLockPassword.EXTRA_KEY_MIN_METRICS
        ) ?: PasswordMetrics(LockPatternUtils.CREDENTIAL_TYPE_NONE)

        viewModel.setup(isAlphaMode, minMetrics, complexity)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Force a garbage collection immediately to remove remnant of user password shards
        // from memory.
        System.gc()
        System.runFinalization()
        System.gc()
    }

    class GenerateLockPasswordHostFragment : Fragment() {
        private val viewModel: GenerateLockPasswordViewModel by activityViewModels()

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
            return inflater.inflate(R.layout.generate_lock_password_container, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            lifecycleScope.launchAndCollect(viewModel.stage.filterNotNull()) { newStage ->
                val existingFragment = childFragmentManager.findFragmentByTag(
                    newStage.fragmentTag
                )
                if (existingFragment != null) {
                    return@launchAndCollect
                }

                val newFragment: Fragment = when (newStage) {
                    is PassGenStage.ChooseGeneratedOrManual -> GeneratedOrManualLockPasswordFragment()
                    is PassGenStage.ChooseParams -> LockPasswordGenerationParamsFragment()
                    is PassGenStage.ShowMultiple -> ShowGeneratedLockPassOptionsFragment()
                    is PassGenStage.Confirmation -> ConfirmGeneratedLockPassFragment()
                    PassGenStage.Quit -> {
                        requireActivity().finish()
                        return@launchAndCollect
                    }
                }

                val enter = if (newStage.isBackwards) {
                    com.google.android.setupdesign.R.anim.sud_slide_back_in
                } else {
                    com.google.android.setupdesign.R.anim.sud_slide_next_in
                }
                val exit = if (newStage.isBackwards) {
                    com.google.android.setupdesign.R.anim.sud_slide_back_out
                } else {
                    com.google.android.setupdesign.R.anim.sud_slide_next_out
                }

                childFragmentManager
                    .beginTransaction()
                    .setCustomAnimations(enter, exit)
                    .replace(R.id.fragment_container_view, newFragment, newStage.fragmentTag)
                    .setReorderingAllowed(true)
                    .commit()
            }
        }
    }
}
