package com.helpchoice.hal.mockinghal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Locks the CORS origin wildcard grammar in [wildcardOriginToRegex]:
 *  `*` host label = one label, `*` port = any port, bare `*` = any origin. */
class WildcardOriginTest {

    private fun matches(pattern: String, origin: String) =
        wildcardOriginToRegex(pattern).matches(origin)

    @Test fun exactOriginMatchesItselfOnly() {
        assertTrue(matches("http://localhost:8080", "http://localhost:8080"))
        assertFalse(matches("http://localhost:8080", "http://localhost:9090"))
    }

    @Test fun wildcardPortMatchesAnyPortButRespectsScheme() {
        assertTrue(matches("http://localhost:*", "http://localhost:3000"))
        assertTrue(matches("http://localhost:*", "http://localhost:80"))
        assertFalse(matches("http://localhost:*", "https://localhost:3000")) // scheme differs
    }

    @Test fun wildcardHostLabelMatchesExactlyOneLabel() {
        assertTrue(matches("http://*.example.com", "http://sub.example.com"))
        assertFalse(matches("http://*.example.com", "http://a.b.example.com")) // two labels
        assertFalse(matches("http://*.example.com", "http://example.com"))     // zero labels
    }

    @Test fun bareStarMatchesAnyOrigin() {
        assertTrue(matches("*", "http://anything:1234"))
        assertTrue(matches("*", "https://evil.example.org"))
    }

    @Test fun anchoredSoNoPartialMatch() {
        assertFalse(matches("http://localhost:8080", "http://localhost:8080.evil.com"))
        assertFalse(matches("http://localhost:8080", "prefix-http://localhost:8080"))
    }
}
