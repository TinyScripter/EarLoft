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

/**
 * A route (IPv4 or IPv6) of the VPN the app's traffic goes through.
 *
 * @property captures `false` for a route the VPN leaves out of the tunnel (`RTN_THROW`, e.g. "exclude private IPs").
 */
internal data class VpnRoute(
    val destination: InetAddress,
    val prefixLength: Int,
    val captures: Boolean,
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

    /**
     * Whether the VPN with [routes] takes the traffic to the [receiver] into its tunnel: then the phone's answers to the receiver
     * don't go out over Wi-Fi, and the receiver can't load anything from the phone (seen with WireGuard routing the home network,
     * review2 9.8). The most specific route of the receiver's address family decides, as in the kernel; IPv6 too (review3 Q27).
     * No matching route means the traffic bypasses the VPN.
     */
    fun vpnCapturesReceiver(routes: List<VpnRoute>, receiver: InetAddress?): Boolean {
        if (receiver == null) return false
        return routes.filter { inSubnet(it.destination, it.prefixLength, receiver) }.maxByOrNull { it.prefixLength }?.captures == true
    }

    private fun LocalAddress.contains(other: InetAddress) = inSubnet(address, prefixLength, other)

    /** Whether [other] lies in [network]/[prefixLength]; never across address families. */
    private fun inSubnet(network: InetAddress, prefixLength: Int, other: InetAddress): Boolean {
        val a = network.address
        val b = other.address
        if (a.size != b.size) return false
        val prefix = prefixLength.coerceIn(0, a.size * 8)
        val fullBytes = prefix / 8
        for (i in 0 until fullBytes) if (a[i] != b[i]) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
    }
}
