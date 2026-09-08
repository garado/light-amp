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
 * What the connection costs, from the SDK's ConnectivityManager hooks.
 *
 * This replaces an interface-list heuristic — "a wlan interface with an
 * address means Wi-Fi" — that could be lied to: Android moves traffic to
 * cellular when a Wi-Fi link stops validating, and the interface keeps its
 * address while the bytes ride LTE. That is how a heavy data day happens with
 * every setting right. The SDK's [com.thelightphone.sdk.LightConnectivity]
 * (upstream #163/#166) reads the *active* network, so the answer here is about
 * the route the bytes will actually take — and the callback makes changes land
 * at once rather than on a five-second poll.
 *
 * Gates ask about *metered-ness*, not "is it Wi-Fi": a phone-hotspot Wi-Fi is
 * metered and is treated as the cellular it rides on. The settings keep saying
 * "Wi-Fi", because that is the phone in most hands.
 */
object Connectivity {

    /** Metered until told otherwise, so the gap before [bind] errs cheap. */
    private val status = MutableStateFlow(
        NetworkStatus(isConnected = false, isWifi = false, isMetered = true),
    )

    /** Wired once at boot — see App.boot. */
    fun bind(context: SealedLightContext, scope: CoroutineScope) {
        status.value = runCatching { context.connectivity.currentStatus }
            .getOrDefault(status.value)
        scope.launch {
            context.connectivity.observeNetworkStatus().collect { status.value = it }
        }
    }

    val network: StateFlow<NetworkStatus> = status

    /**
     * Whether there is a local network to speak to at all.
     *
     * Distinct from [isUnmetered], and the two must not be confused: a phone
     * hotspot is Wi-Fi and metered, so it costs money *and* has neighbours.
     * This is the question a LAN broadcast asks — is anyone there to hear it —
     * where the gates ask what the bytes cost.
     */
    fun isOnWifi(): Boolean = status.value.isConnected && status.value.isWifi

    /** Bytes are free here: Wi-Fi, or any other unmetered connection. */
    fun isUnmetered(): Boolean =
        status.value.isConnected && !status.value.isMetered

    val unmetered: Flow<Boolean> =
        status.map { it.isConnected && !it.isMetered }.distinctUntilChanged()

    /**
     * Emits whenever the connection becomes something different — coming back
     * after an outage, or changing what it costs.
     *
     * Both matter, and neither alone is enough. Going from cellular to Wi-Fi
     * never stops being *connected*, so watching connectivity alone would miss
     * the moment Wi-Fi Only starts allowing things again; watching the price
     * alone would miss a plane landing with the mode set to Make it Hurt.
     */
    val changed: Flow<Pair<Boolean, Boolean>> =
        status.map { it.isConnected to it.isMetered }.distinctUntilChanged()
}
