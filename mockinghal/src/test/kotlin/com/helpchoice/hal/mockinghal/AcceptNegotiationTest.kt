package com.helpchoice.hal.mockinghal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the Accept-header q-value content negotiation in [RequestMatcher]:
 * a headerIn pattern wins only when the media type it matches is *preferred*
 * (higher q) over the node's own default Content-Type. Regression guard for the
 * bug where `application/hal+xml;q=0.9` overrode the higher-priority default.
 */
class AcceptNegotiationTest {

    // Mirrors the bundled api_root: default hal+json, plus xml/yaml headerIn variants.
    private val cfg = """
        api:
          path:
            "/":
              method:
                GET:
                  code: 200
                  headerOut: { Content-Type: application/hal+json }
                  resource: jsonbody
                  headerIn:
                    Accept:
                      "application/hal\\+xml":
                        headerOut: { Content-Type: application/hal+xml }
                        resource: xmlbody
                      "application/hal\\+yaml":
                        headerOut: { Content-Type: application/hal+yaml }
                        resource: yamlbody
    """.trimIndent()

    private fun negotiate(accept: String?): MatchResult {
        val roots = ResourceRegistry.parseConfig(cfg).getOrThrow()
        val headers = if (accept == null) emptyMap() else mapOf("accept" to accept)
        return RequestMatcher.findMatch(roots, "GET", "/", "", headers, "")!!
    }

    private fun ct(accept: String?) = negotiate(accept).headerOut["Content-Type"]

    @Test fun higherPriorityDefaultBeatsLowerQVariant() {
        // the original bug: json (q=1.0) must win over xml (q=0.9)
        assertEquals("application/hal+json", ct("application/hal+json, application/hal+xml;q=0.9"))
    }

    @Test fun explicitlyRequestedVariantWins() {
        assertEquals("application/hal+xml", ct("application/hal+xml"))
        assertEquals("application/hal+yaml", ct("application/hal+yaml"))
    }

    @Test fun variantWinsWhenPreferredOverDefault() {
        assertEquals("application/hal+xml", ct("application/hal+xml, application/hal+json;q=0.5"))
    }

    @Test fun wildcardAndAbsentAcceptUseDefault() {
        assertEquals("application/hal+json", ct("*/*"))
        assertEquals("application/hal+json", ct(null))
    }

    @Test fun qZeroRejectsTheDefaultSoVariantWins() {
        assertEquals("application/hal+xml", ct("application/hal+json;q=0, application/hal+xml;q=0.9"))
    }

    @Test fun resourceBodyFollowsNegotiatedRepresentation() {
        assertEquals("jsonbody", negotiate("application/hal+json, application/hal+xml;q=0.9").resource)
        assertEquals("xmlbody", negotiate("application/hal+xml").resource)
    }

    @Test fun nonAcceptHeaderUsesPlainContainmentNotQValues() {
        // A header other than Accept must ignore q-values and match by containment.
        val c = """
            r:
              path:
                "/x":
                  method:
                    GET:
                      code: 200
                      resource: default
                      headerIn:
                        X-Format:
                          "application/hal\\+xml":
                            resource: xml
        """.trimIndent()
        val roots = ResourceRegistry.parseConfig(c).getOrThrow()
        // q=0.1 would lose under Accept negotiation, but X-Format uses plain containment → matches.
        val m = RequestMatcher.findMatch(
            roots, "GET", "/x", "", mapOf("x-format" to "application/hal+xml;q=0.1"), "",
        )!!
        assertEquals("xml", m.resource)
    }
}
