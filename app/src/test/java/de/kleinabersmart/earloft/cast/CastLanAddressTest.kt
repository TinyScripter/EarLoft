package de.kleinabersmart.earloft.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class CastLanAddressTest {

    private val wifi = address("192.168.0.42", 24, default = false)
    private val tunnel = address("100.64.12.7", 10, vpn = true, default = true)

    @Test
    fun `with a VPN the Wi-Fi address is announced, not the tunnel`() {
        // On Android the VPN network is the default network and often reports Wi-Fi as its transport, too.
        val picked = CastLanAddress.pick(listOf(tunnel.copy(local = true), wifi), ip("192.168.0.66"))

        assertEquals("192.168.0.42", picked?.hostAddress)
    }

    @Test
    fun `with a VPN and an unknown receiver the Wi-Fi address is still announced`() {
        assertEquals("192.168.0.42", CastLanAddress.pick(listOf(tunnel.copy(local = true), wifi), null)?.hostAddress)
    }

    @Test
    fun `the address in the receiver's subnet wins over the default network`() {
        val ethernet = address("10.0.0.5", 16, default = true)

        val picked = CastLanAddress.pick(listOf(ethernet, wifi), ip("192.168.0.66"))

        assertEquals("192.168.0.42", picked?.hostAddress)
    }

    @Test
    fun `a receiver outside every subnet gets the default network's address`() {
        val ethernet = address("10.0.0.5", 16, default = true)

        assertEquals("10.0.0.5", CastLanAddress.pick(listOf(wifi, ethernet), ip("172.16.1.9"))?.hostAddress)
    }

    @Test
    fun `the prefix length decides the subnet`() {
        val narrow = address("192.168.0.42", 25, default = true)
        val other = address("192.168.0.200", 25, default = false)

        assertEquals("192.168.0.200", CastLanAddress.pick(listOf(narrow, other), ip("192.168.0.130"))?.hostAddress)
    }

    @Test
    fun `mobile, loopback and link-local addresses never count`() {
        val mobile = address("10.20.30.40", 8, local = false, default = true)
        val loopback = address("127.0.0.1", 8, default = false)
        val linkLocal = address("169.254.3.4", 16, default = false)

        assertNull(CastLanAddress.pick(listOf(mobile, loopback, linkLocal, tunnel), ip("10.20.30.1")))
    }

    @Test
    fun `an IPv6 receiver falls back to the default network`() {
        val picked = CastLanAddress.pick(listOf(wifi, address("192.168.1.3", 24, default = true)), InetAddress.getByName("fe80::1"))

        assertEquals("192.168.1.3", picked?.hostAddress)
    }

    @Test
    fun `a VPN routing the home network captures the receiver`() {
        // WireGuard on the Pixel (review2 9.8): the home network and everything else go into the tunnel.
        val routes = listOf(route("192.168.0.0", 24), route("0.0.0.0", 0))

        assertTrue(CastLanAddress.vpnCapturesReceiver(routes, ip("192.168.0.39")))
    }

    @Test
    fun `a full-tunnel VPN captures the receiver through its default route`() {
        assertTrue(CastLanAddress.vpnCapturesReceiver(listOf(route("0.0.0.0", 0)), ip("192.168.0.39")))
    }

    @Test
    fun `an excluded local network leaves the receiver outside the tunnel`() {
        val routes = listOf(route("0.0.0.0", 0), route("192.168.0.0", 16, captures = false))

        assertFalse(CastLanAddress.vpnCapturesReceiver(routes, ip("192.168.0.39")))
    }

    @Test
    fun `a split tunnel to other networks leaves the receiver alone`() {
        assertFalse(CastLanAddress.vpnCapturesReceiver(listOf(route("10.8.0.0", 24)), ip("192.168.0.39")))
    }

    @Test
    fun `the most specific route decides`() {
        val routes = listOf(route("192.168.0.0", 16, captures = false), route("192.168.0.32", 28))

        assertTrue(CastLanAddress.vpnCapturesReceiver(routes, ip("192.168.0.39")))
        assertFalse(CastLanAddress.vpnCapturesReceiver(routes, ip("192.168.0.66")))
    }

    @Test
    fun `an unknown or IPv6 receiver is never reported`() {
        val routes = listOf(route("0.0.0.0", 0))

        assertFalse(CastLanAddress.vpnCapturesReceiver(routes, null))
        assertFalse(CastLanAddress.vpnCapturesReceiver(routes, InetAddress.getByName("fe80::1")))
    }

    private fun route(network: String, prefix: Int, captures: Boolean = true) = VpnRoute(ip(network), prefix, captures)

    private fun address(ip: String, prefix: Int, local: Boolean = true, vpn: Boolean = false, default: Boolean) =
        LocalAddress(ip(ip), prefix, local, vpn, default)

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
