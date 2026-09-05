package com.classsentinel.service

/** Tracks provisional rollcall alerts until their authoritative final arrives. */
internal class EarlyRollcallAlertGate {

    private val alertedUtteranceIds = mutableSetOf<Int>()

    @Synchronized
    fun record(utteranceId: Int): Boolean = alertedUtteranceIds.add(utteranceId)

    @Synchronized
    fun consume(utteranceId: Int): Boolean = alertedUtteranceIds.remove(utteranceId)

    @Synchronized
    fun clear() {
        alertedUtteranceIds.clear()
    }
}