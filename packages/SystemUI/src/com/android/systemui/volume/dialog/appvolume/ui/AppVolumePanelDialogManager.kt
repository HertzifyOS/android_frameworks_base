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

package com.android.systemui.volume.dialog.appvolume.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.unit.dp
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.createBottomSheet
import com.android.systemui.volume.dialog.appvolume.ui.composable.AppVolumePanelScreen
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.AppVolumePanelViewModel
import javax.inject.Inject

@SysUISingleton
class AppVolumePanelDialogManager
@Inject
constructor(
    @Application private val context: Context,
    private val dialogFactory: SystemUIDialogFactory,
    private val viewModel: AppVolumePanelViewModel,
) {

    private var dialog: ComponentSystemUIDialog? = null

    fun create(aboveStatusBar: Boolean) {
        if (dialog?.isShowing == true) {
            return
        }

        viewModel.fetchActiveAppVolumes()

        dialog =
            dialogFactory
                .createBottomSheet(
                    context = context,
                    isDraggable = true,
                    maxWidth = 640.dp,
                    content = { sheetDialog ->
                        AppVolumePanelScreen(
                            viewModel = viewModel,
                            onDismiss = { sheetDialog.dismiss() },
                            onSettingsClick = { openSoundSettings(sheetDialog) },
                        )
                    },
                )
                .apply { show() }
    }

    private fun openSoundSettings(dialog: SystemUIDialog) {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        dialog.dismiss()
    }
}