package com.android.systemui.dynamicbar.ui

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class DynamicBarManager
@Inject
constructor(
    private val viewModel: DynamicBarChipViewModel,
    private val expandedPanel: DynamicBarExpandedPanel,
) : CoreStartable {

    override fun start() {
        viewModel.interactor.init()
        expandedPanel.init()
    }
}

