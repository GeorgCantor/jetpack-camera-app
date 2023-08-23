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

package com.google.jetpackcamera.feature.postcapture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.jetpackcamera.storage.ImageCache
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.ByteBuffer
import javax.inject.Inject


private const val TAG = "PostCaptureViewModel"

@HiltViewModel
class PostCaptureViewModel @Inject constructor(
    private val imageCache: ImageCache,
) : ViewModel() {

    fun getBitmap() : Bitmap? {
        Log.d(TAG, "getBitmap")
        val image = imageCache.getImage()

        if (image != null) {
            Log.d(TAG, "imageCache.getImage is not null")
            return image
        } else {
            Log.d(TAG, "imageCache.getImage is null")
        }

        return null
    }
}
