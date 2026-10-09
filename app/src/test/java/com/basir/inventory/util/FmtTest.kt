package com.basir.inventory.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FmtTest {
    @After fun reset() { Fmt.bangla = true }

    @Test fun takaUsesBanglaDigitsAndLakhGrouping() {
        Fmt.bangla = true
        assertEquals("৳১২,৩৪,৫৬৭", Fmt.taka(1_234_567_00))
        assertEquals("৳১২.৫০", Fmt.taka(1250))
        assertEquals("৳০", Fmt.taka(0))
    }

    @Test fun englishModeKeepsAsciiDigits() {
        Fmt.bangla = false
        assertEquals("৳1,00,000", Fmt.taka(100_000_00))
        assertEquals("12.5", Fmt.qty(12_500))
    }

    @Test fun parsesBanglaAndAsciiInput() {
        assertEquals(12_500L, Fmt.parseQty("১২.৫"))
        assertEquals(12_500L, Fmt.parseQty("12.5"))
        assertEquals(1_234_50L, Fmt.parseMoney("1,234.5"))
        assertNull(Fmt.parseQty("-1"))
        assertNull(Fmt.parseQty("abc"))
        assertNull(Fmt.parseQty(""))
    }

    @Test fun plainQtyRoundTrips() {
        assertEquals("10", Fmt.plainQty(10_000))
        assertEquals("0.25", Fmt.plainQty(250))
        assertEquals("120.5", Fmt.plainMoney(12050))
    }

    @Test fun lineTotalHandlesFractionalQuantities() {
        // 12.5 ft at ৳40/ft = ৳500
        assertEquals(500_00L, lineTotal(40_00, 12_500))
        assertEquals(3_33L, lineTotal(10_00, 333))
    }
}
