package de.kleinabersmart.earloft.cast

import java.net.Inet4Address
import java.net.InetAddress

/**
 * One IPv4 address of a network the phone is connected to.
 *
 * @property local The network runs over Wi-Fi or Ethernet (the receiver can't reach the phone over mobile data).
 * @property vpn The network is a VPN; its address is the tunnel's, which the receiver in the LAN can't reach (review J12).
 * @property default The network is the system's default network.
 */
internal data class LocalAddress(
    val address: Inet4Address,
    val prefixLength: Int,
    val local: Boolean,
    val vpn: Boolean,
    val default: Boolean,
)

/** Which of the phone's addresses the cast server announces to the receiver (review J12, decision Z7 B). */
internal object CastLanAddress {

    /**
     * The address in the receiver's subnet; if none contains the [receiver] (unknown, or reached through a router), the one of
     * the default network, otherwise the first one. VPN and mobile networks, loopback and link-local addresses never count.
     */
    fun pick(candidates: List<LocalAddress>, receiver: InetAddress?): Inet4Address? {
        val usable = candidates.filter {
            it.local && !it.vpn && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        }
        if (receiver is Inet4Address) {
            usable.firstOrNull { it.contains(receiver) }?.let { return it.address }
        }
        return (usable.firstOrNull { it.default } ?: usable.firstOrNull())?.address
    }

    private fun LocalAddress.contains(other: Inet4Address): Boolean {
        val prefix = prefixLength.coerceIn(0, 32)
        if (prefix == 0) return true
        val mask = -1 shl (32 - prefix)
        return address.toInt() and mask == other.toInt() and mask
    }

    private fun Inet4Address.toInt(): Int = address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
}
