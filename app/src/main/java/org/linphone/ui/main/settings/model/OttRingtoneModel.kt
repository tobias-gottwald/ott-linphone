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
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.settings.model

import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.ott.OttRingtones
import org.linphone.utils.Event

/**
 * One selectable entry of the incoming-call ringtone chooser:
 * [OttRingtones.CHOICE_SYSTEM], one of the Yealink tones or
 * [OttRingtones.CHOICE_CUSTOM] (opens the Android system ringtone picker).
 */
class OttRingtoneModel
    @UiThread
    constructor(
    val choice: String,
    val label: String,
    val isSelected: Boolean,
    private val listener: OnClickListener
) {
    interface OnClickListener {
        fun onClicked(model: OttRingtoneModel)
    }

    private var actionDoneCallback: (() -> Unit)? = null

    @UiThread
    fun setActionDoneCallback(lambda: () -> Unit) {
        actionDoneCallback = lambda
    }

    @UiThread
    fun onClicked() {
        listener.onClicked(this)
        actionDoneCallback?.invoke()
    }
}

/**
 * View model of the ringtone chooser dialog: the ordered entry list plus a
 * dismiss event, mirroring the number/address picker dialog model.
 */
class OttRingtonePickerDialogModel
    @UiThread
    constructor(
    list: List<OttRingtoneModel>
) {
    val ringtones = MutableLiveData<List<OttRingtoneModel>>()

    val dismissEvent = MutableLiveData<Event<Boolean>>()

    init {
        for (model in list) {
            model.setActionDoneCallback {
                dismiss()
            }
        }
        ringtones.value = list
    }

    @UiThread
    fun dismiss() {
        dismissEvent.value = Event(true)
    }

    companion object {
        /**
         * Builds the chooser entries: system ringtone (default), Yealink 1..8
         * and the Android system picker entry, with [currentChoice] checked.
         */
        @UiThread
        fun buildEntries(
            currentChoice: String,
            listener: OttRingtoneModel.OnClickListener
        ): List<OttRingtoneModel> {
            val context = coreContext.context
            val entries = arrayListOf(
                OttRingtoneModel(
                    OttRingtones.CHOICE_SYSTEM,
                    context.getString(R.string.ott_ringtone_system_label),
                    currentChoice == OttRingtones.CHOICE_SYSTEM,
                    listener
                )
            )
            for (index in 1..OttRingtones.YEALINK_TONES_COUNT) {
                val choice = OttRingtones.yealinkChoice(index)
                entries.add(
                    OttRingtoneModel(
                        choice,
                        context.getString(R.string.ott_ringtone_yealink_label, index),
                        currentChoice == choice,
                        listener
                    )
                )
            }
            entries.add(
                OttRingtoneModel(
                    OttRingtones.CHOICE_CUSTOM,
                    context.getString(R.string.ott_ringtone_pick_from_device_label),
                    currentChoice == OttRingtones.CHOICE_CUSTOM,
                    listener
                )
            )
            return entries
        }
    }
}
