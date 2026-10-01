package com.streamcap.mobile.network

import org.junit.Assert.*
import org.junit.Test

class RecordingNetworkPolicyTest {
    @Test fun wifiOnlyRejectsMeteredCellularAndUnknownNetworks() {
        assertFalse(RecordingNetworkPolicy.permits(false, true, false, false))
        assertFalse(RecordingNetworkPolicy.permits(false, true, true, true))
        assertFalse(RecordingNetworkPolicy.permits(false, false, false, false))
        assertTrue(RecordingNetworkPolicy.permits(false, true, true, false))
    }
    @Test fun mobileOptInLeavesConnectivityErrorsToTransport() {
        assertTrue(RecordingNetworkPolicy.permits(true, true, false, true))
        assertTrue(RecordingNetworkPolicy.permits(true, false, false, false))
    }
}
