package com.huigu.phone10.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OperitDiagnosticCodeTest {
    @Test fun keepsOnlyRecognizedProtocolFailureCodes() {
        assertEquals("OPERIT_TIMEOUT", OperitBridge.safeCode("OPERIT_TIMEOUT"))
        assertEquals("OPERIT_OBSERVE_FAILED", OperitBridge.safeCode("OPERIT_OBSERVE_FAILED"))
        assertEquals("OPERIT_INVALID_EVENT", OperitBridge.safeCode("OPERIT_INVALID_EVENT"))
    }

    @Test fun neverIncludesRawErrorTextOrCredentials() {
        for (raw in listOf(null, "", "https://private.invalid?token=secret", "OPERIT_TIMEOUT key=secret", "private chat text")) {
            assertEquals("OPERIT_UNAVAILABLE", OperitBridge.safeCode(raw))
        }
    }
}
