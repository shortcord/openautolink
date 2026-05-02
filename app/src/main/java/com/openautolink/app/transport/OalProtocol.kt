package com.openautolink.app.transport

/**
 * Wire-protocol constants shared between the car app and the companion app.
 *
 * Mirror of values in `companion/.../service/TcpAdvertiser.kt`. Keep these
 * two files in sync — drift between them silently breaks discovery.
 */
object OalProtocol {
    /** Primary AA TCP port: companion's TcpAdvertiser listens here for the car. */
    const val AA_PORT = 5277

    /**
     * Identity probe port. Single-purpose: the car opens a short connection,
     * sends `IDENTITY_PROBE_REQUEST`, the companion replies with
     * `OAL!{phone_id}\t{friendly_name}\n`. Used by subnet sweep and manual-IP
     * verification when mDNS is unavailable. Never carries AA traffic.
     */
    const val IDENTITY_PORT = 5278

    /**
     * UDP broadcast discovery port. The car sends a single
     * [IDENTITY_PROBE_REQUEST] datagram to `255.255.255.255:UDP_DISCOVERY_PORT`,
     * each running companion replies once with the same identity payload
     * that the TCP probe returns. Sub-50ms when the AP allows broadcast,
     * so this layer sits between mDNS (free, but flaky on AAOS 12/13) and
     * the TCP /24 sweep (always works, ~400ms wall time).
     */
    const val UDP_DISCOVERY_PORT = 5279

    /** mDNS service type the companion publishes and the car discovers. */
    const val MDNS_SERVICE_TYPE = "_openautolink._tcp"

    /** Identity probe request line (sent by the car). */
    const val IDENTITY_PROBE_REQUEST = "OAL?\n"

    /** Identity probe response prefix (sent by the companion). */
    const val IDENTITY_PROBE_RESPONSE_PREFIX = "OAL!"

    /** Wire-protocol version reported in mDNS TXT and identity responses. */
    const val PROTOCOL_VERSION = "1"
}
