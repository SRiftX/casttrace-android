package com.streamcap.mobile.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCatalogEvidenceTest {
    @Test
    fun documentedPassingSamplesAreIncludedWithoutPromotingIncompleteRuns() {
        val passingKeys = PlatformCatalog.entriesWithPassingRecordingSamples.map { it.key }.toSet()

        assertTrue(
            passingKeys.containsAll(
                setOf(
                    "huya",
                    "bilibili",
                    "douyu",
                    "yy",
                    "maoer",
                    "twitcasting",
                    "twitch",
                    "showroom",
                    "inke",
                    "chzzk",
                    "sixroom",
                    "picarto",
                ),
            ),
        )
        assertFalse("AcFun did not reach the ten-minute target", "acfun" in passingKeys)
        assertFalse("FlexTV media audit failed", "flextv" in passingKeys)
        assertTrue(PlatformCatalog.entriesWithEvidence.any { it.key == "acfun" })
        assertTrue(PlatformCatalog.entriesWithEvidence.any { it.key == "flextv" })
    }
}
