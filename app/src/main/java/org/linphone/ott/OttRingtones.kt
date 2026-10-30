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
package org.linphone.ott

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import java.io.File
import org.linphone.R
import org.linphone.core.Core
import org.linphone.core.tools.Log

/**
 * Incoming call ringtone selection for the OTT app.
 *
 * Offers the Android system ringtone (default) and the eight bundled
 * Yealink tones shipped as MP3 assets under `assets/sounds/ott/`.
 *
 * SDK contract (verified against linphone-sdk-android 5.5.16):
 * - On Android `use_native_ringing` defaults to 1, so incoming call ringing
 *   is done by the SDK's `AudioHelper` through `android.media.Ringtone` /
 *   `android.media.MediaPlayer` (NOT the mediastreamer2 WAV-only file
 *   player, which only runs if native ringing were disabled).
 * - `core.ring == null/""` makes `AudioHelper` resolve the CURRENT device
 *   default ringtone via `RingtoneManager` at every incoming call, so
 *   system ringtone changes are always honored.
 * - `core.ring == "/absolute/path"` is played through a `MediaPlayer`
 *   `FileInputStream` (MP3 is supported, playback loops).
 * - `core.ring == "content://..."` is played through `MediaPlayer`
 *   `setDataSource(context, uri)` (the Android system picker flow).
 * - `linphone_core_set_ring` persists the value as `[sound] local_ring`
 *   in the linphonerc, but we keep the named choice in our own
 *   preferences and re-apply it at every core start so a lost/stale
 *   file (or a restored backup) self-heals instead of ringing nothing.
 */
object OttRingtones {
    private const val TAG = "[OTT Ringtones]"

    private const val ASSET_DIR = "sounds/ott"

    /** App-private directory (under [Context.filesDir]) the assets are copied to. */
    private const val FILES_DIR = "ringtones"

    private const val PREFERENCES_NAME = "ott_ringtones"
    private const val PREFERENCE_CHOICE = "choice"
    private const val PREFERENCE_CUSTOM_URI = "custom_uri"

    /** Legacy preference state: not written yet (first start after update). */
    private const val CHOICE_UNSET = ""

    const val CHOICE_SYSTEM = "system"
    const val CHOICE_CUSTOM = "custom"

    const val YEALINK_TONES_COUNT = 8

    private val YEALINK_CHOICE_PREFIX = "yealink"

    @AnyThread
    fun yealinkChoice(index: Int): String {
        return "$YEALINK_CHOICE_PREFIX$index"
    }

    @AnyThread
    fun yealinkIndexOf(choice: String): Int {
        if (!choice.startsWith(YEALINK_CHOICE_PREFIX)) return -1
        return choice.removePrefix(YEALINK_CHOICE_PREFIX).toIntOrNull() ?: -1
    }

    /**
     * The persisted named choice. Defaults to [CHOICE_SYSTEM] (out of the box
     * the app follows the actual system default ringtone). Never auto-selects
     * a Yealink tone.
     */
    @AnyThread
    fun getChoice(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val choice = preferences.getString(PREFERENCE_CHOICE, CHOICE_UNSET) ?: CHOICE_UNSET
        if (choice == CHOICE_UNSET || choice == CHOICE_SYSTEM) return CHOICE_SYSTEM
        if (choice == CHOICE_CUSTOM) return CHOICE_CUSTOM
        val index = yealinkIndexOf(choice)
        return if (index in 1..YEALINK_TONES_COUNT) choice else CHOICE_SYSTEM
    }

    @AnyThread
    fun getCustomUri(context: Context): String? {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return preferences.getString(PREFERENCE_CUSTOM_URI, null)
    }

    /**
     * Localized display label of a choice (used as settings summary and as
     * the selected entry label in the chooser dialog).
     */
    @AnyThread
    fun labelForChoice(context: Context, choice: String): String {
        return when {
            choice == CHOICE_SYSTEM -> context.getString(R.string.ott_ringtone_system_label)
            choice == CHOICE_CUSTOM -> {
                val uri = getCustomUri(context)
                if (uri.isNullOrEmpty()) {
                    context.getString(R.string.ott_ringtone_custom_label)
                } else {
                    val title = try {
                        RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
                    } catch (e: Exception) {
                        Log.w("$TAG Failed to resolve custom ringtone title for [$uri]: $e")
                        null
                    }
                    if (title.isNullOrEmpty()) {
                        context.getString(R.string.ott_ringtone_custom_label)
                    } else {
                        context.getString(R.string.ott_ringtone_custom_with_name_label, title)
                    }
                }
            }
            else -> {
                val index = yealinkIndexOf(choice)
                if (index in 1..YEALINK_TONES_COUNT) {
                    context.getString(R.string.ott_ringtone_yealink_label, index)
                } else {
                    context.getString(R.string.ott_ringtone_system_label)
                }
            }
        }
    }

