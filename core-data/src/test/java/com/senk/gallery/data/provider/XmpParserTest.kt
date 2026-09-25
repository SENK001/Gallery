package com.senk.gallery.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpParserTest {

    @Test
    fun googleMotionPhotoIsDetected() {
        val xmp = "<rdf:Description GCamera:MotionPhoto=\"1\" " +
            "GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"12\"/>"
        assertTrue(XmpParser.isMotionPhoto(xmp))
    }

    @Test
    fun legacyMicroVideoIsDetected() {
        val xmp = "<rdf:Description GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"123\"/>"
        assertTrue(XmpParser.isMotionPhoto(xmp))
    }

    @Test
    fun plainImageIsNotMotionPhoto() {
        assertFalse(XmpParser.isMotionPhoto(null))
        assertFalse(XmpParser.isMotionPhoto(""))
        assertFalse(XmpParser.isMotionPhoto("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"))
    }

    @Test
    fun containerMotionPhotoVideoLengthIsRead() {
        val xmp = """
            <rdf:Description GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item
                        Item:Mime="image/jpeg"
                        Item:Semantic="Primary"
                        Item:Length="4035092"
                        Item:Padding="0"/>
                    <Container:Item
                        Item:Mime="image/jpeg"
                        Item:Semantic="GainMap"
                        Item:Length="853469"
                        Item:Padding="0"/>
                    <Container:Item
                        Item:Mime="video/mp4"
                        Item:Semantic="MotionPhoto"
                        Item:Length="5939308"
                        Item:Padding="0"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        assertEquals(5939308L, XmpParser.motionVideoLength(xmp))
    }

    @Test
    fun legacyMicroVideoOffsetIsUsedAsLength() {
        val xmp = "<rdf:Description GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"1234567\"/>"
        assertEquals(1234567L, XmpParser.motionVideoLength(xmp))
    }

    @Test
    fun plainImageHasNoMotionVideoLength() {
        assertEquals(0L, XmpParser.motionVideoLength(null))
        assertEquals(0L, XmpParser.motionVideoLength(""))
        assertEquals(
            0L,
            XmpParser.motionVideoLength("<rdf:Description GCamera:MotionPhoto=\"1\"/>"),
        )
    }

    @Test
    fun panoramaIsDetected() {
        val xmp = "<rdf:Description GPano:ProjectionType=\"equirectangular\" " +
            "GPano:UsePanoramaViewer=\"True\"/>"
        assertTrue(XmpParser.isPanorama(xmp))
    }

    @Test
    fun panoramaSizeFallback() {
        assertTrue(XmpParser.isPanoramaBySize(8000, 2000))
        assertFalse(XmpParser.isPanoramaBySize(4000, 3000))
        assertFalse(XmpParser.isPanoramaBySize(0, 0))
    }
}
