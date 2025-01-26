package com.android.settings.password.generate

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import com.google.android.setupcompat.template.FooterBarMixin
import com.google.android.setupcompat.template.FooterButton
import com.google.android.setupdesign.GlifLayout
import kotlinx.coroutines.flow.filterNotNull

private const val TAG = "ShowGeneratedLockPassOptionsFragment"

class ShowGeneratedLockPassOptionsFragment : BaseLockPasswordGenerationFragment(
    R.layout.generate_lock_password_show_generated
) {

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val layout = view as GlifLayout

        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val errorMessage = view.findViewById<TextView>(R.id.error_message)
        val recyclerView = view.findViewById<RecyclerView>(R.id.pass_list)
        recyclerView.layoutManager = LinearLayoutManager(view.context)
        recyclerView.adapter = PassOptionsRecyclerViewAdapter(viewModel)
        // not a lot of items shown anyway
        recyclerView.isNestedScrollingEnabled = false
        recyclerView.setHasFixedSize(false)

        val footerBarMixin = layout.getMixin(FooterBarMixin::class.java)
        footerBarMixin.primaryButton =
            FooterButton.Builder(requireContext())
                .setText(R.string.lock_screen_generate_options_next_button)
                .setListener { viewModel.primaryButtonClicked() }
                .setButtonType(FooterButton.ButtonType.NEXT)
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Primary)
                .build()
        footerBarMixin.secondaryButton =
            FooterButton.Builder(requireContext())
                .setText(R.string.lock_screen_generate_options_regenerate_button)
                .setListener { viewModel.generateNewPasswords() }
                .setButtonType(FooterButton.ButtonType.CLEAR)
                .setTheme(com.google.android.setupdesign.R.style.SudGlifButton_Secondary)
                .build()

        viewLifecycleOwner.repeatCollectOnLifecycle(viewModel.isGenerating) { isGenerating ->
            footerBarMixin.secondaryButton?.isEnabled = !isGenerating
        }

        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.isPrimaryButtonEnabled
        ) { isEnabled ->
            footerBarMixin.primaryButton?.isEnabled = isEnabled
        }

        viewLifecycleOwner.repeatCollectOnLifecycle(viewModel.selectedPassword) { _ ->
            // notifying individual items causes a fade effect, and there are not that
            // many items anyway
            recyclerView.adapter?.notifyDataSetChanged()
        }

        viewLifecycleOwner.repeatCollectOnLifecycle(viewModel.generatedPasswords) { passes ->
            when (passes) {
                is GenerateLockPasswordViewModel.GenerateState.Error -> {
                    progressBar.visibility = View.GONE
                    errorMessage.visibility = View.VISIBLE
                    errorMessage.text = getString(
                        R.string.lock_screen_generate_show_generated_error_message_s,
                        passes.errorMessage
                    )
                }
                is GenerateLockPasswordViewModel.GenerateState.Loaded -> {
                    progressBar.visibility = View.GONE
                    errorMessage.visibility = View.GONE
                    errorMessage.text = ""
                }
                GenerateLockPasswordViewModel.GenerateState.NotLoaded -> {
                    progressBar.visibility = View.VISIBLE
                    errorMessage.visibility = View.GONE
                    errorMessage.text = ""
                }
            }

            recyclerView.adapter?.notifyDataSetChanged()
        }

        viewLifecycleOwner.repeatCollectOnLifecycle(
            viewModel.genParams.filterNotNull()
        ) { genOptions ->
            layout.apply {
                when (genOptions) {
                    is PinGenParams -> {
                        icon = requireActivity().getDrawable(R.drawable.ic_lock_pin)
                        setHeaderText(R.string.lock_screen_generate_show_generated_title_pin)
                        setDescriptionText(
                            getString(R.string.lock_screen_generate_show_generated_desc_pin_d_pins_been_gen, genOptions.numberToGenerate)
                        )
                    }
                    is DicewarePassphraseGenParams -> {
                        icon = requireActivity().getDrawable(R.drawable.ic_password)
                        setHeaderText(R.string.lock_screen_generate_show_generated_title_passphrase)
                        setDescriptionText(
                            getString(R.string.lock_screen_generate_show_generated_desc_passphrase_d_passphrases_been_gen, genOptions.numberToGenerate)
                        )
                    }
                }
            }
        }
    }
}

class PassOptionsRecyclerViewAdapter(
    val viewModel: GenerateLockPasswordViewModel
) : RecyclerView.Adapter<PassOptionsRecyclerViewAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        viewModel.getGeneratedPasswordIdForRecyclerView(position)

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val radioButton: RadioButton = itemView.findViewById<RadioButton>(R.id.list_radio_button)
        val text: TextView = itemView.findViewById<TextView>(R.id.list_text)

        fun cleanUp() {
            text.text = ""
            text.setOnClickListener(null)
            radioButton.setOnCheckedChangeListener(null)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanUp()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view: View = inflater.inflate(
            R.layout.generate_lock_password_show_generated_list_item,
            parent,
            false
        )
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = viewModel.generatedPasswords.value.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val generatedPassword = viewModel.generatedPasswords.value.listOrNull()?.getOrNull(position)
        val isSelected = viewModel.selectedPassword.value?.index == position
        holder.apply {
            radioButton.setOnCheckedChangeListener(null)
            radioButton.setChecked(isSelected)
            val listener = View.OnClickListener { radioButton.toggle() }
            text.setOnClickListener(listener)
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    viewModel.setSelectedPassword(position)
                }
            }
            text.text = when (generatedPassword) {
                is GeneratedPassphrase -> generatedPassword.passphrase
                is GeneratedPin -> generatedPassword.pin
                null -> ""
            }
        }
    }
}