    /**
     * Copies the given Yealink tone out of the APK assets into app-private
     * storage (the native ring path only plays real files, not assets) and
     * returns the absolute path, or null if the asset is missing or the
     * copy failed (logged). The bundled file names are unique and their
     * content never changes, so an existing target is used as-is, like the
     * SDK's own asset copy convention.
     */
    @WorkerThread
    fun installYealinkRingtone(context: Context, index: Int): String? {
        if (index !in 1..YEALINK_TONES_COUNT) {
            Log.e("$TAG Invalid Yealink ringtone index [$index]")
            return null
        }

        val assetName = "yealink$index.mp3"
        val directory = File(context.filesDir, FILES_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e("$TAG Failed to create ringtone directory [${directory.absolutePath}]")
            return null
        }

        val target = File(directory, assetName)
        if (target.exists()) {
            return target.absolutePath
        }

        try {
            context.assets.open("$ASSET_DIR/$assetName").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Remove a possibly partial copy so the next start retries.
            target.delete()
            Log.e("$TAG Failed to copy bundled ringtone [$assetName] to [${target.absolutePath}]: $e")
            return null
        }
        Log.i("$TAG Installed bundled ringtone [$assetName] to [${target.absolutePath}]")
        return target.absolutePath
    }

    /**
     * Re-applies the persisted choice to the core. Called at every core
     * start; no-op while the core already matches, so an explicit user
     * choice is never reset and out-of-the-box devices keep following the
     * system default ringtone.
     */
    @WorkerThread
    fun applyConfiguredRingtone(context: Context, core: Core) {
        migrateLegacyRingtoneIfNeeded(context, core)

        when (val choice = getChoice(context)) {
            CHOICE_SYSTEM -> {
                // "" makes the SDK resolve the actual system default ringtone
                // at every incoming call (an empty local_ring is reloaded as
                // an empty string by the core, same behavior).
                if (!core.ring.isNullOrEmpty()) {
                    core.ring = ""
                    Log.i("$TAG Ringtone selection is [system], core now follows the device default ringtone")
                }
            }
            CHOICE_CUSTOM -> {
                val uri = getCustomUri(context)
                if (uri.isNullOrEmpty()) {
                    Log.w("$TAG Custom ringtone selected but no URI stored, falling back to system default")
                    core.ring = ""
                } else if (core.ring != uri) {
                    core.ring = uri
                    Log.i("$TAG Applied custom ringtone [$uri]")
                }
            }
            else -> {
                val path = installYealinkRingtone(context, yealinkIndexOf(choice))
                if (path == null) {
                    Log.e("$TAG Selected ringtone [$choice] unavailable, falling back to system default")
                    core.ring = ""
                } else if (core.ring != path) {
                    core.ring = path
                    Log.i("$TAG Applied ringtone [$choice] from [$path]")
                }
            }
        }
    }

    /**
     * One-time adoption of a ringtone set before this feature existed (the
     * legacy Android system picker wrote it straight into the core config):
     * keep it as the custom choice instead of resetting it to system.
     */
    @WorkerThread
    private fun migrateLegacyRingtoneIfNeeded(context: Context, core: Core) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.contains(PREFERENCE_CHOICE)) return

        val legacy = core.ring.orEmpty()
        if (legacy.isNotEmpty()) {
            Log.i("$TAG Adopting previously configured ringtone [$legacy] as custom choice")
            preferences.edit()
                .putString(PREFERENCE_CHOICE, CHOICE_CUSTOM)
                .putString(PREFERENCE_CUSTOM_URI, legacy)
                .apply()
        } else {
            preferences.edit().putString(PREFERENCE_CHOICE, CHOICE_SYSTEM).apply()
        }
    }

    /**
     * Applies and persists a choice ("system", "yealink1".."yealink8" or
     * "custom"). Returns the choice actually applied.
     */
    @WorkerThread
    fun select(context: Context, core: Core, choice: String): String {
        return when {
            choice == CHOICE_SYSTEM -> {
                selectSystem(context, core)
                CHOICE_SYSTEM
            }
            choice == CHOICE_CUSTOM -> {
                // The custom URI is set through the Android system picker
                // (selectCustom); until then keep the previous state.
                Log.w("$TAG Custom choice requires a URI from the system picker, ignoring")
                getChoice(context)
            }
            yealinkIndexOf(choice) in 1..YEALINK_TONES_COUNT -> {
                selectYealink(context, core, yealinkIndexOf(choice))
                choice
            }
            else -> {
                Log.e("$TAG Unknown ringtone choice [$choice], ignoring")
                getChoice(context)
            }
        }
    }

    @WorkerThread
    fun selectSystem(context: Context, core: Core) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFERENCE_CHOICE, CHOICE_SYSTEM)
            .apply()
        core.ring = ""
        Log.i("$TAG Ringtone selection saved: system default")
    }

    @WorkerThread
    fun selectYealink(context: Context, core: Core, index: Int) {
        val path = installYealinkRingtone(context, index)
        if (path == null) {
            Log.e("$TAG Cannot select Yealink ringtone [$index], asset missing")
            return
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFERENCE_CHOICE, yealinkChoice(index))
            .apply()
        core.ring = path
        Log.i("$TAG Ringtone selection saved: Yealink $index")
    }

    @WorkerThread
    fun selectCustom(context: Context, core: Core, uri: Uri) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREFERENCE_CHOICE, CHOICE_CUSTOM)
            .putString(PREFERENCE_CUSTOM_URI, uri.toString())
            .apply()
        core.ring = uri.toString()
        Log.i("$TAG Ringtone selection saved: custom [$uri]")
    }
}
