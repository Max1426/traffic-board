package com.liuliang.board

import com.liuliang.board.data.*
import com.liuliang.board.model.*
import org.junit.Assert.*
import org.junit.Test

class TrafficRulesTest {
    @Test fun normalHeaderAndUnitConversion() {
        val info = HeaderParser.parse("upload=1073741824; download=2147483648; total=128849018880; expire=1800000000")!!
        assertEquals(3221225472L, info.used)
        assertEquals(125627793408L, info.remaining)
        assertEquals(2.5, info.percent!!, 0.00001)
        assertEquals("3.00 GiB", Display.bytes(info.used))
    }
    @Test fun absentHeaderDoesNotCreateZeroUsage() { assertNull(HeaderParser.parse(null)); assertNull(HeaderParser.parse(" ")); assertNull(HeaderParser.parse("not a header")) }
    @Test fun partialCountersRemainUnknown() {
        val info = HeaderParser.parse("download=100; total=1000")!!
        assertNull(info.used); assertNull(info.remaining); assertNull(info.percent); assertFalse(info.complete)
    }
    @Test fun malformedAndOverflowValuesAreNotCoercedToZero() {
        val info = HeaderParser.parse("upload=-1; download=9223372036854775808; total=1000; expire=garbage")!!
        assertNull(info.upload); assertNull(info.download); assertNull(info.used); assertNull(info.expire)
    }
    @Test fun duplicateValuesAreAmbiguous() { assertNull(HeaderParser.parse("upload=1; upload=2; download=5")!!.upload) }
    @Test fun caseAndWhitespaceAreAccepted() { assertEquals(3L, HeaderParser.parse(" UPLOAD = 1 ; Download = 2 ; total=4 ")!!.used) }
    @Test fun zeroTotalIsUnknownNotUnlimited() {
        val info = HeaderParser.parse("upload=0; download=0; total=0; expire=0")!!
        assertEquals(0L, info.used); assertNull(info.limit); assertNull(info.remaining); assertNull(info.percent); assertNull(info.expire)
    }
    @Test fun overagePreservesActualPercentageAndClampsRemaining() {
        val info = TrafficInfo(50, 100, 100)
        assertEquals(150.0, info.percent!!, 0.001); assertEquals(0L, info.remaining)
    }
    @Test fun sumOverflowRemainsUnknown() { assertNull(TrafficInfo(Long.MAX_VALUE, 1, 100).used) }
    @Test fun invalidExpiryCannotCrashDateFormatting() { assertNull(HeaderParser.parse("total=100;expire=999999999999")!!.expire); assertEquals("未提供", Display.date(null)) }
    @Test fun encryptedPayloadCodecRetainsNullableValuesAndAlertMemory() {
        val original = BoardData(listOf(Subscription("one", "中文名称", "https://example.com/?token=secret", info = TrafficInfo(null, 5, 0, null), alerts = AlertMemory(setOf(80), setOf(3), 1800000000))), AlertSettings(false, true, listOf(70, 90), listOf(9, 2)))
        assertEquals(original, BoardCodec.decode(BoardCodec.encode(original)))
    }
    @Test fun inputRulesRejectUnsafeOrIncompleteInputs() {
        assertNotNull(InputRules.validate("", "https://example.com", "clash"))
        assertNotNull(InputRules.validate("a", "http://example.com", "clash"))
        assertNotNull(InputRules.validate("a", "https://example.com", "clash\nsecret"))
        assertNotNull(InputRules.validate("a", "https://user:pass@example.com", "clash"))
        assertNull(InputRules.validate("a", "https://example.com/sub?token=x", "clash"))
        assertNull(InputRules.validate("a", "http://127.0.0.1/sub", "clash", true))
        assertNotNull(InputRules.validate("a", "http://127.0.0.1/sub", "clash", false))
    }
    @Test fun thresholdValidationNormalizesChineseCommaAndDuplicates() {
        assertEquals(listOf(80, 95), InputRules.thresholds("95，80,80", true))
        assertEquals(listOf(7, 3, 0), InputRules.thresholds("0,3,7", false))
        for (bad in listOf("", "-1", "101", "80,", "a", "0")) assertNull(InputRules.thresholds(bad, true))
        assertNull(InputRules.thresholds("366", false))
    }
}
