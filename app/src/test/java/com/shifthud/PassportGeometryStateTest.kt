package com.shifthud

import android.app.Application
import com.shifthud.ui.paystub.*
import com.shifthud.ui.scheduleimport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PassportGeometryStateTest {
    @Test fun blockLineAndElementBoxesSurviveSavedReviewRoundTrip() {
        val document=OcrDocument(listOf(OcrBlock(OcrPiece("SAT 17",10,20,80,80),listOf(OcrLine(OcrPiece("SAT",10,20,80,40),listOf(OcrPiece("SAT",12,21,70,39))),OcrLine(OcrPiece("17",20,45,60,80))))))
        assertEquals(document,decodeScheduleGeometry(document.encodeGeometry()))
    }
    @Test fun invalidOrOversizedGeometryCannotBeRestored() {
        assertNull(decodeScheduleGeometry("not json"));assertNull(decodeScheduleGeometry(" ".repeat(200001)))
    }
}
