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
package com.google.jetpackcamera.ui

import android.Manifest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.jetpackcamera.BuildConfig
import com.google.jetpackcamera.feature.preview.PreviewMode
import com.google.jetpackcamera.feature.preview.PreviewScreen
import com.google.jetpackcamera.feature.preview.PreviewViewModel
import com.google.jetpackcamera.settings.SettingsScreen
import com.google.jetpackcamera.settings.VersionInfoHolder
import com.google.jetpackcamera.ui.Routes.PREVIEW_ROUTE
import com.google.jetpackcamera.ui.Routes.SETTINGS_ROUTE

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun JcaApp(
    openAppSettings: () -> Unit,
    /*TODO(b/306236646): remove after still capture*/
    previewMode: PreviewMode,
    onPreviewViewModel: (PreviewViewModel) -> Unit,
    onRequestWindowColorMode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val permissionStates = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    // should show rationale means a setting has been viewed and denied
    if (cameraPermissionState.status.isGranted && (
            permissionStates.allPermissionsGranted ||
                permissionStates.shouldShowRationale
            )
    ) {
        JetpackCameraNavHost(
            onPreviewViewModel = onPreviewViewModel,
            previewMode = previewMode,
            onRequestWindowColorMode = onRequestWindowColorMode,
            modifier = modifier
        )
    } else {
        // you'll have the option to go through camera and all other optional permissions
        PermissionsScreen(
            modifier = modifier.fillMaxSize(),
            permissionEnums = getUnGrantedPermissions(permissionStates),
            openAppSettings = openAppSettings
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun getUnGrantedPermissions(
    permissionStates: MultiplePermissionsState
): MutableSet<PermissionEnum> {
    val unGrantedPermissions = mutableSetOf<PermissionEnum>()
    for (permission in permissionStates.permissions) {
        // camera is always required
        if (!permission.status.isGranted && permission.permission == Manifest.permission.CAMERA) {
            unGrantedPermissions.add(PermissionEnum.CAMERA)
        }
        // audio is optional
        else if (!permission.status.shouldShowRationale && permission.permission ==
            Manifest.permission.RECORD_AUDIO
        ) {
            unGrantedPermissions.add(PermissionEnum.RECORD_AUDIO)
        }
    }
    return unGrantedPermissions
}

@Composable
private fun JetpackCameraNavHost(
    previewMode: PreviewMode,
    onPreviewViewModel: (PreviewViewModel) -> Unit,
    onRequestWindowColorMode: (Int) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = PREVIEW_ROUTE, modifier = modifier) {
        composable(PREVIEW_ROUTE) {
            PreviewScreen(
                onPreviewViewModel = onPreviewViewModel,
                onNavigateToSettings = { navController.navigate(SETTINGS_ROUTE) },
                onRequestWindowColorMode = onRequestWindowColorMode,
                previewMode = previewMode
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(
                versionInfo = VersionInfoHolder(
                    versionName = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE
                ),
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
