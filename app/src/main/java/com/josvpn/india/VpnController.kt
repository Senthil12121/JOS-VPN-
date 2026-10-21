package com.josvpn.india

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

/** The single WireGuard tunnel used by this app. */
class AppTunnel : Tunnel {
    @Volatile
    var listener: ((Tunnel.State) -> Unit)? = null

    override fun getName(): String = "josvpn"

    override fun onStateChange(newState: Tunnel.State) {
        listener?.invoke(newState)
    }
}

/** Holds the backend and tunnel so they survive activity recreation. */
object VpnController {
    val tunnel = AppTunnel()

    @Volatile
    private var instance: GoBackend? = null

    fun backendFor(context: Context): GoBackend {
        return instance ?: synchronized(this) {
            instance ?: GoBackend(context.applicationContext).also { instance = it }
        }
    }
}
