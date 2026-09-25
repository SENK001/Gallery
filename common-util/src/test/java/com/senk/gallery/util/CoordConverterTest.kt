package com.senk.gallery.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordConverterTest {

    @Test
    fun convertsShanghaiWgs84ToGcj02() {
        val result = CoordConverter.wgs84ToGcj02(31.1774276, 121.5272106)
        assertEquals(31.17530398364597, result.first, 1e-6)
        assertEquals(121.531541859215, result.second, 1e-6)
    }

    @Test
    fun convertsShenzhenWgs84ToGcj02() {
        val result = CoordConverter.wgs84ToGcj02(22.543847, 113.912316)
        assertEquals(22.540796131694766, result.first, 1e-6)
        assertEquals(113.9171764808363, result.second, 1e-6)
    }

    @Test
    fun convertsBeijingWgs84ToGcj02() {
        val result = CoordConverter.wgs84ToGcj02(39.911954, 116.377817)
        assertEquals(39.91334545536069, result.first, 1e-6)
        assertEquals(116.38404722455657, result.second, 1e-6)
    }

    @Test
    fun keepsOverseasCoordinatesUnchanged() {
        val result = CoordConverter.wgs84ToGcj02(35.6586, 139.7454)
        assertEquals(35.6586, result.first, 1e-9)
        assertEquals(139.7454, result.second, 1e-9)
    }
}
