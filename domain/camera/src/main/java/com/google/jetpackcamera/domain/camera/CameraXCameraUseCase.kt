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
package com.google.jetpackcamera.domain.camera

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio.RATIO_16_9
import androidx.camera.core.AspectRatio.RATIO_4_3
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange as CXDynamicRange
import androidx.camera.core.ExperimentalImageCaptureOutputFormat
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCapture.ScreenFlash
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.takePicture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoRecordEvent.Finalize.ERROR_NONE
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.os.BuildCompat
import com.google.jetpackcamera.domain.camera.CameraUseCase.ScreenFlashEvent.Type
import com.google.jetpackcamera.domain.camera.effects.SingleSurfaceForcingEffect
import com.google.jetpackcamera.settings.SettableConstraintsRepository
import com.google.jetpackcamera.settings.SettingsRepository
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraConstraints
import com.google.jetpackcamera.settings.model.CaptureMode
import com.google.jetpackcamera.settings.model.DynamicRange
import com.google.jetpackcamera.settings.model.FlashMode
import com.google.jetpackcamera.settings.model.ImageOutputFormat
import com.google.jetpackcamera.settings.model.LensFacing
import com.google.jetpackcamera.settings.model.LowLightBoost
import com.google.jetpackcamera.settings.model.Stabilization
import com.google.jetpackcamera.settings.model.SupportedStabilizationMode
import com.google.jetpackcamera.settings.model.SystemConstraints
import dagger.hilt.android.scopes.ViewModelScoped
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "CameraXCameraUseCase"
const val TARGET_FPS_AUTO = 0
const val TARGET_FPS_15 = 15
const val TARGET_FPS_30 = 30
const val TARGET_FPS_60 = 60

/**
 * CameraX based implementation for [CameraUseCase]
 */
