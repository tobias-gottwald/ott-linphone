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
package org.linphone.ui.main.voicemail.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.databinding.VoicemailListCellBinding
import org.linphone.ui.main.voicemail.model.VoicemailModel
import org.linphone.utils.Event

/**
 * List adapter for the store voicemail rows (oc-aa79), same pattern as the
 * history adapter: data-bound cells, click events consumed by the list
 * fragment.
 */
class VoicemailListAdapter :
    ListAdapter<VoicemailModel, VoicemailListAdapter.ViewHolder>(VoicemailDiffCallback()) {

    val voicemailClickedEvent: MutableLiveData<Event<VoicemailModel>> by lazy {
        MutableLiveData()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding: VoicemailListCellBinding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.voicemail_list_cell,
            parent,
            false
        )
        binding.lifecycleOwner = parent.findViewTreeLifecycleOwner()
        binding.setOnClickListener {
            binding.model?.let { model ->
                voicemailClickedEvent.value = Event(model)
            }
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        val binding: VoicemailListCellBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        @UiThread
        fun bind(model: VoicemailModel) {
            with(binding) {
                this.model = model
                executePendingBindings()
            }
        }
    }

    private class VoicemailDiffCallback : DiffUtil.ItemCallback<VoicemailModel>() {
        override fun areItemsTheSame(oldItem: VoicemailModel, newItem: VoicemailModel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VoicemailModel, newItem: VoicemailModel): Boolean {
            return oldItem.summary == newItem.summary
        }
    }
}
