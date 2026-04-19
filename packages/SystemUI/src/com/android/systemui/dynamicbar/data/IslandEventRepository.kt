package com.android.systemui.dynamicbar.data

import android.util.Log
import com.android.systemui.dynamicbar.data.source.AospChipIslandManager
import com.android.systemui.dynamicbar.data.source.AppTrackingIslandManager
import com.android.systemui.dynamicbar.data.source.BiometricIslandManager
import com.android.systemui.dynamicbar.data.source.ConnectivityIslandManager
import com.android.systemui.dynamicbar.data.source.MediaIslandManager
import com.android.systemui.dynamicbar.data.source.NotificationIslandManager
import com.android.systemui.dynamicbar.data.source.SystemIslandManager
import com.android.systemui.dynamicbar.data.source.TorchIslandManager
import com.android.systemui.dynamicbar.domain.DynamicBarSettings
import com.android.systemui.dynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SysUISingleton
class IslandEventRepository
@Inject
constructor(
    val media: MediaIslandManager,
    val connectivity: ConnectivityIslandManager,
    val system: SystemIslandManager,
    val notification: NotificationIslandManager,
    val appTracking: AppTrackingIslandManager,
    val torch: TorchIslandManager,
    val biometric: BiometricIslandManager,
    val aospChip: AospChipIslandManager,
    private val settings: DynamicBarSettings,
) {
    companion object {
        private const val TAG = "IslandEventRepository"
    }

    @Volatile private var listenersStarted = false

    private val _indicationEvents =
        MutableStateFlow<Map<String, IslandEvent.KeyguardIndication>>(emptyMap())

    fun updateIndicationEvent(event: IslandEvent.KeyguardIndication) {
        _indicationEvents.update { it + (event.indicationType.name to event) }
    }

    fun clearIndicationEvent(type: IslandEvent.KeyguardIndication.IndicationType) {
        _indicationEvents.update { it - type.name }
    }

    fun clearAllIndicationEvents() {
        _indicationEvents.value = emptyMap()
    }

    val events: Flow<List<IslandEvent>> = buildEventsFlow()

    private val disabled get() = settings.disabledEventTypes.value

    private fun isTypeEnabled(typeId: String): Boolean = typeId !in disabled

    fun startListening() {
        if (listenersStarted) return
        listenersStarted = true
        Log.d(TAG, "Starting event listeners")
        syncDisabledTypes()
        if (isTypeEnabled("media")) media.startListening()
        if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
        if (isTypeEnabled("hotspot")) connectivity.startHotspot()
        if (isTypeEnabled("vpn")) connectivity.startVpn()
        if (isTypeEnabled("charging")) system.startCharging()
        if (isTypeEnabled("ringer")) system.startRinger()
        if (isTypeEnabled("clipboard")) system.startClipboard()
        notification.startListening()
        if (isTypeEnabled("app_switch")) appTracking.startListening()
        if (isTypeEnabled("torch")) torch.startListening()
        if (isTypeEnabled("biometric_unlock")) biometric.startListening()
    }

    fun stopListening() {
        if (!listenersStarted) return
        listenersStarted = false
        Log.d(TAG, "Stopping event listeners")
        media.stopListening()
        connectivity.stopListening()
        system.stopListening()
        notification.stopListening()
        torch.stopListening()
        appTracking.stopListening()
        biometric.stopListening()
    }

    fun refreshListeners() {
        if (!listenersStarted) return
        syncDisabledTypes()

        if (isTypeEnabled("media")) media.startListening()
        else media.stopListening()

        if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
        else connectivity.stopBluetooth()
        if (isTypeEnabled("hotspot")) connectivity.startHotspot()
        else connectivity.stopHotspot()
        if (isTypeEnabled("vpn")) connectivity.startVpn()
        else connectivity.stopVpn()

        if (isTypeEnabled("charging")) system.startCharging()
        else system.stopCharging()
        if (isTypeEnabled("ringer")) system.startRinger()
        else system.stopRinger()
        if (isTypeEnabled("clipboard")) system.startClipboard()
        else system.stopClipboard()

        if (isTypeEnabled("app_switch")) appTracking.startListening()
        else appTracking.stopListening()
        if (isTypeEnabled("torch")) torch.startListening()
        else torch.stopListening()
        if (isTypeEnabled("biometric_unlock")) biometric.startListening()
        else biometric.stopListening()
    }

    private fun syncDisabledTypes() {
        notification.disabledTypes = disabled
    }

    private fun buildEventsFlow(): Flow<List<IslandEvent>> {
        val sportsGroup = notification.sportsEvents.map { notifSports ->
            if (!isTypeEnabled("sports")) emptyList()
            else notifSports
        }
        val promotedGroup = combine(
            notification.promotedOngoingEvents,
            sportsGroup,
        ) { promoted, sports ->
            (if (isTypeEnabled("promoted_ongoing")) promoted else emptyList()) + sports
        }
        val highGroup =
            combine(torch.torchEvent, biometric.biometricEvent) { t, bio ->
                listOfNotNull(
                    t?.takeIf { isTypeEnabled("torch") },
                    bio?.takeIf { isTypeEnabled("biometric_unlock") },
                )
            }
        val midGroup =
            combine(
                media.mediaEvent,
                connectivity.bluetoothEvent,
                connectivity.hotspotEvent,
                system.chargingEvent,
                notification.alarmEvent,
            ) { m, bt, hotspot, charging, alarm ->
                listOfNotNull(
                    m?.takeIf { isTypeEnabled("media") },
                    bt?.takeIf { isTypeEnabled("bluetooth") },
                    hotspot?.takeIf { isTypeEnabled("hotspot") },
                    charging?.takeIf { isTypeEnabled("charging") },
                    alarm?.takeIf { isTypeEnabled("alarm") },
                )
            }
        val lowGroupA =
            combine(
                notification.timerEvent,
                notification.stopwatchEvent,
                system.ringerEvent,
                connectivity.vpnEvent,
                system.clipboardEvent,
            ) { timer, stopwatch, ringer, vpn, clipboard ->
                listOfNotNull(
                    timer?.takeIf { isTypeEnabled("timer") },
                    stopwatch?.takeIf { isTypeEnabled("stopwatch") },
                    ringer?.takeIf { isTypeEnabled("ringer") },
                    vpn?.takeIf { isTypeEnabled("vpn") },
                    clipboard?.takeIf { isTypeEnabled("clipboard") },
                )
            }
        val lowGroup =
            combine(
                lowGroupA,
                appTracking.appSwitchEvent,
                notification.audioRecordingEvent,
                notification.nowPlayingEvent,
            ) { a, appSwitch, audioRec, nowPlaying ->
                a + listOfNotNull(
                    appSwitch?.takeIf { isTypeEnabled("app_switch") },
                    audioRec?.takeIf { isTypeEnabled("audio_recording") },
                    nowPlaying?.takeIf { isTypeEnabled("media") },
                )
            }
        val transientGroup = combine(midGroup, lowGroup) { mid, low -> mid + low }

        val indicationGroup = _indicationEvents.map { it.values.toList() }

        val allEvents = combine(
            highGroup,
            transientGroup,
            promotedGroup,
            indicationGroup,
            aospChip.aospChipEvents,
        ) { high, transient, promoted, indication, aosp ->
            high + transient + promoted + indication + aosp
        }

        return allEvents.map { events ->
            events.sorted()
        }.distinctUntilChanged { old, new ->
            old.size == new.size && old.indices.all { i ->
                old[i].withoutDrawables() == new[i].withoutDrawables()
            }
        }
    }
}
