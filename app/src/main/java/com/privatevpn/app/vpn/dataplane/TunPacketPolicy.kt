package com.privatevpn.app.vpn.dataplane

/** Admission is checked before a raw IP packet reaches the proxy data plane. */
fun interface TunPacketPolicy {
    fun allowsPacket(packet: ByteArray, length: Int): Boolean
}
