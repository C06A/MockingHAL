package com.helpchoice.hal.mockinghal

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end HTTP contract over [Application.module] using Ktor's in-process test host:
 * default startup load, the POST/PATCH/DELETE config lifecycle, matching, error codes,
 * Accept negotiation, multipart, and the hand-rolled CORS interceptor.
 */
class ApplicationTest {

    // Each test starts from a clean registry; module() re-loads the bundled defaults on start.
    @BeforeTest fun reset() = ResourceRegistry.replace(emptyMap())

    private val custom = """
        m:
          path:
            "/custom":
              method:
                GET:
                  code: 200
                  headerOut: { Content-Type: application/json }
                  resource: { hello: world }
    """.trimIndent()

    // ── startup / matching ─────────────────────────────────────────────────
    @Test fun servesBundledDefaultRoot() = testApplication {
        application { module() }
        val resp = client.get("/") { header(HttpHeaders.Accept, "application/hal+json") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("MockingHAL"))
    }

    @Test fun unmatchedPathReturns404Json() = testApplication {
        application { module() }
        val resp = client.get("/definitely-not-here")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains("Resource not found"))
    }

    // ── Accept negotiation against the real default config ──────────────────
    @Test fun acceptNegotiationPrefersHigherQDefault() = testApplication {
        application { module() }
        val resp = client.get("/") {
            header(HttpHeaders.Accept, "application/hal+json, application/hal+xml;q=0.9")
        }
        assertTrue(resp.headers[HttpHeaders.ContentType]!!.contains("application/hal+json"))
    }

    @Test fun acceptNegotiationHonoursExplicitXml() = testApplication {
        application { module() }
        val resp = client.get("/") { header(HttpHeaders.Accept, "application/hal+xml") }
        assertTrue(resp.headers[HttpHeaders.ContentType]!!.contains("application/hal+xml"))
    }

    // ── POST / PATCH / DELETE lifecycle ─────────────────────────────────────
    @Test fun postReplacesAllResources() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Created, client.post("/") { setBody(custom) }.status)
        assertTrue(client.get("/custom").bodyAsText().contains("world"))
        assertEquals(HttpStatusCode.NotFound, client.get("/").status) // default root replaced away
    }

    @Test fun patchAppendsAndOverrides() = testApplication {
        application { module() }
        client.post("/") { setBody(custom) }
        val extra = """
            n:
              path:
                "/extra":
                  method:
                    GET: { code: 200, resource: extra }
        """.trimIndent()
        assertEquals(HttpStatusCode.OK, client.patch("/") { setBody(extra) }.status)
        assertTrue(client.get("/custom").bodyAsText().contains("world")) // preserved
        assertTrue(client.get("/extra").bodyAsText().contains("extra"))  // added
    }

    @Test fun deleteResetsToDefaults() = testApplication {
        application { module() }
        client.post("/") { setBody(custom) }
        assertEquals(HttpStatusCode.OK, client.get("/custom").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/").status)
        assertEquals(HttpStatusCode.OK, client.get("/").status)             // default root back
        assertEquals(HttpStatusCode.NotFound, client.get("/custom").status) // custom gone
    }

    @Test fun invalidConfigReturns400() = testApplication {
        application { module() }
        val bad = """
            bad:
              path:
                "[":
                  method:
                    GET: { code: 200 }
        """.trimIndent()
        val resp = client.post("/") { setBody(bad) }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("Invalid config"))
    }

    @Test fun multipartBodyMergesParts() = testApplication {
        application { module() }
        val resp = client.post("/") {
            setBody(MultiPartFormDataContent(formData {
                append("p1", "m1:\n  path:\n    \"/m1\":\n      method:\n        GET: { code: 200, resource: one }\n")
                append("p2", "m2:\n  path:\n    \"/m2\":\n      method:\n        GET: { code: 200, resource: two }\n")
            }))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        assertTrue(client.get("/m1").bodyAsText().contains("one"))
        assertTrue(client.get("/m2").bodyAsText().contains("two"))
    }

    // ── CORS (bundled cors.yaml allows http://localhost:*) ──────────────────
    @Test fun corsHeaderAddedForAllowedOrigin() = testApplication {
        application { module() }
        val origin = "http://localhost:3000"
        val aco = client.get("/") { header(HttpHeaders.Origin, origin) }
            .headers[HttpHeaders.AccessControlAllowOrigin]
        assertNotNull(aco)
        assertTrue(aco == origin || aco == "*")
    }

    @Test fun corsPreflightReturns204WithAllowMethods() = testApplication {
        application { module() }
        val resp = client.options("/") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        assertNotNull(resp.headers[HttpHeaders.AccessControlAllowMethods])
    }

    @Test fun corsIsIndependentOfResourceConfig() = testApplication {
        application { module() }
        client.post("/") { setBody(custom) } // replace resources
        val aco = client.get("/custom") { header(HttpHeaders.Origin, "http://localhost:7777") }
            .headers[HttpHeaders.AccessControlAllowOrigin]
        assertNotNull(aco) // CORS loaded at startup, unaffected by the config replace
    }
}
