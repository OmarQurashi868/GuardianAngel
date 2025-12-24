package com.example.guardianangel.core

import android.media.AudioFormat

/**
 * Centralized constants to avoid scattering magic numbers and strings.
 *
 * Notes:
 * - Use const val for primitives and Strings.
 * - Use val for constants sourced from Android SDK (e.g., AudioFormat.*) that may not be compile-time constants.
 */
object Constants {

    // Logging
    const val TAG: String = "GuardianAngel"

    // Networking
    const val AUDIO_PORT: Int = 5353
    const val PTT_PORT: Int = 5354
    const val CONNECTION_TIMEOUT_MS: Long = 30_000L // 30 seconds

    // NSD (Network Service Discovery)
    const val NSD_SERVICE_TYPE_WARD: String = "_ward._tcp"
    const val NSD_SERVICE_NAME_PREFIX_WARD: String = "ward_"

    // Audio configuration
    const val SAMPLE_RATE: Int = 44_100
    val CHANNEL_CONFIG_IN: Int = AudioFormat.CHANNEL_IN_MONO
    val CHANNEL_CONFIG_OUT: Int = AudioFormat.CHANNEL_OUT_MONO
    val AUDIO_FORMAT: Int = AudioFormat.ENCODING_PCM_16BIT

    // Notifications
    const val NOTIFICATION_CHANNEL_ID: String = "guardian_angel_channel"
    const val ALERT_CHANNEL_ID: String = "guardian_angel_alert_channel"
    const val NOTIFICATION_ID: Int = 1
    const val ALERT_NOTIFICATION_ID: Int = 2
    const val REQUEST_CODE_NOTIFICATION: Int = 101
}
