package com.android.systemui.dynamicbar.ui

import com.android.systemui.animation.Expandable
import com.android.systemui.dynamicbar.domain.DynamicBarInteractor
import com.android.systemui.dynamicbar.model.IslandEvent
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DynamicBarChipState(
    val event: IslandEvent,
    val eventCount: Int,
    val pinnedIndex: Int,
    val allEvents: List<IslandEvent>,
)

data class KeyguardBatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSave: Boolean,
    val isWireless: Boolean,
    val timeRemaining: String?,
)

@SysUISingleton
class DynamicBarChipViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    val interactor: DynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    authController: AuthController,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    val keyguardExpansion: DynamicBarKeyguardExpansion,
    val statusBarExpansion: DynamicBarStatusBarExpansion,
) {

    val isLowUdfps: StateFlow<Boolean> =
        udfpsOverlayInteractor.udfpsOverlayParams
            .map { params ->
                if (!authController.isUdfpsSupported) return@map false
                val sensorBottom = params.sensorBounds.bottom
                val displayHeight = params.naturalDisplayHeight
                if (displayHeight <= 0) return@map false
                sensorBottom > displayHeight * LOW_UDFPS_THRESHOLD
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val chipState: StateFlow<DynamicBarChipState?> =
        interactor.uiState
            .map { uiState ->
                if (!uiState.shouldShow) return@map null
                val topEvent = uiState.topEvent ?: return@map null
                DynamicBarChipState(
                    event = topEvent,
                    eventCount = uiState.activeEvents.size,
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                )
            }
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled

    val keyguardBatteryInfo: StateFlow<KeyguardBatteryInfo> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            KeyguardBatteryInfo(
                level = level ?: 0,
                isCharging = charging,
                isPowerSave = powerSave,
                isWireless = batteryController.isPluggedInWireless,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope,
            SharingStarted.Lazily,
            KeyguardBatteryInfo(0, false, false, false, null),
        )

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipCenterXFraction = MutableStateFlow(0.5f)
    val chipCenterXFraction: StateFlow<Float> = _chipCenterXFraction.asStateFlow()

    fun updateChipCenterX(fraction: Float) {
        _chipCenterXFraction.value = fraction
    }

    val isExpanded: StateFlow<Boolean> = statusBarExpansion.isExpanded

    val isKeyguardExpanded: StateFlow<Boolean> = keyguardExpansion.isExpanded

    fun cycleNext() = interactor.cycleNext()

    fun cyclePrev() = interactor.cyclePrev()

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    fun handleAospChipTap(event: IslandEvent.AospChip, expandable: Expandable): Boolean {
        val active = event.active
        return when (val behavior = active.clickBehavior) {
            is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.HideHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.ExpandAction -> {
                behavior.onClick(expandable)
                true
            }
            is OngoingActivityChipModel.ClickBehavior.None -> false
        }
    }

    companion object {
        private const val LOW_UDFPS_THRESHOLD = 0.93f
    }
}

