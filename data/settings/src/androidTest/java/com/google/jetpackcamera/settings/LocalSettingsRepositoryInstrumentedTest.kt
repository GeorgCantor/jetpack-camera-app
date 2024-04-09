/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.jetpackcamera.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.settings.DataStoreModule.provideDataStore
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.DEFAULT_CAMERA_APP_SETTINGS
import com.google.jetpackcamera.settings.model.DarkMode
import com.google.jetpackcamera.settings.model.DynamicRange
import com.google.jetpackcamera.settings.model.FlashMode
import com.google.jetpackcamera.settings.model.ImageOutputFormat
import com.google.jetpackcamera.settings.model.LensFacing
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalSettingsRepositoryInstrumentedTest {
    private val testContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var testDataStore: DataStore<JcaSettings>
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var repository: LocalSettingsRepository

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher())
        testDataStore = provideDataStore(testContext)
        datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        testDataStore = DataStoreFactory.create(
            serializer = JcaSettingsSerializer,
            scope = datastoreScope
        ) {
            testContext.dataStoreFile("test_jca_settings.pb")
        }
        repository = LocalSettingsRepository(testDataStore)
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "datastore"
        ).deleteRecursively()

        datastoreScope.cancel()
    }

    @Test
    fun repository_can_fetch_initial_datastore() = runTest {
        // if you've created a new setting value and this test is failing, be sure to check that
        // JcaSettingsSerializer.kt defaultValue has been properly modified :)

        val cameraAppSettings: CameraAppSettings = repository.getCameraAppSettings()

        advanceUntilIdle()
        assertThat(cameraAppSettings).isEqualTo(DEFAULT_CAMERA_APP_SETTINGS)
    }

    @Test
    fun can_update_dark_mode() = runTest {
        val initialDarkModeStatus = repository.getCameraAppSettings().darkMode
        repository.updateDarkModeStatus(DarkMode.LIGHT)
        val newDarkModeStatus = repository.getCameraAppSettings().darkMode

        advanceUntilIdle()
        assertThat(initialDarkModeStatus).isNotEqualTo(newDarkModeStatus)
        assertThat(initialDarkModeStatus).isEqualTo(DarkMode.SYSTEM)
        assertThat(newDarkModeStatus).isEqualTo(DarkMode.LIGHT)
    }

    @Test
    fun can_update_default_to_front_camera() = runTest {
        // default lens facing starts as BACK
        val initialDefaultLensFacing = repository.getCameraAppSettings().cameraLensFacing
        repository.updateDefaultLensFacing(LensFacing.FRONT)
        // default lens facing is now FRONT
        val newDefaultLensFacing = repository.getCameraAppSettings().cameraLensFacing
        advanceUntilIdle()

        assertThat(initialDefaultLensFacing).isEqualTo(LensFacing.BACK)
        assertThat(newDefaultLensFacing).isEqualTo(LensFacing.FRONT)
    }

    @Test
    fun can_update_flash_mode() = runTest {
        // default flash mode starts as OFF
        val initialFlashModeStatus = repository.getCameraAppSettings().flashMode
        repository.updateFlashModeStatus(FlashMode.ON)
        // default flash mode is now ON
        val newFlashModeStatus = repository.getCameraAppSettings().flashMode
        advanceUntilIdle()

        assertThat(initialFlashModeStatus).isEqualTo(FlashMode.OFF)
        assertThat(newFlashModeStatus).isEqualTo(FlashMode.ON)
    }

    @Test
    fun can_update_available_camera_lens() = runTest {
        // available cameras start true
        val initialFrontCamera = repository.getCameraAppSettings().isFrontCameraAvailable
        val initialBackCamera = repository.getCameraAppSettings().isBackCameraAvailable

        repository.updateAvailableCameraLens(frontLensAvailable = false, backLensAvailable = false)
        // available cameras now false
        advanceUntilIdle()
        val newFrontCamera = repository.getCameraAppSettings().isFrontCameraAvailable
        val newBackCamera = repository.getCameraAppSettings().isBackCameraAvailable

        assertThat(initialFrontCamera && initialBackCamera).isTrue()
        assertThat(newFrontCamera || newBackCamera).isFalse()
    }

    @Test
    fun can_update_dynamic_range() = runTest {
        val initialDynamicRange = repository.getCameraAppSettings().dynamicRange

        repository.updateDynamicRange(dynamicRange = DynamicRange.HLG10)

        advanceUntilIdle()

        val newDynamicRange = repository.getCameraAppSettings().dynamicRange

        assertThat(initialDynamicRange).isEqualTo(DynamicRange.SDR)
        assertThat(newDynamicRange).isEqualTo(DynamicRange.HLG10)
    }

    @Test
    fun can_update_supported_dynamic_ranges() = runTest {
        val initialSupportedDynamicRanges = repository.getCameraAppSettings().supportedDynamicRanges

        repository.updateSupportedDynamicRanges(
            supportedDynamicRanges = listOf(DynamicRange.SDR, DynamicRange.HLG10)
        )

        advanceUntilIdle()

        val newSupportedDynamicRanges = repository.getCameraAppSettings().supportedDynamicRanges

        assertThat(initialSupportedDynamicRanges).containsExactly(DynamicRange.SDR)
        assertThat(newSupportedDynamicRanges).containsExactly(DynamicRange.SDR, DynamicRange.HLG10)
    }

    @Test
    fun can_update_image_format() = runTest {
        val initialImageFormat = repository.getCameraAppSettings().imageFormat

        repository.updateImageFormat(imageFormat = ImageOutputFormat.JPEG_ULTRA_HDR)

        advanceUntilIdle()

        val newImageFormat = repository.getCameraAppSettings().imageFormat

        assertThat(initialImageFormat).isEqualTo(ImageOutputFormat.JPEG)
        assertThat(newImageFormat).isEqualTo(ImageOutputFormat.JPEG_ULTRA_HDR)
    }

    @Test
    fun can_update_supported_image_formats() = runTest {
        val initialSupportedImageFormats = repository.getCameraAppSettings().supportedImageFormats

        repository.updateSupportedImageFormats(
            supportedImageFormats = listOf(ImageOutputFormat.JPEG, ImageOutputFormat.JPEG_ULTRA_HDR)
        )

        advanceUntilIdle()

        val newSupportedImageFormats = repository.getCameraAppSettings().supportedImageFormats

        assertThat(initialSupportedImageFormats).containsExactly(ImageOutputFormat.JPEG)
        assertThat(newSupportedImageFormats).containsExactly(
            ImageOutputFormat.JPEG,
            ImageOutputFormat.JPEG_ULTRA_HDR
        )
    }
}
