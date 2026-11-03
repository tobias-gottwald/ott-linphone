/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
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
package org.linphone.ui.main.help.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.HelpFragmentBinding
import org.linphone.BuildConfig
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.help.viewmodel.HelpViewModel
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider

@UiThread
class HelpFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Help Fragment]"
    }

    private lateinit var binding: HelpFragmentBinding

    private lateinit var viewModel: HelpViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = HelpFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner

        viewModel = ViewModelProvider(this)[HelpViewModel::class.java]
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            goBack()
        }

        binding.setDebugClickListener {
            if (findNavController().currentDestination?.id == R.id.helpFragment) {
                val action = HelpFragmentDirections.actionHelpFragmentToDebugFragment()
                findNavController().navigate(action)
            }
        }

        binding.setUserGuideClickListener {
            val url = getString(R.string.website_user_guide_url)
            openUrlInBrowser(url)
        }

        binding.setPrivacyPolicyClickListener {
            val url = getString(R.string.website_privacy_policy_url)
            openUrlInBrowser(url)
        }

        binding.setLicensesClickListener {
            val url = getString(R.string.website_open_source_licences_usage_url)
            openUrlInBrowser(url)
        }

        binding.setTranslateClickListener {
            val url = getString(R.string.website_translate_weblate_url)
            openUrlInBrowser(url)
        }

        viewModel.openPlayStoreEvent.observe(viewLifecycleOwner) {
            it.consume {
                openAppInPlayStore()
            }
        }
    }

    private fun openAppInPlayStore() {
        val appId = BuildConfig.APPLICATION_ID
        Log.i("$TAG Trying to open the app's page [$appId] in the Play Store")
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$appId".toUri())
            startActivity(marketIntent)
        } catch (anfe: ActivityNotFoundException) {
            Log.w("$TAG Play Store app not found, falling back to web URL [$anfe]")
            openUrlInBrowser("https://play.google.com/store/apps/details?id=$appId")
        }
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        } catch (ise: IllegalStateException) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url], IllegalStateException: $ise"
            )
        } catch (anfe: ActivityNotFoundException) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url], ActivityNotFoundException: $anfe"
            )
        } catch (e: Exception) {
            Log.e(
                "$TAG Can't start ACTION_VIEW intent for URL [$url]: $e"
            )
        }
    }
}
