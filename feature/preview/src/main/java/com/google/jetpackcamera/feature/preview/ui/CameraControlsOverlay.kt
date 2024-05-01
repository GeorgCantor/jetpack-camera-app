/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.jetpackcamera.feature.preview.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.jetpackcamera.feature.preview.MultipleEventsCutter
import com.google.jetpackcamera.feature.preview.PreviewMode
import com.google.jetpackcamera.feature.preview.PreviewUiState
import com.google.jetpackcamera.feature.preview.PreviewViewModel
import com.google.jetpackcamera.feature.preview.VideoRecordingState
import com.google.jetpackcamera.feature.preview.quicksettings.ui.QuickSettingsIndicators
import com.google.jetpackcamera.feature.preview.quicksettings.ui.ToggleQuickSettingsButton
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.FlashMode
import com.google.jetpackcamera.settings.model.LensFacing
import com.google.jetpackcamera.settings.model.Stabilization
import com.google.jetpackcamera.settings.model.SystemConstraints
import com.google.jetpackcamera.settings.model.TYPICAL_SYSTEM_CONSTRAINTS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ZoomLevelDisplayState(showInitially: Boolean = false) {
    private var _showZoomLevel = mutableStateOf(showInitially)
    val showZoomLevel: Boolean get() = _showZoomLevel.value

    suspend fun showZoomLevel() {
        _showZoomLevel.value = true
        delay(3000)
        _showZoomLevel.value = false
    }
}

@Composable
fun CameraControlsOverlay(
    previewUiState: PreviewUiState.Ready,
    previewMode: PreviewMode,
    blinkState: BlinkState,
    modifier: Modifier = Modifier,
    zoomLevelDisplayState: ZoomLevelDisplayState = remember { ZoomLevelDisplayState() },
    onNavigateToSettings: () -> Unit = {},
    onFlipCamera: () -> Unit = {},
    onChangeFlash: (FlashMode) -> Unit = {},
    onToggleQuickSettings: () -> Unit = {},
    onCaptureImage: () -> Unit = {},
    onCaptureImageWithUri: (
        ContentResolver,
        Uri?,
        Boolean,
        (PreviewViewModel.ImageCaptureEvent) -> Unit
    ) -> Unit = { _, _, _, _ -> },
    onStartVideoRecording: () -> Unit = {},
    onStopVideoRecording: () -> Unit = {}
) {
    // Show the current zoom level for a short period of time, only when the level changes.
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(previewUiState.zoomScale) {
        if (firstRun) {
            firstRun = false
        } else {
            zoomLevelDisplayState.showZoomLevel()
        }
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.fillMaxSize()) {
            if (previewUiState.videoRecordingState == VideoRecordingState.INACTIVE) {
                ControlsTop(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    isQuickSettingsOpen = previewUiState.quickSettingsIsOpen,
                    currentCameraSettings = previewUiState.currentCameraSettings,
                    onNavigateToSettings = onNavigateToSettings,
                    onChangeFlash = onChangeFlash,
                    onToggleQuickSettings = onToggleQuickSettings
                )
            }



            ControlsBottom(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                audioAmplitude = previewUiState.audioAmplitude.toFloat(),
                zoomLevel = previewUiState.zoomScale,
                showZoomLevel = zoomLevelDisplayState.showZoomLevel,
                isQuickSettingsOpen = previewUiState.quickSettingsIsOpen,
                systemConstraints = previewUiState.systemConstraints,
                videoRecordingState = previewUiState.videoRecordingState,
                previewMode = previewMode,
                onFlipCamera = onFlipCamera,
                onCaptureImage = onCaptureImage,
                onCaptureImageWithUri = onCaptureImageWithUri,
                onToggleQuickSettings = onToggleQuickSettings,
                onStartVideoRecording = onStartVideoRecording,
                onStopVideoRecording = onStopVideoRecording,
                blinkState = blinkState
            )
        }
    }
}

