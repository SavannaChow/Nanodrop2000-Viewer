package com.tbwk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class TbwkEditorTest {
    @Test
    fun renamesAndDeletesSamplesInEditedCopy() {
        val root = File(requireNotNull(System.getProperty("user.dir"))).parentFile?.parentFile
            ?: error("Could not resolve repository root.")
        val sample = File(root, "examples/nanodrop-dna-measurements-01.twbk")
        val document = TbwkEditableDocument.parse(sample.readBytes())

        document.renameMeasurement(0, "wash-renamed")
        document.deleteMeasurement(1)

        val worksheet = TbwkParser.parse(document.serializedBytes())
        assertEquals(12, worksheet.measurements.size)
        assertEquals("wash-renamed", worksheet.measurements.first().title)
        assertFalse(worksheet.measurements.any { it.title == "blank" })
    }
}