@ViewModelScoped
class CameraXCameraUseCase
@Inject
constructor(
    private val application: Application,
    private val coroutineScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher,
    private val settingsRepository: SettingsRepository,
    private val constraintsRepository: SettableConstraintsRepository
) : CameraUseCase {
    private lateinit var cameraProvider: ProcessCameraProvider

    private lateinit var imageCaptureUseCase: ImageCapture

    private var videoCaptureUseCase: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var captureMode: CaptureMode
    private lateinit var systemConstraints: SystemConstraints
    private var disableVideoCapture by Delegates.notNull<Boolean>()

    private val screenFlashEvents: MutableSharedFlow<CameraUseCase.ScreenFlashEvent> =
        MutableSharedFlow()
    private val focusMeteringEvents =
        Channel<CameraEvent.FocusMeteringEvent>(capacity = Channel.CONFLATED)

    private val currentSettings = MutableStateFlow<CameraAppSettings?>(null)

    override suspend fun initialize(externalImageCapture: Boolean) {
        this.disableVideoCapture = externalImageCapture
        cameraProvider = ProcessCameraProvider.awaitInstance(application)

        // updates values for available cameras
        val availableCameraLenses =
            listOf(
                LensFacing.FRONT,
                LensFacing.BACK
            ).filter {
                cameraProvider.hasCamera(it.toCameraSelector())
            }

        // Build and update the system constraints
        systemConstraints = SystemConstraints(
            availableLenses = availableCameraLenses,
            perLensConstraints = buildMap {
                val availableCameraInfos = cameraProvider.availableCameraInfos
                for (lensFacing in availableCameraLenses) {
                    val selector = lensFacing.toCameraSelector()
                    selector.filter(availableCameraInfos).firstOrNull()?.let { camInfo ->
                        val supportedDynamicRanges =
                            Recorder.getVideoCapabilities(camInfo).supportedDynamicRanges
                                .mapNotNull(CXDynamicRange::toSupportedAppDynamicRange)
                                .toSet()

                        val supportedStabilizationModes = buildSet {
                            if (isPreviewStabilizationSupported(camInfo)) {
                                add(SupportedStabilizationMode.ON)
                            }

                            if (isVideoStabilizationSupported(camInfo)) {
                                add(SupportedStabilizationMode.HIGH_QUALITY)
                            }
                        }

                        val supportedFixedFrameRates = getSupportedFrameRates(camInfo)
                        val supportedImageFormats = getSupportedImageFormats(camInfo)

                        put(
                            lensFacing,
                            CameraConstraints(
                                supportedStabilizationModes = supportedStabilizationModes,
                                supportedFixedFrameRates = supportedFixedFrameRates,
                                supportedDynamicRanges = supportedDynamicRanges,
                                lowLightBoostSupport = getLowLightBoostDeviceSupport(),
                                supportedImageFormatsMap = mapOf(
                                    // Only JPEG is supported in single-stream mode, since
                                    // single-stream mode uses CameraEffect, which does not support
                                    // Ultra HDR now.
                                    Pair(CaptureMode.SINGLE_STREAM, setOf(ImageOutputFormat.JPEG)),
                                    Pair(CaptureMode.MULTI_STREAM, supportedImageFormats)
                                )
                            )
                        )
                    }
                }
            }
        )

        constraintsRepository.updateSystemConstraints(systemConstraints)

        currentSettings.value =
            settingsRepository.defaultCameraAppSettings.first()
                .tryApplyDynamicRangeConstraints()
                .tryApplyAspectRatioForExternalCapture(externalImageCapture)
                .tryApplyImageFormatConstraints()

        imageCaptureUseCase = ImageCapture.Builder()
            .setResolutionSelector(
                getResolutionSelector(
                    settingsRepository.defaultCameraAppSettings.first().aspectRatio
                )
            ).build()
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(BuildCompat.PrereleaseSdkCheck::class)
    private fun getLowLightBoostDeviceSupport() =
        when(BuildCompat.isAtLeastV()) {
            true -> cameraProvider.availableCameraInfos.map { cameraInfo ->
                Camera2CameraInfo
                    .from(cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.contains(CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
            }.any()
            false -> false
        }

    /**
     * Returns the union of supported stabilization modes for a device's cameras
     */
    private fun getDeviceSupportedStabilizations(): Set<SupportedStabilizationMode> {
        val deviceSupportedStabilizationModes = mutableSetOf<SupportedStabilizationMode>()

        cameraProvider.availableCameraInfos.forEach { cameraInfo ->
            if (isPreviewStabilizationSupported(cameraInfo)) {
                deviceSupportedStabilizationModes.add(SupportedStabilizationMode.ON)
            }
            if (isVideoStabilizationSupported(cameraInfo)) {
                deviceSupportedStabilizationModes.add(SupportedStabilizationMode.HIGH_QUALITY)
            }
        }
        return deviceSupportedStabilizationModes
    }

    /**
     * Camera settings that persist as long as a camera is running.
     *
     * Any change in these settings will require calling [ProcessCameraProvider.runWith] with
     * updates [CameraSelector] and/or [UseCaseGroup]
     */
    private data class PerpetualSessionSettings(
        val cameraSelector: CameraSelector,
        val aspectRatio: AspectRatio,
        val captureMode: CaptureMode,
        val targetFrameRate: Int,
        val stabilizePreviewMode: Stabilization,
        val stabilizeVideoMode: Stabilization,
        val dynamicRange: DynamicRange,
        val imageFormat: ImageOutputFormat
    )

    /**
     * Camera settings that can change while the camera is running.
     *
     * Any changes in these settings can be applied either directly to use cases via their
     * setter methods or to [androidx.camera.core.CameraControl].
     * The use cases typically will not need to be re-bound.
     */
    private data class TransientSessionSettings(
        val flashMode: FlashMode,
        val zoomScale: Float,
        val audioMuted: Boolean
    )

    override suspend fun runCamera() = coroutineScope {
        Log.d(TAG, "runCamera")

        val transientSettings = MutableStateFlow<TransientSessionSettings?>(null)
        currentSettings
            .filterNotNull()
            .map { currentCameraSettings ->
                transientSettings.value = TransientSessionSettings(
                    flashMode = currentCameraSettings.flashMode,
                    audioMuted = currentCameraSettings.audioMuted,
                    zoomScale = currentCameraSettings.zoomScale
                )

                val cameraSelector = when (currentCameraSettings.cameraLensFacing) {
                    LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                }

                PerpetualSessionSettings(
                    cameraSelector = cameraSelector,
                    aspectRatio = currentCameraSettings.aspectRatio,
                    captureMode = currentCameraSettings.captureMode,
                    targetFrameRate = currentCameraSettings.targetFrameRate,
                    stabilizePreviewMode = currentCameraSettings.previewStabilization,
                    stabilizeVideoMode = currentCameraSettings.videoCaptureStabilization,
                    dynamicRange = currentCameraSettings.dynamicRange,
                    imageFormat = currentCameraSettings.imageFormat
                )
            }.distinctUntilChanged()
            .collectLatest { sessionSettings ->
                Log.d(TAG, "Starting new camera session")
                val cameraInfo = sessionSettings.cameraSelector.filter(
                    cameraProvider.availableCameraInfos
                ).first()

                val lensFacing = sessionSettings.cameraSelector.toAppLensFacing()
                val cameraConstraints = checkNotNull(
                    systemConstraints.perLensConstraints[lensFacing]
                ) {
                    "Unable to retrieve CameraConstraints for $lensFacing. " +
                        "Was the use case initialized?"
                }

                val initialTransientSettings = transientSettings
                    .filterNotNull()
                    .first()

                val useCaseGroup = createUseCaseGroup(
                    sessionSettings,
                    initialTransientSettings,
                    cameraConstraints.supportedStabilizationModes,
                    effect = when (sessionSettings.captureMode) {
                        CaptureMode.SINGLE_STREAM -> SingleSurfaceForcingEffect(coroutineScope)
                        CaptureMode.MULTI_STREAM -> null
                    }
                )

                var prevTransientSettings = initialTransientSettings
                cameraProvider.runWith(sessionSettings.cameraSelector, useCaseGroup) { camera ->
                    Log.d(TAG, "Camera session started")

                    launch {
                        focusMeteringEvents.consumeAsFlow().collect {
                            val focusMeteringAction =
                                FocusMeteringAction.Builder(it.meteringPoint).build()
                            Log.d(TAG, "Starting focus and metering")
                            camera.cameraControl.startFocusAndMetering(focusMeteringAction)
                        }
                    }

                    transientSettings.filterNotNull().collectLatest { newTransientSettings ->
                        // Apply camera control settings
                        if (prevTransientSettings.zoomScale != newTransientSettings.zoomScale) {
                            cameraInfo.zoomState.value?.let { zoomState ->
                                val finalScale =
                                    (zoomState.zoomRatio * newTransientSettings.zoomScale).coerceIn(
                                        zoomState.minZoomRatio,
                                        zoomState.maxZoomRatio
                                    )
                                camera.cameraControl.setZoomRatio(finalScale)
                                _zoomScale.value = finalScale
                            }
                        }

                        if (prevTransientSettings.flashMode != newTransientSettings.flashMode) {
                            setFlashModeInternal(
                                flashMode = newTransientSettings.flashMode,
                                isFrontFacing = sessionSettings.cameraSelector
                                    == CameraSelector.DEFAULT_FRONT_CAMERA
                            )
                        }

                        prevTransientSettings = newTransientSettings
                    }
                }
            }
    }

    override suspend fun takePicture(onCaptureStarted: (() -> Unit)) {
        try {
            val imageProxy = imageCaptureUseCase.takePicture(onCaptureStarted)
            Log.d(TAG, "onCaptureSuccess")
            imageProxy.close()
        } catch (exception: Exception) {
            Log.d(TAG, "takePicture onError: $exception")
            throw exception
        }
    }

    // TODO(b/319733374): Return bitmap for external mediastore capture without URI
    override suspend fun takePicture(
        onCaptureStarted: (() -> Unit),
        contentResolver: ContentResolver,
        imageCaptureUri: Uri?,
        ignoreUri: Boolean
    ): ImageCapture.OutputFileResults {
        val eligibleContentValues = getEligibleContentValues()
        val outputFileOptions: OutputFileOptions
        if (ignoreUri) {
            val formatter = SimpleDateFormat(
                "yyyy-MM-dd-HH-mm-ss-SSS",
                Locale.US
            )
            val filename = "JCA-${formatter.format(Calendar.getInstance().time)}.jpg"
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            outputFileOptions = OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
        } else if (imageCaptureUri == null) {
            val e = RuntimeException("Null Uri is provided.")
            Log.d(TAG, "takePicture onError: $e")
            throw e
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(imageCaptureUri)
                if (outputStream != null) {
                    outputFileOptions =
                        OutputFileOptions.Builder(
                            contentResolver.openOutputStream(imageCaptureUri)!!
                        ).build()
                } else {
                    val e = RuntimeException("Provider recently crashed.")
                    Log.d(TAG, "takePicture onError: $e")
                    throw e
                }
            } catch (e: FileNotFoundException) {
                Log.d(TAG, "takePicture onError: $e")
                throw e
            }
        }
        try {
            val outputFileResults = imageCaptureUseCase.takePicture(
                outputFileOptions,
                onCaptureStarted
            )
            val relativePath =
                eligibleContentValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH)
            val displayName = eligibleContentValues.getAsString(
                MediaStore.Images.Media.DISPLAY_NAME
            )
            Log.d(TAG, "Saved image to $relativePath/$displayName")
            return outputFileResults
        } catch (exception: ImageCaptureException) {
            Log.d(TAG, "takePicture onError: $exception")
            throw exception
        }
    }

    private fun getEligibleContentValues(): ContentValues {
        val eligibleContentValues = ContentValues()
        eligibleContentValues.put(
            MediaStore.Images.Media.DISPLAY_NAME,
            Calendar.getInstance().time.toString()
        )
        eligibleContentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        eligibleContentValues.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES
        )
        return eligibleContentValues
    }

    override suspend fun startVideoRecording(
        onVideoRecord: (CameraUseCase.OnVideoRecordEvent) -> Unit
    ) {
        if (videoCaptureUseCase == null) {
            throw RuntimeException("Attempted video recording with null videoCapture use case")
        }
        Log.d(TAG, "recordVideo")
        // todo(b/336886716): default setting to enable or disable audio when permission is granted

        // ok. there is a difference between MUTING and ENABLING audio
        // audio must be enabled in order to be muted
        // if the video recording isnt started with audio enabled, you will not be able to unmute it
        // the toggle should only affect whether or not the audio is muted.
        // the permission will determine whether or not the audio is enabled.
        val audioEnabled = (
            checkSelfPermission(
                this.application.baseContext,
                Manifest.permission.RECORD_AUDIO
            )
                == PackageManager.PERMISSION_GRANTED
            )
        val captureTypeString =
            when (captureMode) {
                CaptureMode.MULTI_STREAM -> "MultiStream"
                CaptureMode.SINGLE_STREAM -> "SingleStream"
            }
        val name = "JCA-recording-${Date()}-$captureTypeString.mp4"
        val contentValues =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
            }
        val mediaStoreOutput =
            MediaStoreOutputOptions.Builder(
                application.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(contentValues)
                .build()

        val callbackExecutor: Executor =
            (
                currentCoroutineContext()[ContinuationInterceptor] as?
                    CoroutineDispatcher
                )?.asExecutor() ?: ContextCompat.getMainExecutor(application)
        recording =
            videoCaptureUseCase!!.output
                .prepareRecording(application, mediaStoreOutput)
                .apply {
                    if (audioEnabled) {
                        withAudioEnabled()
                    }
                }
                .start(callbackExecutor) { onVideoRecordEvent ->
                    run {
                        Log.d(TAG, onVideoRecordEvent.toString())
                        when (onVideoRecordEvent) {
                            is VideoRecordEvent.Finalize -> {
                                when (onVideoRecordEvent.error) {
                                    ERROR_NONE ->
                                        onVideoRecord(
                                            CameraUseCase.OnVideoRecordEvent.OnVideoRecorded
                                        )
                                    else ->
                                        onVideoRecord(
                                            CameraUseCase.OnVideoRecordEvent.OnVideoRecordError
                                        )
                                }
                            }
                            is VideoRecordEvent.Status -> {
                                onVideoRecord(
                                    CameraUseCase.OnVideoRecordEvent.OnVideoRecordStatus(
                                        onVideoRecordEvent.recordingStats.audioStats.audioAmplitude
                                    )
                                )
                            }
                        }
                    }
                }
        currentSettings.value?.audioMuted?.let { recording?.mute(it) }
    }

    override fun stopVideoRecording() {
        Log.d(TAG, "stopRecording")
        recording?.stop()
    }

    override fun setZoomScale(scale: Float) {
        currentSettings.update { old ->
            old?.copy(zoomScale = scale)
        }
    }

    // Could be improved by setting initial value only when camera is initialized
    private val _zoomScale = MutableStateFlow(1f)
    override fun getZoomScale(): StateFlow<Float> = _zoomScale.asStateFlow()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    override fun getSurfaceRequest(): StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    // Sets the camera to the designated lensFacing direction
    override suspend fun setLensFacing(lensFacing: LensFacing) {
        currentSettings.update { old ->
            if (systemConstraints.availableLenses.contains(lensFacing)) {
                old?.copy(cameraLensFacing = lensFacing)
                    ?.tryApplyDynamicRangeConstraints()
                    ?.tryApplyImageFormatConstraints()
            } else {
                old
            }
        }
    }

    private fun CameraAppSettings.tryApplyDynamicRangeConstraints(): CameraAppSettings {
        return systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedDynamicRanges) {
                val newDynamicRange = if (contains(dynamicRange)) {
                    dynamicRange
                } else {
                    DynamicRange.SDR
                }

                this@tryApplyDynamicRangeConstraints.copy(
                    dynamicRange = newDynamicRange
                )
            }
        } ?: this
    }

    private fun CameraAppSettings.tryApplyAspectRatioForExternalCapture(
        externalImageCapture: Boolean
    ): CameraAppSettings {
        if (externalImageCapture) {
            return this.copy(aspectRatio = AspectRatio.THREE_FOUR)
        }
        return this
    }

    private fun CameraAppSettings.tryApplyImageFormatConstraints(): CameraAppSettings {
        return systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedImageFormatsMap[captureMode]) {
                val newImageFormat = if (this != null && contains(imageFormat)) {
                    imageFormat
                } else {
                    ImageOutputFormat.JPEG
                }

                this@tryApplyImageFormatConstraints.copy(
                    imageFormat = newImageFormat
                )
            }
        } ?: this
    }

    override suspend fun tapToFocus(x: Float, y: Float) {
        Log.d(TAG, "tapToFocus, sending FocusMeteringEvent")

        getSurfaceRequest().filterNotNull().map { surfaceRequest ->
            SurfaceOrientedMeteringPointFactory(
                surfaceRequest.resolution.width.toFloat(),
                surfaceRequest.resolution.height.toFloat()
            )
        }.collectLatest { meteringPointFactory ->
            val meteringPoint = meteringPointFactory.createPoint(x, y)
            focusMeteringEvents.send(CameraEvent.FocusMeteringEvent(meteringPoint))
        }
    }

    override fun getScreenFlashEvents() = screenFlashEvents.asSharedFlow()
    override fun getCurrentSettings() = currentSettings.asStateFlow()

    override fun setFlashMode(flashMode: FlashMode) {
        currentSettings.update { old ->
            old?.copy(flashMode = flashMode)
        }
    }

    private fun setFlashModeInternal(flashMode: FlashMode, isFrontFacing: Boolean) {
        val isScreenFlashRequired =
            isFrontFacing && (flashMode == FlashMode.ON || flashMode == FlashMode.AUTO)

        if (isScreenFlashRequired) {
            imageCaptureUseCase.screenFlash = object : ScreenFlash {
                override fun apply(
                    expirationTimeMillis: Long,
                    listener: ImageCapture.ScreenFlashListener
                ) {
                    Log.d(TAG, "ImageCapture.ScreenFlash: apply")
                    coroutineScope.launch {
                        screenFlashEvents.emit(
                            CameraUseCase.ScreenFlashEvent(Type.APPLY_UI) {
                                listener.onCompleted()
                            }
                        )
                    }
                }

                override fun clear() {
                    Log.d(TAG, "ImageCapture.ScreenFlash: clear")
                    coroutineScope.launch {
                        screenFlashEvents.emit(
                            CameraUseCase.ScreenFlashEvent(Type.CLEAR_UI) {}
                        )
                    }
                }
            }
        }

        imageCaptureUseCase.flashMode = when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF // 2

            FlashMode.ON -> if (isScreenFlashRequired) {
                ImageCapture.FLASH_MODE_SCREEN // 3
            } else {
                ImageCapture.FLASH_MODE_ON // 1
            }

            FlashMode.AUTO -> if (isScreenFlashRequired) {
                ImageCapture.FLASH_MODE_SCREEN // 3
            } else {
                ImageCapture.FLASH_MODE_AUTO // 0
            }
        }
        Log.d(TAG, "Set flash mode to: ${imageCaptureUseCase.flashMode}")
    }

    override fun isScreenFlashEnabled() =
        imageCaptureUseCase.flashMode == ImageCapture.FLASH_MODE_SCREEN &&
            imageCaptureUseCase.screenFlash != null

    override suspend fun setAspectRatio(aspectRatio: AspectRatio) {
        currentSettings.update { old ->
            old?.copy(aspectRatio = aspectRatio)
        }
    }

    override suspend fun setCaptureMode(captureMode: CaptureMode) {
        currentSettings.update { old ->
            old?.copy(captureMode = captureMode)?.tryApplyImageFormatConstraints()
        }
    }

    private fun createUseCaseGroup(
        sessionSettings: PerpetualSessionSettings,
        initialTransientSettings: TransientSessionSettings,
        supportedStabilizationModes: Set<SupportedStabilizationMode>,
        effect: CameraEffect? = null
    ): UseCaseGroup {
        val previewUseCase = createPreviewUseCase(sessionSettings, supportedStabilizationModes)
        imageCaptureUseCase = createImageUseCase(sessionSettings)
        if (!disableVideoCapture) {
            videoCaptureUseCase = createVideoUseCase(sessionSettings, supportedStabilizationModes)
        }

        setFlashModeInternal(
            flashMode = initialTransientSettings.flashMode,
            isFrontFacing = sessionSettings.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        )

        return UseCaseGroup.Builder().apply {
            setViewPort(
                ViewPort.Builder(
                    sessionSettings.aspectRatio.ratio,
                    previewUseCase.targetRotation
                ).build()
            )
            addUseCase(previewUseCase)
            if (sessionSettings.dynamicRange == DynamicRange.SDR ||
                sessionSettings.imageFormat == ImageOutputFormat.JPEG_ULTRA_HDR
            ) {
                addUseCase(imageCaptureUseCase)
            }
            // Not to bind VideoCapture when Ultra HDR is enabled to keep the app design simple.
            if (videoCaptureUseCase != null &&
                sessionSettings.imageFormat == ImageOutputFormat.JPEG
            ) {
                addUseCase(videoCaptureUseCase!!)
            }

            effect?.let { addEffect(it) }

            captureMode = sessionSettings.captureMode
        }.build()
    }
    override suspend fun setDynamicRange(dynamicRange: DynamicRange) {
        currentSettings.update { old ->
            old?.copy(dynamicRange = dynamicRange)
        }
    }

    override suspend fun setImageFormat(imageFormat: ImageOutputFormat) {
        currentSettings.update { old ->
            old?.copy(imageFormat = imageFormat)
        }
    }

    @androidx.annotation.OptIn(ExperimentalImageCaptureOutputFormat::class)
    private fun getSupportedImageFormats(cameraInfo: CameraInfo): Set<ImageOutputFormat> {
        return ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
            .mapNotNull(Int::toAppImageFormat)
            .toSet()
    }

    @androidx.annotation.OptIn(ExperimentalImageCaptureOutputFormat::class)
    private fun createImageUseCase(sessionSettings: PerpetualSessionSettings): ImageCapture {
        val builder = ImageCapture.Builder()
        builder.setResolutionSelector(getResolutionSelector(sessionSettings.aspectRatio))
        if (sessionSettings.dynamicRange != DynamicRange.SDR &&
            sessionSettings.imageFormat == ImageOutputFormat.JPEG_ULTRA_HDR
        ) {
            builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        }
        return builder.build()
    }

    override suspend fun setLowLightBoost(lowLightBoost: LowLightBoost) {
        currentSettings.update { old ->
            old?.copy(lowLightBoost = lowLightBoost)
        }
    }

    override suspend fun setAudioMuted(isAudioMuted: Boolean) {
        // toggle mute for current in progress recording
        recording?.mute(!isAudioMuted)

        currentSettings.update { old ->
            old?.copy(audioMuted = isAudioMuted)
        }
    }

    private fun createVideoUseCase(
        sessionSettings: PerpetualSessionSettings,
        supportedStabilizationMode: Set<SupportedStabilizationMode>
    ): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setAspectRatio(getAspectRatioForUseCase(sessionSettings.aspectRatio))
            .setExecutor(defaultDispatcher.asExecutor()).build()
        return VideoCapture.Builder(recorder).apply {
            // set video stabilization
            if (shouldVideoBeStabilized(sessionSettings, supportedStabilizationMode)
            ) {
                setVideoStabilizationEnabled(true)
            }
            // set target fps
            if (sessionSettings.targetFrameRate != TARGET_FPS_AUTO) {
                setTargetFrameRate(
                    Range(sessionSettings.targetFrameRate, sessionSettings.targetFrameRate)
                )
            }

            setDynamicRange(sessionSettings.dynamicRange.toCXDynamicRange())
        }.build()
    }

    private fun getAspectRatioForUseCase(aspectRatio: AspectRatio): Int {
        return when (aspectRatio) {
            AspectRatio.THREE_FOUR -> RATIO_4_3
            AspectRatio.NINE_SIXTEEN -> RATIO_16_9
            else -> RATIO_4_3
        }
    }

    private fun shouldVideoBeStabilized(
        sessionSettings: PerpetualSessionSettings,
        supportedStabilizationModes: Set<SupportedStabilizationMode>
    ): Boolean {
        // video is on and target fps is not 60
        return (sessionSettings.targetFrameRate != TARGET_FPS_60) &&
            (supportedStabilizationModes.contains(SupportedStabilizationMode.HIGH_QUALITY)) &&
            // high quality (video only) selected
            (
                sessionSettings.stabilizeVideoMode == Stabilization.ON &&
                    sessionSettings.stabilizePreviewMode == Stabilization.UNDEFINED
                )
    }

    private fun createPreviewUseCase(
        sessionSettings: PerpetualSessionSettings,
        supportedStabilizationModes: Set<SupportedStabilizationMode>
    ): Preview {
        val previewUseCaseBuilder = Preview.Builder()
        // set preview stabilization
        if (shouldPreviewBeStabilized(sessionSettings, supportedStabilizationModes)) {
            previewUseCaseBuilder.setPreviewStabilizationEnabled(true)
        }

        previewUseCaseBuilder.setResolutionSelector(
            getResolutionSelector(sessionSettings.aspectRatio)
        )

        return previewUseCaseBuilder.build().apply {
            setSurfaceProvider { surfaceRequest ->
                _surfaceRequest.value = surfaceRequest
            }
        }
    }

    private fun getResolutionSelector(aspectRatio: AspectRatio): ResolutionSelector {
        val aspectRatioStrategy = when (aspectRatio) {
            AspectRatio.THREE_FOUR -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            AspectRatio.NINE_SIXTEEN -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
        return ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatioStrategy).build()
    }

    private fun shouldPreviewBeStabilized(
        sessionSettings: PerpetualSessionSettings,
        supportedStabilizationModes: Set<SupportedStabilizationMode>
    ): Boolean {
        // only supported if target fps is 30 or none
        return (
            when (sessionSettings.targetFrameRate) {
                TARGET_FPS_AUTO, TARGET_FPS_30 -> true
                else -> false
            }
            ) &&
            (
                supportedStabilizationModes.contains(SupportedStabilizationMode.ON) &&
                    sessionSettings.stabilizePreviewMode == Stabilization.ON
                )
    }

    companion object {
        private val FIXED_FRAME_RATES = setOf(TARGET_FPS_15, TARGET_FPS_30, TARGET_FPS_60)

        /**
         * Checks if preview stabilization is supported by the device.
         *
         */
        private fun isPreviewStabilizationSupported(cameraInfo: CameraInfo): Boolean {
            return Preview.getPreviewCapabilities(cameraInfo).isStabilizationSupported
        }

        /**
         * Checks if video stabilization is supported by the device.
         *
         */
        private fun isVideoStabilizationSupported(cameraInfo: CameraInfo): Boolean {
            return Recorder.getVideoCapabilities(cameraInfo).isStabilizationSupported
        }

        private fun getSupportedFrameRates(camInfo: CameraInfo): Set<Int> {
            return buildSet {
                camInfo.supportedFrameRateRanges.forEach { e ->
                    if (e.upper == e.lower && FIXED_FRAME_RATES.contains(e.upper)) {
                        add(e.upper)
                    }
                }
            }
        }
    }
}

