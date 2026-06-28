package com.helpchoice.hal.mockinghal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Locks the [RequestMatcher] semantics: method/path strict filters, optional
 *  query/headerIn/body selectors, response accumulation, and root ordering. */
class RequestMatcherTest {

    private fun parse(cfg: String) =
        ResourceRegistry.parseConfig(cfg.trimIndent()).getOrThrow()

    private fun find(
        cfg: String, method: String, path: String,
        query: String = "", headers: Map<String, String> = emptyMap(), body: String = "",
    ) = RequestMatcher.findMatch(parse(cfg), method, path, query, headers, body)

    // ── method (strict AND) ────────────────────────────────────────────────
    @Test fun methodMustMatch() {
        val cfg = """
            r:
              path:
                "/ping":
                  method:
                    GET: { code: 200, resource: pong }
        """
        assertEquals("pong", find(cfg, "GET", "/ping")?.resource)
        assertNull(find(cfg, "POST", "/ping"))
    }

    // ── path (regex prefix, segment-boundary) ──────────────────────────────
    @Test fun pathIsPrefixMatchedAtSegmentBoundary() {
        val cfg = """
            r:
              path:
                "/items":
                  method:
                    GET: { code: 200, resource: items }
        """
        assertEquals("items", find(cfg, "GET", "/items")?.resource)     // exact
        assertEquals("items", find(cfg, "GET", "/items/1")?.resource)   // prefix at boundary
        assertNull(find(cfg, "GET", "/itemsX"))                         // not a boundary
        assertNull(find(cfg, "GET", "/item"))                          // shorter than prefix
    }

    @Test fun emptyPathKeyMatchesOnlyWhenNoSegmentRemains() {
        val cfg = """
            r:
              path:
                "/x":
                  path:
                    "":
                      method: { GET: { code: 200, resource: exact } }
                    "/sub":
                      method: { GET: { code: 200, resource: sub } }
        """
        assertEquals("exact", find(cfg, "GET", "/x")?.resource)
        assertEquals("sub", find(cfg, "GET", "/x/sub")?.resource)
        assertNull(find(cfg, "GET", "/x/other"))
    }

    @Test fun pathPatternIsAFullRegex() {
        val cfg = """
            r:
              path:
                "/item-\\d+":
                  method:
                    GET: { code: 200, resource: num }
        """
        assertEquals("num", find(cfg, "GET", "/item-42")?.resource)
        assertNull(find(cfg, "GET", "/item-x"))
    }

    // ── query (optional selector) ──────────────────────────────────────────
    @Test fun queryOverridesWhenMatchingAndEmptyKeyMatchesEmptyQuery() {
        val cfg = """
            r:
              path:
                "/q":
                  method:
                    GET:
                      code: 200
                      resource: all
                      query:
                        "":
                          resource: emptyq
                        "userId=1":
                          resource: user1
        """
        assertEquals("emptyq", find(cfg, "GET", "/q", query = "")?.resource)
        assertEquals("user1", find(cfg, "GET", "/q", query = "userId=1")?.resource)
        assertEquals("all", find(cfg, "GET", "/q", query = "userId=2")?.resource) // default
    }

    // ── headerIn (optional selector, containment; missing = empty) ──────────
    @Test fun headerInSelectsByContainmentAndDefaultsWhenAbsent() {
        val cfg = """
            r:
              path:
                "/h":
                  method:
                    GET:
                      code: 200
                      resource: anon
                      headerIn:
                        Authorization:
                          "Bearer":
                            resource: authed
        """
        assertEquals("authed", find(cfg, "GET", "/h", headers = mapOf("authorization" to "Bearer xyz"))?.resource)
        assertEquals("anon", find(cfg, "GET", "/h")?.resource) // missing header → empty → default
    }

    // ── body (optional selector, containment) ──────────────────────────────
    @Test fun bodySelectsByContainment() {
        val cfg = """
            r:
              path:
                "/b":
                  method:
                    POST:
                      code: 200
                      resource: normal
                      body:
                        '"role":"admin"':
                          code: 403
                          resource: denied
        """
        assertEquals(403, find(cfg, "POST", "/b", body = """{"role":"admin"}""")?.code)
        assertEquals("normal", find(cfg, "POST", "/b", body = """{"role":"user"}""")?.resource)
    }

    // ── response accumulation (code/resource override, headerOut merge) ─────
    @Test fun responsesAccumulateAlongThePath() {
        val cfg = """
            r:
              path:
                "/acc":
                  code: 200
                  resource: parent
                  headerOut:
                    X-Parent: p
                    Content-Type: text/plain
                  method:
                    GET:
                      code: 201
                      resource: child
                      headerOut:
                        X-Child: c
                        Content-Type: application/json
        """
        val m = assertNotNull(find(cfg, "GET", "/acc"))
        assertEquals(201, m.code)               // child overrides
        assertEquals("child", m.resource)       // child overrides
        assertEquals("p", m.headerOut["X-Parent"])          // parent header kept
        assertEquals("c", m.headerOut["X-Child"])           // child header added
        assertEquals("application/json", m.headerOut["Content-Type"]) // child wins on collision
    }

    // ── roots tried in insertion order, first full match wins ───────────────
    @Test fun firstMatchingRootWins() {
        val cfg = """
            first:
              path: { "/x": { method: { GET: { code: 200, resource: first } } } }
            second:
              path: { "/x": { method: { GET: { code: 200, resource: second } } } }
        """
        assertEquals("first", find(cfg, "GET", "/x")?.resource)
    }

    @Test fun noMatchReturnsNull() {
        val cfg = """
            r:
              path: { "/only": { method: { GET: { code: 200, resource: ok } } } }
        """
        assertNull(find(cfg, "GET", "/nope"))
    }
}
