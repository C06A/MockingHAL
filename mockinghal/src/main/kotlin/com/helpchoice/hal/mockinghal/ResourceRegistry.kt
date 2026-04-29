package com.helpchoice.hal.mockinghal

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.concurrent.atomic.AtomicReference

object ResourceRegistry {

    /** Used to serialise [MatchResult.resource] objects to JSON before sending. */
    val jsonMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /**
     * YAMLMapper accepts both YAML and JSON input.
     * jackson-module-kotlin enables deserialization into Kotlin data classes.
     */
    private val yamlMapper: ObjectMapper = YAMLMapper().registerKotlinModule()

    private val resources = AtomicReference<Map<String, TreeNode>>(emptyMap())

    fun getAll(): Map<String, TreeNode> = resources.get()

    /** Atomically replace all loaded resources. */
    fun replace(newResources: Map<String, TreeNode>) = resources.set(newResources)

    /** Atomically append [newResources] to the current map, overriding any keys that collide. */
    fun append(newResources: Map<String, TreeNode>) {
        resources.updateAndGet { current ->
            LinkedHashMap(current).apply { putAll(newResources) }
        }
    }

    /**
     * Parse one YAML or JSON text block into a name → [TreeNode] map.
     *
     * Top-level keys are arbitrary labels used only for identification.
     * All regex patterns ([TreeNode.path] keys, [TreeNode.body] keys, and
     * [TreeNode.headerIn] value-pattern keys) are compiled eagerly so that
     * invalid patterns surface as parse errors (HTTP 400) at load time.
     *
     * @return [Result.success] with the parsed map (insertion order preserved),
     *         or [Result.failure] if the text is malformed or contains an invalid regex.
     */
    fun parseConfig(text: String): Result<Map<String, TreeNode>> = runCatching {
        val type = yamlMapper.typeFactory.constructMapType(
            LinkedHashMap::class.java, String::class.java, TreeNode::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val map = yamlMapper.readValue(text, type) as LinkedHashMap<String, TreeNode>
        map.values.forEach { validateNode(it) }
        map
    }

    private fun validateNode(node: TreeNode) {
        node.path.forEach { (pattern, child) ->
            if (pattern.isNotEmpty()) Regex(pattern)
            validateNode(child)
        }
        node.query.forEach { (pattern, child) ->
            if (pattern.isNotEmpty()) Regex(pattern)
            validateNode(child)
        }
        node.body.forEach { (pattern, child) ->
            Regex(pattern)
            validateNode(child)
        }
        node.headerIn.forEach { (_, patterns) ->
            patterns.forEach { (pattern, child) ->
                Regex(pattern)
                validateNode(child)
            }
        }
        node.method.values.forEach { validateNode(it) }
    }
}