@Composable
private fun ControlsTop(
    isQuickSettingsOpen: Boolean,
    currentCameraSettings: CameraAppSettings,
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onChangeFlash: (FlashMode) -> Unit = {},
    onToggleQuickSettings: () -> Unit = {}
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // button to open default settings page
            SettingsNavButton(
                modifier = Modifier
                    .padding(12.dp)
                    .testTag(SETTINGS_BUTTON),
                onNavigateToSettings = onNavigateToSettings
            )
            if (!isQuickSettingsOpen) {
                QuickSettingsIndicators(
                    currentFlashMode = currentCameraSettings.flashMode,
                    onFlashModeClick = onChangeFlash
                )
            }
        }

        // quick settings button
        ToggleQuickSettingsButton(onToggleQuickSettings, isQuickSettingsOpen)

        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StabilizationIcon(
                videoStabilization = currentCameraSettings.videoCaptureStabilization,
                previewStabilization = currentCameraSettings.previewStabilization
            )
        }
    }
}

@Composable
private fun ControlsBottom(
    modifier: Modifier = Modifier,
    audioAmplitude: Float,
    zoomLevel: Float,
    showZoomLevel: Boolean,
    isQuickSettingsOpen: Boolean,
    systemConstraints: SystemConstraints,
    videoRecordingState: VideoRecordingState,
    previewMode: PreviewMode,
    onFlipCamera: () -> Unit = {},
    onCaptureImage: () -> Unit = {},
    onCaptureImageWithUri: (
        ContentResolver,
        Uri?,
        Boolean,
        (PreviewViewModel.ImageCaptureEvent) -> Unit
    ) -> Unit = { _, _, _, _ -> },
    onToggleQuickSettings: () -> Unit = {},
    onStartVideoRecording: () -> Unit = {},
    onStopVideoRecording: () -> Unit = {},
    blinkState: BlinkState? = null
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showZoomLevel) {
            ZoomScaleText(zoomLevel)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (!isQuickSettingsOpen && videoRecordingState == VideoRecordingState.INACTIVE) {
                    FlipCameraButton(
                        modifier = Modifier.testTag(FLIP_CAMERA_BUTTON),
                        onClick = onFlipCamera,
                        // enable only when phone has front and rear camera
                        enabledCondition = systemConstraints.availableLenses.size > 1
                    )
                }
            }
            CaptureButton(
                previewMode = previewMode,
                isQuickSettingsOpen = isQuickSettingsOpen,
                videoRecordingState = videoRecordingState,
                onCaptureImage = onCaptureImage,
                onCaptureImageWithUri = onCaptureImageWithUri,
                onToggleQuickSettings = onToggleQuickSettings,
                onStartVideoRecording = onStartVideoRecording,
                onStopVideoRecording = onStopVideoRecording,
                blinkState = blinkState
            )
            Row(Modifier.weight(1f)) {
                if (videoRecordingState == VideoRecordingState.ACTIVE) {
                    AmplitudeVisualizer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        sizeScale = 50,
                        audioAmplitude = audioAmplitude,
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureButton(
    previewMode: PreviewMode,
    isQuickSettingsOpen: Boolean,
    videoRecordingState: VideoRecordingState,
    modifier: Modifier = Modifier,
    onCaptureImage: () -> Unit = {},
    onCaptureImageWithUri: (
        ContentResolver,
        Uri?,
        Boolean,
        (PreviewViewModel.ImageCaptureEvent) -> Unit
    ) -> Unit = { _, _, _, _ -> },
    onToggleQuickSettings: () -> Unit = {},
    onStartVideoRecording: () -> Unit = {},
    onStopVideoRecording: () -> Unit = {},
    blinkState: BlinkState? = null
) {
    val multipleEventsCutter = remember { MultipleEventsCutter() }
    val context = LocalContext.current
    CaptureButton(
        modifier = modifier.testTag(CAPTURE_BUTTON),
        onClick = {
            blinkState?.scope?.launch { blinkState.play() }
            multipleEventsCutter.processEvent {
                when (previewMode) {
                    is PreviewMode.StandardMode -> {
                        onCaptureImageWithUri(
                            context.contentResolver,
                            null,
                            true,
                            previewMode.onImageCapture
                        )
                    }

                    is PreviewMode.ExternalImageCaptureMode -> {
                        onCaptureImageWithUri(
                            context.contentResolver,
                            previewMode.imageCaptureUri,
                            false,
                            previewMode.onImageCapture
                        )
                    }
                }
            }
            if (isQuickSettingsOpen) {
                onToggleQuickSettings()
            }
        },
        onLongPress = {
            onStartVideoRecording()
            if (isQuickSettingsOpen) {
                onToggleQuickSettings()
            }
        },
        onRelease = { onStopVideoRecording() },
        videoRecordingState = videoRecordingState
    )
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsTop_QuickSettingsOpen() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsTop(
            isQuickSettingsOpen = true,
            currentCameraSettings = CameraAppSettings()
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsTop_QuickSettingsClosed() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsTop(
            isQuickSettingsOpen = false,
            currentCameraSettings = CameraAppSettings()
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsTop_FlashModeOn() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsTop(
            isQuickSettingsOpen = false,
            currentCameraSettings = CameraAppSettings(flashMode = FlashMode.ON)
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsTop_FlashModeAuto() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsTop(
            isQuickSettingsOpen = false,
            currentCameraSettings = CameraAppSettings(flashMode = FlashMode.AUTO)
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsTop_WithStabilization() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsTop(
            isQuickSettingsOpen = false,
            currentCameraSettings = CameraAppSettings(
                videoCaptureStabilization = Stabilization.ON,
                previewStabilization = Stabilization.ON
            )
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsBottom() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsBottom(
            zoomLevel = 1.3f,
            showZoomLevel = true,
            isQuickSettingsOpen = false,
            systemConstraints = TYPICAL_SYSTEM_CONSTRAINTS,
            videoRecordingState = VideoRecordingState.INACTIVE,
            previewMode = PreviewMode.StandardMode {},
            audioAmplitude = 0f
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsBottom_NoZoomLevel() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsBottom(
            zoomLevel = 1.3f,
            showZoomLevel = false,
            isQuickSettingsOpen = false,
            systemConstraints = TYPICAL_SYSTEM_CONSTRAINTS,
            videoRecordingState = VideoRecordingState.INACTIVE,
            previewMode = PreviewMode.StandardMode {},
            audioAmplitude = 0f
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsBottom_QuickSettingsOpen() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsBottom(
            zoomLevel = 1.3f,
            showZoomLevel = true,
            isQuickSettingsOpen = true,
            systemConstraints = TYPICAL_SYSTEM_CONSTRAINTS,
            videoRecordingState = VideoRecordingState.INACTIVE,
            previewMode = PreviewMode.StandardMode {},
                    audioAmplitude = 0f

        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsBottom_NoFlippableCamera() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsBottom(
            zoomLevel = 1.3f,
            showZoomLevel = true,
            isQuickSettingsOpen = false,
            systemConstraints = TYPICAL_SYSTEM_CONSTRAINTS.copy(
                availableLenses = listOf(LensFacing.FRONT),
                perLensConstraints = mapOf(
                    LensFacing.FRONT to
                            TYPICAL_SYSTEM_CONSTRAINTS.perLensConstraints[LensFacing.FRONT]!!
                )
            ),
            videoRecordingState = VideoRecordingState.INACTIVE,
            previewMode = PreviewMode.StandardMode {},
            audioAmplitude = 0f

        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun Preview_ControlsBottom_Recording() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        ControlsBottom(
            zoomLevel = 1.3f,
            showZoomLevel = true,
            isQuickSettingsOpen = false,
            systemConstraints = TYPICAL_SYSTEM_CONSTRAINTS,
            videoRecordingState = VideoRecordingState.ACTIVE,
            previewMode = PreviewMode.StandardMode {},
            audioAmplitude = 0.5f
        )
    }
}