private fun CXDynamicRange.toSupportedAppDynamicRange(): DynamicRange? {
    return when (this) {
        CXDynamicRange.SDR -> DynamicRange.SDR
        CXDynamicRange.HLG_10_BIT -> DynamicRange.HLG10
        // All other dynamic ranges unsupported. Return null.
        else -> null
    }
}

private fun DynamicRange.toCXDynamicRange(): CXDynamicRange {
    return when (this) {
        DynamicRange.SDR -> CXDynamicRange.SDR
        DynamicRange.HLG10 -> CXDynamicRange.HLG_10_BIT
    }
}

private fun LensFacing.toCameraSelector(): CameraSelector = when (this) {
    LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
}

private fun CameraSelector.toAppLensFacing(): LensFacing = when (this) {
    CameraSelector.DEFAULT_FRONT_CAMERA -> LensFacing.FRONT
    CameraSelector.DEFAULT_BACK_CAMERA -> LensFacing.BACK
    else -> throw IllegalArgumentException(
        "Unknown CameraSelector -> LensFacing mapping. [CameraSelector: $this]"
    )
}

@androidx.annotation.OptIn(ExperimentalImageCaptureOutputFormat::class)
private fun Int.toAppImageFormat(): ImageOutputFormat? {
    return when (this) {
        ImageCapture.OUTPUT_FORMAT_JPEG -> ImageOutputFormat.JPEG
        ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR -> ImageOutputFormat.JPEG_ULTRA_HDR
        // All other output formats unsupported. Return null.
        else -> null
    }
}
