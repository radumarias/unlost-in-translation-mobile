package com.example.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The routing decision that used to be written out longhand at five call sites.
 * Each copy was free to invert the sides on its own, and the symptom — the words
 * coming out of the wrong headset — is silent.
 */
class DualHeadsetModelTest {

    private val assigned = DualHeadsetModel(deviceA = 11, deviceB = 22, isDualHeadsetModeEnabled = true)

    @Test
    fun eachSideGetsItsOwnDevice() {
        assertEquals(11, assigned.deviceFor(forSideA = true))
        assertEquals(22, assigned.deviceFor(forSideA = false))
    }

    @Test
    fun routingOffMeansThePhoneDecides() {
        val off = assigned.copy(isDualHeadsetModeEnabled = false)
        assertNull(off.deviceFor(forSideA = true))
        assertNull(off.deviceFor(forSideA = false))
    }

    @Test
    fun anUnassignedSideIsNullEvenWithRoutingOn() {
        // Half-configured is a real state: the dialog assigns one headset at a
        // time, and the unassigned side must fall back rather than borrow the
        // other person's.
        val halfDone = DualHeadsetModel(deviceA = 11, deviceB = null, isDualHeadsetModeEnabled = true)
        assertEquals(11, halfDone.deviceFor(forSideA = true))
        assertNull(halfDone.deviceFor(forSideA = false))
    }

    @Test
    fun defaultsRouteNowhere() {
        assertNull(DualHeadsetModel().deviceFor(forSideA = true))
        assertNull(DualHeadsetModel().deviceFor(forSideA = false))
    }
}
