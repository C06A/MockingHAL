package com.helpchoice.hal.mockinghal

import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks the port-resolution precedence and validation in [resolvePort]. */
class ResolvePortTest {

    @Test fun mockinghalPortHasHighestPrecedence() {
        assertEquals(9090, resolvePort(mockinghalPort = "9090", portEnv = "8000", sysProp = "7000"))
    }

    @Test fun portEnvUsedWhenMockinghalPortAbsent() {
        assertEquals(8000, resolvePort(mockinghalPort = null, portEnv = "8000", sysProp = "7000"))
    }

    @Test fun systemPropertyUsedWhenNoEnv() {
        assertEquals(7000, resolvePort(mockinghalPort = null, portEnv = null, sysProp = "7000"))
    }

    @Test fun defaultWhenNothingSet() {
        assertEquals(DEFAULT_PORT, resolvePort(null, null, null))
    }

    @Test fun nonNumericFallsBackToDefault() {
        assertEquals(DEFAULT_PORT, resolvePort(mockinghalPort = "abc", portEnv = null, sysProp = null))
    }

    @Test fun outOfRangeFallsBackToDefault() {
        assertEquals(DEFAULT_PORT, resolvePort(mockinghalPort = "0", portEnv = null, sysProp = null))
        assertEquals(DEFAULT_PORT, resolvePort(mockinghalPort = "70000", portEnv = null, sysProp = null))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals(9090, resolvePort(mockinghalPort = "  9090  ", portEnv = null, sysProp = null))
    }
}
