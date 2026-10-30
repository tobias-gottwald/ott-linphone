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
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.VoicemailListFragmentBinding
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.voicemail.adapter.VoicemailListAdapter
import org.linphone.ui.main.voicemail.viewmodel.VoicemailListViewModel

/**
 * Store voicemail list tab (oc-aa79), replacing the unused SIP chat
 * bottom-navigation slot. Master-detail like the other tabs: the list on the
 * left, the detail ([VoicemailDetailFragment]) in the sliding pane's child
 * navigation graph. The list refreshes on open and on explicit refresh, and
 * paginates while scrolling.
 */
@UiThread
class VoicemailListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Voicemail List Fragment]"
    }

    private lateinit var binding: VoicemailListFragmentBinding

    private lateinit var listViewModel: VoicemailListViewModel

    private lateinit var adapter: VoicemailListAdapter

    override fun onDefaultAccountChanged() {
        Log.i("$TAG Default account changed, dropping and reloading the voicemail list")
        listViewModel.onIdentityChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter = VoicemailListAdapter()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = VoicemailListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listViewModel = ViewModelProvider(this)[VoicemailListViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel
        observeToastEvents(listViewModel)

        binding.voicemailList.setHasFixedSize(true)
        binding.voicemailList.layoutManager = LinearLayoutManager(requireContext())
        binding.voicemailList.outlineProvider = outlineProvider
        binding.voicemailList.clipToOutline = true

        binding.voicemailList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val itemCount = layoutManager.itemCount
                if (itemCount == 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= itemCount - 1 - listViewModel.loadMoreThreshold) {
                    listViewModel.loadMore()
                }
            }
        })

        adapter.voicemailClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                Log.i("$TAG Opening voicemail [${model.id}]")
                val navController = binding.voicemailNavContainer.findNavController()
                val action = VoicemailDetailFragmentDirections.actionGlobalVoicemailDetailFragment(model.id)
                navController.navigate(action)
            }
        }

        listViewModel.voicemails.observe(viewLifecycleOwner) {
            adapter.submitList(it)

            // Wait for adapter to have items before setting it in the
            // RecyclerView, otherwise scroll position isn't retained.
            if (binding.voicemailList.adapter != adapter) {
                binding.voicemailList.adapter = adapter
            }

            Log.i("$TAG Voicemail list ready with [${it.size}] item(s)")
        }

        binding.setRefreshClickListener {
            Log.i("$TAG Manual voicemail list refresh")
            listViewModel.refresh()
        }

        binding.setRetryClickListener {
            Log.i("$TAG Retrying voicemail list load after error")
            listViewModel.refresh()
        }

        // AbstractMainFragment related

        listViewModel.title.value = getString(R.string.ott_voicemail_bottom_nav_label)
        setViewModel(listViewModel)
        initViews(
            binding.slidingPaneLayout,
            binding.topBar,
            binding.bottomNavBar,
            R.id.voicemailListFragment
        )
    }

    override fun onResume() {
        super.onResume()

        // Refresh on open: another device of the store may have new
        // messages, and pending transcriptions may have landed.
        Log.i("$TAG Fragment resumed, refreshing voicemail list")
        listViewModel.refresh()
    }
}
