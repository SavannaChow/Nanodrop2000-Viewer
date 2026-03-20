package com.tbwk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TbwkParserTest {
    @Test
    fun parsesExampleWorksheet() {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not available.")
        val root = generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "examples").isDirectory }
            ?: error("Could not locate repository root from $userDir.")
        val sample = File(root, "examples/nanodrop-dna-measurements-01.twbk")

        val worksheet = sample.inputStream().use(TbwkParser::parse)

        assertEquals(13, worksheet.measurements.size)
        assertEquals("wash", worksheet.measurements.first().title)
        assertEquals("Wavelength (nm)", worksheet.measurements.first().xLabel)
        assertTrue(worksheet.measurements.first().properties.properties.containsKey("A260"))
        assertEquals(
            listOf(
                "wash",
                "blank",
                "BSD01",
                "BSD01",
                "BSD01 cntl A1",
                "wash",
                "BSD01 cntl A2",
                "wash",
                "BSD01 cntl A3",
                "BSD01 cntl A3",
                "BSD01 cntl A4",
                "wash",
                "wash",
            ),
            worksheet.measurements.map { it.title }
        )
    }
}
