package com.arenacast.mediaprojection.rtmp

import com.pedro.rtsp.utils.ConnectCheckerRtsp
import net.ossrs.rtmp.ConnectCheckerRtmp

interface ConnectCheckerRtp: ConnectCheckerRtmp, ConnectCheckerRtsp {

    /**
     * Commons
     */
    fun onConnectionSuccessRtp()

    fun onConnectionFailedRtp(reason: String)

    fun onNewBitrateRtp(bitrate: Long)

    fun onDisconnectRtp()

    fun onAuthErrorRtp()

    fun onAuthSuccessRtp()

    /**
     * RTMP
     */
    override fun onConnectionSuccessRtmp() {
        onConnectionSuccessRtp()
    }

    override fun onConnectionFailedRtmp(reason: String) {
        onConnectionFailedRtp(reason)
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        onNewBitrateRtp(bitrate)
    }

    override fun onDisconnectRtmp() {
        onDisconnectRtp()
    }

    override fun onAuthErrorRtmp() {
        onAuthErrorRtp()
    }

    override fun onAuthSuccessRtmp() {
        onAuthSuccessRtp()
    }

    /**
     * RTSP
     */
    override fun onConnectionSuccessRtsp() {
        onConnectionSuccessRtp()
    }

    override fun onConnectionFailedRtsp(reason: String) {
        onConnectionFailedRtp(reason)
    }

    override fun onNewBitrateRtsp(bitrate: Long) {
        onNewBitrateRtp(bitrate)
    }

    override fun onDisconnectRtsp() {
        onDisconnectRtp()
    }

    override fun onAuthErrorRtsp() {
        onAuthErrorRtp()
    }

    override fun onAuthSuccessRtsp() {
        onAuthSuccessRtp()
    }
}