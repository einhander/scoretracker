package com.einhander.temposcore.transport

data class TransportSnapshot(
    val transportBpm: Double,
    val detectedBpm: Double,
    val quarterBeatPosition: Double,
    val confidence: Double,
    val rms: Double,
    val running: Boolean,
)
