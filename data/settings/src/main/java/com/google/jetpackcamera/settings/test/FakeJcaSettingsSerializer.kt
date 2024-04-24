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
package com.google.jetpackcamera.settings.test

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.jetpackcamera.settings.AspectRatio
import com.google.jetpackcamera.settings.CaptureMode
import com.google.jetpackcamera.settings.DarkMode
import com.google.jetpackcamera.settings.DynamicRange
import com.google.jetpackcamera.settings.FlashMode
import com.google.jetpackcamera.settings.JcaSettings
import com.google.jetpackcamera.settings.LensFacing
import com.google.jetpackcamera.settings.PreviewStabilization
import com.google.jetpackcamera.settings.VideoStabilization
import com.google.protobuf.InvalidProtocolBufferException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FakeJcaSettingsSerializer(
    var failReadWithCorruptionException: Boolean = false
) : Serializer<JcaSettings> {

    override val defaultValue: JcaSettings = JcaSettings.newBuilder()
        .setDarkModeStatus(DarkMode.DARK_MODE_SYSTEM)
        .setDefaultLensFacing(LensFacing.LENS_FACING_BACK)
        .setFlashModeStatus(FlashMode.FLASH_MODE_OFF)
        .setAspectRatioStatus(AspectRatio.ASPECT_RATIO_NINE_SIXTEEN)
        .setCaptureModeStatus(CaptureMode.CAPTURE_MODE_MULTI_STREAM)
        .setStabilizePreview(PreviewStabilization.PREVIEW_STABILIZATION_UNDEFINED)
        .setStabilizeVideo(VideoStabilization.VIDEO_STABILIZATION_UNDEFINED)
        .setDynamicRangeStatus(DynamicRange.DYNAMIC_RANGE_SDR)
        .build()

    override suspend fun readFrom(input: InputStream): JcaSettings {
        if (failReadWithCorruptionException) {
            throw CorruptionException(
                "Corruption Exception",
                IOException()
            )
        }
        try {
            return JcaSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: JcaSettings, output: OutputStream) = t.writeTo(output)
}
