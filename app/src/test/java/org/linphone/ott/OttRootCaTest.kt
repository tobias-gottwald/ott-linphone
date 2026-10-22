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
import android.content.res.AssetManager
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.core.Core
import org.linphone.core.tools.Log

class OttRootCaTest {

    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var assets: AssetManager

    @MockK(relaxed = true)
    private lateinit var core: Core

    private lateinit var filesDir: java.io.File

    private val pem = """
        -----BEGIN CERTIFICATE-----
        FAKE-PEM-BODY
        -----END CERTIFICATE-----
    """.trimIndent().toByteArray()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        mockkStatic(Log::class)
        every { Log.i(*anyVararg()) } just Runs
        every { Log.w(*anyVararg()) } just Runs
        every { Log.e(*anyVararg()) } just Runs

        filesDir = Files.createTempDirectory("ott-root-ca-test").toFile()
        every { context.filesDir } returns filesDir
        every { context.assets } returns assets
        every { assets.open(OttRootCa.ASSET_NAME) } returns ByteArrayInputStream(pem)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `null root CA is replaced by the pinned bundle`() {
        every { core.rootCa } returns null

        OttRootCa.apply(context, core)

        val target = java.io.File(filesDir, OttRootCa.ASSET_NAME)
        assertTrue(target.exists())
        assertArrayEquals(pem, target.readBytes())
        verify(exactly = 1) { core.rootCa = target.absolutePath }
    }

    @Test
    fun `SDK default bundle path is replaced by the pinned bundle`() {
        every { core.rootCa } returns "${filesDir.absolutePath}/share/linphone/rootca.pem"

        OttRootCa.apply(context, core)

        verify(exactly = 1) {
            core.rootCa = java.io.File(filesDir, OttRootCa.ASSET_NAME).absolutePath
        }
    }

    @Test
    fun `MDM or provisioning provided root CA is kept`() {
        every { core.rootCa } returns "/data/local/tmp/company-ca.pem"

        OttRootCa.apply(context, core)

        verify(exactly = 0) { core.rootCa = any() }
    }

    @Test
    fun `already pinned root CA is not set again`() {
        every { core.rootCa } returns java.io.File(filesDir, OttRootCa.ASSET_NAME).absolutePath

        OttRootCa.apply(context, core)

        verify(exactly = 0) { core.rootCa = any() }
    }

    @Test
    fun `unparsable asset leaves core untouched`() {
        every { assets.open(OttRootCa.ASSET_NAME) } returns
            ByteArrayInputStream("not a certificate".toByteArray())

        OttRootCa.apply(context, core)

        assertFalse(java.io.File(filesDir, OttRootCa.ASSET_NAME).exists())
        verify(exactly = 0) { core.rootCa = any() }
    }

    @Test
    fun `unreadable asset leaves core untouched`() {
        every { assets.open(OttRootCa.ASSET_NAME) } throws java.io.FileNotFoundException("missing")

        OttRootCa.apply(context, core)

        verify(exactly = 0) { core.rootCa = any() }
    }
}
