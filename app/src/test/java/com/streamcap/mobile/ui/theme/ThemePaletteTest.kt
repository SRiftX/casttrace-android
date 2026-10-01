package com.streamcap.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun `classic is the default and unknown ids fall back to it`() {
        assertEquals(ThemePalette.CLASSIC, ThemePalette.fromId(null))
        assertEquals(ThemePalette.CLASSIC, ThemePalette.fromId("unsupported"))
    }

    @Test
    fun `every palette survives storage round trip`() {
        ThemePalette.entries.forEach { palette ->
            assertEquals(palette, ThemePalette.fromId(palette.id))
        }
    }
}
