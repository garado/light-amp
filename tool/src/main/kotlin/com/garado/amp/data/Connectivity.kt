package com.garado.amp.data

import com.thelightphone.sdk.NetworkStatus
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the connection costs, from the SDK's ConnectivityManager hooks
 * ([com.thelightphone.sdk.LightConnectivity], upstream #163/#166), which
 * reports the active network rather than which interfaces merely hold an
 * address.
 *
 * Gates check metered-ness, not "is it Wi-Fi": a phone hotspot is Wi-Fi and
 * metered, so it is treated as the cellular connection it rides on.
 */
object Connectivity {

    /** Metered until told otherwise, so the gap before [bind] errs cheap. */
    private val status = MutableStateFlow(
        NetworkStatus(isConnected = false, isWifi = false, isMetered = true),
    )

    /** Wired once at boot; see App.boot. */
    fun bind(context: SealedLightContext, scope: CoroutineScope) {
        status.value = runCatching { context.connectivity.currentStatus }
            .getOrDefault(status.value)
        scope.launch {
            context.connectivity.observeNetworkStatus().collect { status.value = it }
        }
    }

    val network: StateFlow<NetworkStatus> = status

    /**
     * Whether there is a local network to speak to at all. Distinct from
     * [isUnmetered]: a phone hotspot is Wi-Fi and metered, so it is both.
     */
    fun isOnWifi(): Boolean = status.value.isConnected && status.value.isWifi

    /** Bytes are free here: Wi-Fi, or any other unmetered connection. */
    fun isUnmetered(): Boolean =
        status.value.isConnected && !status.value.isMetered

    val unmetered: Flow<Boolean> =
        status.map { it.isConnected && !it.isMetered }.distinctUntilChanged()

    /**
     * Emits when the connection changes: comes back after an outage, or its
     * cost changes. Both are tracked because neither alone covers both cases
     * (cellular to Wi-Fi never stops being "connected"; losing the connection
     * entirely doesn't change its metered-ness).
     */
    val changed: Flow<Pair<Boolean, Boolean>> =
        status.map { it.isConnected to it.isMetered }.distinctUntilChanged()
}
