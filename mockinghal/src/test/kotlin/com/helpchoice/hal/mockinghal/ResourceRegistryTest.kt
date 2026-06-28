package com.helpchoice.hal.mockinghal

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Locks config parsing (YAML & JSON, eager regex validation) and the
 *  atomic replace/append registry operations. */
class ResourceRegistryTest {

    @BeforeTest fun reset() = ResourceRegistry.replace(emptyMap())
    @AfterTest  fun clear() = ResourceRegistry.replace(emptyMap())

    @Test fun parsesYaml() {
        val map = ResourceRegistry.parseConfig(
            """
            r:
              path:
                "/a":
                  method:
                    GET: { code: 200, resource: ok }
            """.trimIndent()
        ).getOrThrow()
        assertEquals(setOf("r"), map.keys)
        assertEquals(200, map["r"]!!.path["/a"]!!.method["GET"]!!.code)
    }

    @Test fun parsesJsonToo() {
        val map = ResourceRegistry.parseConfig(
            """{ "r": { "path": { "/a": { "method": { "GET": { "code": 201, "resource": "ok" } } } } } }"""
        ).getOrThrow()
        assertEquals(201, map["r"]!!.path["/a"]!!.method["GET"]!!.code)
    }

    @Test fun yamlAndJsonProduceEqualMaps() {
        val yaml = ResourceRegistry.parseConfig(
            """
            r:
              path:
                "/a":
                  method:
                    GET: { code: 200 }
            """.trimIndent()
        ).getOrThrow()
        val json = ResourceRegistry.parseConfig(
            """{ "r": { "path": { "/a": { "method": { "GET": { "code": 200 } } } } } }"""
        ).getOrThrow()
        assertEquals(yaml, json)
    }

    @Test fun invalidRegexInPathFailsAtParse() {
        val result = ResourceRegistry.parseConfig(
            """
            bad:
              path:
                "[":
                  method:
                    GET: { code: 200 }
            """.trimIndent()
        )
        assertTrue(result.isFailure, "an invalid regex pattern must fail parsing")
    }

    @Test fun invalidRegexInBodyFailsAtParse() {
        val result = ResourceRegistry.parseConfig(
            """
            bad:
              path:
                "/x":
                  method:
                    POST:
                      body:
                        "(":
                          code: 200
            """.trimIndent()
        )
        assertTrue(result.isFailure)
    }

    @Test fun malformedDocumentFails() {
        // a top-level JSON array cannot map to Map<String, TreeNode>
        assertTrue(ResourceRegistry.parseConfig("[1, 2, 3]").isFailure)
    }

    @Test fun replaceSetsExactlyTheGivenMap() {
        val a = ResourceRegistry.parseConfig("""a: { path: { "/a": {} } }""").getOrThrow()
        ResourceRegistry.replace(a)
        assertEquals(setOf("a"), ResourceRegistry.getAll().keys)
        val b = ResourceRegistry.parseConfig("""b: { path: { "/b": {} } }""").getOrThrow()
        ResourceRegistry.replace(b)
        assertEquals(setOf("b"), ResourceRegistry.getAll().keys) // a gone
    }

    @Test fun appendAddsNewKeysAndOverridesCollisions() {
        ResourceRegistry.replace(
            ResourceRegistry.parseConfig(
                """
                a: { path: { "/a": { method: { GET: { code: 200 } } } } }
                b: { path: { "/b": {} } }
                """.trimIndent()
            ).getOrThrow()
        )
        ResourceRegistry.append(
            ResourceRegistry.parseConfig(
                """
                a: { path: { "/a": { method: { GET: { code: 201 } } } } }
                c: { path: { "/c": {} } }
                """.trimIndent()
            ).getOrThrow()
        )
        val all = ResourceRegistry.getAll()
        assertEquals(setOf("a", "b", "c"), all.keys)                          // b preserved, c added
        assertEquals(201, all["a"]!!.path["/a"]!!.method["GET"]!!.code)        // a overridden
    }
}
