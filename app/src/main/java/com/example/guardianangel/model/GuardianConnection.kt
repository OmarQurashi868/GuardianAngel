package com.example.guardianangel.model

import java.net.Socket

/**
 * Represents a connected Guardian client.
 *
 * @property deviceName Human-readable device name of the guardian (e.g., Build.MODEL).
 * @property ipAddress IPv4 address of the guardian device.
 * @property socket The active TCP socket connection to the guardian.
 */
data class GuardianConnection(
    val deviceName: String,
    val ipAddress: String,
    val socket: Socket
)
