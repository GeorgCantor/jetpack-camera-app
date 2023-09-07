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

package com.google.jetpackcamera.feature.quicksettings.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.jetpackcamera.feature.quicksettings.CameraAspectRatio
import com.google.jetpackcamera.feature.quicksettings.CameraFlashMode
import com.google.jetpackcamera.feature.quicksettings.CameraLensFace
import com.google.jetpackcamera.feature.quicksettings.QuickSettingsEnum
import com.google.jetpackcamera.quicksettings.R
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.FlashModeStatus
import kotlin.math.min


// completed components ready to go into preview screen

//TODO: Implement Set Ratio
@Composable
fun ExpandedQuickSetRatio(
    setRatio: (aspectRatio: AspectRatio) -> Unit,
    currentRatio: AspectRatio
) {
    val buttons: Array<@Composable () -> Unit> = arrayOf(
        {
            QuickSetRatio(
                onClick = { setRatio(AspectRatio.THREE_FOUR) },
                ratio = AspectRatio.THREE_FOUR,
                currentRatio = currentRatio,
                isHighlightEnabled = true
            )
        },
        {
            QuickSetRatio(
                onClick = { setRatio(AspectRatio.NINE_SIXTEEN) },
                ratio = AspectRatio.NINE_SIXTEEN,
                currentRatio = currentRatio,
                isHighlightEnabled = true
            )
        },
        {
            QuickSetRatio(
                onClick = { setRatio(AspectRatio.ONE_ONE) },
                ratio = AspectRatio.ONE_ONE,
                currentRatio = currentRatio,
                isHighlightEnabled = true
            )
        }
    )
    ExpandedQuickSetting(quickSettingButtons = buttons)
}

//TODO: Implement Set Ratio
@Composable
fun QuickSetRatio(
    onClick: () -> Unit,
    ratio: AspectRatio,
    currentRatio: AspectRatio,
    isHighlightEnabled: Boolean = false
) {
    val enum = when (ratio) {
        AspectRatio.THREE_FOUR -> CameraAspectRatio.THREE_FOUR
        AspectRatio.NINE_SIXTEEN -> CameraAspectRatio.NINE_SIXTEEN
        AspectRatio.ONE_ONE -> CameraAspectRatio.ONE_ONE
        else -> CameraAspectRatio.ONE_ONE
    }
    QuickSettingUiItem(
        enum = enum,
        onClick = { onClick() },
        isHighLighted = isHighlightEnabled && (ratio == currentRatio)
    )
}

@Composable
fun QuickSetFlash(onClick: (FlashModeStatus) -> Unit, currentFlashMode: FlashModeStatus) {
    val enum = when (currentFlashMode) {
        FlashModeStatus.OFF -> CameraFlashMode.OFF
        FlashModeStatus.AUTO -> CameraFlashMode.AUTO
        FlashModeStatus.ON -> CameraFlashMode.ON
    }
    QuickSettingUiItem(
        enum = enum,
        isHighLighted = currentFlashMode == FlashModeStatus.ON,
        onClick =
        {
            when (currentFlashMode) {
                FlashModeStatus.OFF -> onClick(FlashModeStatus.ON)
                FlashModeStatus.ON -> onClick(FlashModeStatus.AUTO)
                FlashModeStatus.AUTO -> onClick(FlashModeStatus.OFF)
            }
        }
    )
}

@Composable
fun QuickFlipCamera(flipCamera: (Boolean) -> Unit, currentFacingFront: Boolean) {
    val enum = when (currentFacingFront) {
        true -> CameraLensFace.FRONT
        false -> CameraLensFace.BACK
    }
    QuickSettingUiItem(
        enum = enum,
        onClick = { flipCamera(!currentFacingFront) }
    )
}

@Composable
fun DropDownIcon(toggleDropDown: () -> Unit, isOpen: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        //dropdown icon
        Icon(
            painter = painterResource(R.drawable.baseline_expand_more_72),
            contentDescription = stringResource(R.string.quick_settings_dropdown_description),
            tint = if (isOpen) Color.White else LocalContentColor.current,
            modifier = Modifier
                .size(72.dp)
                .clickable {
                    toggleDropDown()
                }
                .scale(1f, if (isOpen) -1f else 1f)
        )
    }
}

// subcomponents used to build completed components

@Composable
fun QuickSettingUiItem(
    enum: QuickSettingsEnum,
    onClick: () -> Unit,
    isHighLighted: Boolean = false
) {
    QuickSettingUiItem(
        drawableResId = enum.getDrawableResId(),
        text = stringResource(id = enum.getTextResId()),
        accessibilityText = stringResource(id = enum.getDescriptionResId()),
        onClick = { onClick() },
        isHighLighted = isHighLighted,
    )
}

/**
 * The itemized UI component representing each button in quick settings.
 */
@Composable
fun QuickSettingUiItem(
    @DrawableRes drawableResId: Int,
    text: String,
    accessibilityText: String,
    onClick: () -> Unit,
    isHighLighted: Boolean = false
) {
    Column(
        modifier = Modifier
            .wrapContentSize()
            .padding(dimensionResource(id = R.dimen.quick_settings_ui_item_padding))
            .clickable {
                onClick()
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (isHighLighted) Color.Yellow else Color.White
        Icon(
            painter = painterResource(drawableResId),
            contentDescription = accessibilityText,
            tint = tint,
            modifier = Modifier
                .size(dimensionResource(id = R.dimen.quick_settings_ui_item_icon_size))
        )

        Text(text = text, color = tint)
    }
}

/**
 * Should you want to have an expanded view of a single quick setting
 */
@Composable
fun ExpandedQuickSetting(vararg quickSettingButtons: @Composable () -> Unit) {
    val expandedNumOfColumns =
        min(
            quickSettingButtons.size,
            ((LocalConfiguration.current.screenWidthDp.dp - (dimensionResource(
                id = R.dimen.quick_settings_ui_horizontal_padding
            ) * 2)) /
                    (dimensionResource(id = R.dimen.quick_settings_ui_item_icon_size) +
                            (dimensionResource(id = R.dimen.quick_settings_ui_item_padding) * 2))).toInt()
        )
    LazyVerticalGrid(
        modifier = Modifier.fillMaxWidth(),
        columns = GridCells.Fixed(count = expandedNumOfColumns)
    ) {
        items(quickSettingButtons.size) { i ->
            quickSettingButtons[i]()
        }
    }
}

/**
 * Algorithm to determine dimensions of QuickSettings Icon layout
 */
@Composable
fun QuickSettingsGrid(vararg quickSettingsButtons: @Composable () -> Unit) {
    val initialNumOfColumns =
        min(
            quickSettingsButtons.size,
            ((LocalConfiguration.current.screenWidthDp.dp - (dimensionResource(
                id = R.dimen.quick_settings_ui_horizontal_padding
            ) * 2)) /
                    (dimensionResource(id = R.dimen.quick_settings_ui_item_icon_size) +
                            (dimensionResource(id = R.dimen.quick_settings_ui_item_padding) * 2))).toInt()
        )

    LazyVerticalGrid(
        modifier = Modifier.fillMaxWidth(),
        columns = GridCells.Fixed(count = initialNumOfColumns)
    ) {
        items(quickSettingsButtons.size) { i ->
            quickSettingsButtons[i]()
        }
    }
}