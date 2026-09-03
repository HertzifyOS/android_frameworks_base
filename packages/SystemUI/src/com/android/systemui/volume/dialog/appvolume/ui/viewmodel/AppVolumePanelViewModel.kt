/*
 * Copyright (C) 2026 HertzifyOS
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

package com.android.systemui.volume.dialog.appvolume.ui.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AppVolumeSliderState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppVolumeItem(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val sliderState: AppVolumeSliderState,
)

@SysUISingleton
class AppVolumePanelViewModel
@Inject
constructor(
    @Application private val context: Context,
    private val audioManager: AudioManager,
    private val packageManager: PackageManager,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {

    private val _appVolumes = MutableStateFlow<List<AppVolumeItem>>(emptyList())
    val appVolumes: StateFlow<List<AppVolumeItem>> = _appVolumes.asStateFlow()

    fun fetchActiveAppVolumes() {
        val mediaIconLoaded = context.getDrawable(R.drawable.ic_volume_media)?.asIcon()

        val items =
            audioManager.listAppVolumes()
                .filter { it.isActive }
                .map { appVolume ->
                    val appInfo =
                        try {
                            packageManager.getApplicationInfo(appVolume.packageName, 0)
                        } catch (e: PackageManager.NameNotFoundException) {
                            null
                        }

                    val appName =
                        appInfo?.let { packageManager.getApplicationLabel(it).toString() }
                            ?: appVolume.packageName
                    val appIcon = appInfo?.let { packageManager.getApplicationIcon(it) }

                    AppVolumeItem(
                        packageName = appVolume.packageName,
                        appName = appName,
                        appIcon = appIcon,
                        sliderState =
                            AppVolumeSliderState(
                                value = appVolume.volume,
                                label = appName,
                                icon = mediaIconLoaded,
                                a11yContentDescription = appName,
                                a11yStateDescription = "${(appVolume.volume * 100).toInt()}%",
                                isMuted = appVolume.isMuted,
                            ),
                    )
                }
        _appVolumes.value = items
    }

    fun setAppVolume(packageName: String, volume: Float) {
        audioManager.setAppVolume(packageName, volume)
        fetchActiveAppVolumes()
    }

    fun toggleMute(packageName: String, currentMute: Boolean) {
        audioManager.setAppMute(packageName, !currentMute)
        fetchActiveAppVolumes()
    }
}