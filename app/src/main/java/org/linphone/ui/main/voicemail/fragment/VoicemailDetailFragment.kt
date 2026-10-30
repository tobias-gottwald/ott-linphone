/*
 * Copyright (c) 2026 OTT Hoeren
 *
 * This file is part of the OTT softphone app, based on linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.voicemail.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.VoicemailDetailFragmentBinding
import org.linphone.ui.main.fragment.SlidingPaneChildFragment
import org.linphone.ui.main.voicemail.viewmodel.VoicemailDetailViewModel
import org.linphone.utils.Event

/**
 * Detail of one store voicemail (oc-aa79): playback of the audio from the
 * storage URL the sidecar returned (seek supported while the audio hasn't
 * expired or been deleted — then playback is disabled) plus the transcript
 * with pending/failed/refresh states. A pending transcript can be refreshed
 * manually; the detail is re-fetched on retry after load failures.
 *
 * Playback pauses when the fragment is left and on any SIP call activity
 * (see [VoicemailDetailViewModel]).
 */
@UiThread
class VoicemailDetailFragment : SlidingPaneChildFragment() {
    companion object {
        private const val TAG = "[Voicemail Detail Fragment]"
    }

    private lateinit var binding: VoicemailDetailFragmentBinding

    private lateinit var viewModel: VoicemailDetailViewModel

    private val args: VoicemailDetailFragmentArgs by navArgs()

    private val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            // Pause while the user drags the handle, like the recording
            // player; onStopTrackingTouch resumes at the chosen position.
            viewModel.pausePlayback()
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            viewModel.seekTo(seekBar.progress)
        }
    }

    override fun goBack(): Boolean {
        sharedViewModel.closeSlidingPaneEvent.value = Event(true)

        if (findNavController().currentDestination?.id == R.id.voicemailDetailFragment) {
            // If not done this fragment won't be paused, which would cause us issues
            val action = VoicemailDetailFragmentDirections.actionVoicemailDetailFragmentToEmptyFragment()
            findNavController().navigate(action)
            return true
        }
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = VoicemailDetailFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[VoicemailDetailViewModel::class.java]
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            goBack()
        }

        binding.setRetryClickListener {
            Log.i("$TAG Retrying voicemail detail load")
            viewModel.refreshDetail()
        }

        binding.setSeekBarListener(seekBarListener)

        sharedViewModel.isSlidingPaneSlideable.observe(viewLifecycleOwner) { slideable ->
            binding.back.visibility = if (slideable) View.VISIBLE else View.GONE
        }

        viewModel.audioAvailable.observe(viewLifecycleOwner) { available ->
            // Dim the play button when the audio expired or was deleted.
            // Set programmatically: a float literal in a data-bound
            // android:alpha breaks aapt resource linking.
            binding.playPause.alpha = if (available) 1f else 0.3f
        }

        viewModel.voicemailLoadedEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG Voicemail [${args.voicemailId}] loaded, opening pane")
                sharedViewModel.openSlidingPaneEvent.value = Event(true)
            }
        }

        val voicemailId = args.voicemailId
        Log.i("$TAG Loading voicemail [$voicemailId]")
        viewModel.load(voicemailId)
    }

    override fun onPause() {
        // Leaving the detail (tab switch, back, app switch): the audio must
        // stop and the audio focus must be released.
        viewModel.pausePlayback()
        super.onPause()
    }
}
