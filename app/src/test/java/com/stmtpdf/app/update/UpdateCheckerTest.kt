package com.stmtpdf.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 单测：版本号比较规则。 */
class UpdateCheckerTest {

    @Test
    fun higherPatchIsNewer() {
        assertTrue(UpdateChecker.compareVersions("0.2.3", "0.2.2") > 0)
    }

    @Test
    fun sameVersionIsEqual() {
        assertEquals(0, UpdateChecker.compareVersions("0.2.3", "0.2.3"))
    }

    @Test
    fun olderVersionIsLower() {
        assertTrue(UpdateChecker.compareVersions("0.2.2", "0.2.3") < 0)
    }

    @Test
    fun numericComparisonNotLexical() {
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.9") > 0)
    }

    @Test
    fun shorterVersionPadsWithZero() {
        assertEquals(0, UpdateChecker.compareVersions("1.2", "1.2.0"))
        assertTrue(UpdateChecker.compareVersions("1.2.1", "1.2") > 0)
    }

    @Test
    fun malformedSegmentsTreatedAsZero() {
        assertEquals(0, UpdateChecker.compareVersions("0.2.x", "0.2.0"))
    }
}
