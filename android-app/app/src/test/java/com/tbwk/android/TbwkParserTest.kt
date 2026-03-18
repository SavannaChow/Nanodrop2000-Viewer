package com.tbwk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TbwkParserTest {
    @Test
    fun parsesExampleWorksheet() {
        val root = File(System.getProperty("user.dir")).parentFile
        val sample = File(root, "examples/nanodrop-dna-measurements-01.twbk")

        val worksheet = sample.inputStream().use(TbwkParser::parse)

        assertEquals(13, worksheet.measurements.size)
        assertEquals("wash", worksheet.measurements.first().title)
        assertEquals("Wavelength (nm)", worksheet.measurements.first().xLabel)
        assertTrue(worksheet.measurements.first().properties.properties.containsKey("A260"))
    }
}
