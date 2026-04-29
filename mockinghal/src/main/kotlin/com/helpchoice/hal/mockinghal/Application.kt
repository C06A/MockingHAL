package com.helpchoice.hal.mockinghal

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import java.io.File
import java.net.JarURLConnection

fun main() {
    embeddedServer(CIO, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    // Load all YAML files from the bundled /default classpath directory in
    // sorted order so the server is useful out of the box.
    // POST / replaces this at runtime; PATCH / appends to it.
    loadDefaultResources()

    routing {

        // ── Config loading ────────────────────────────────────────────────────────
        //
        // POST / replaces all loaded resources with the config supplied in the body.
        // Accepts:
        //   - a plain YAML or JSON body
        //   - multipart/form-data or multipart/mixed with one part per config block
        //     (parts are appended in order into a single resource map)
        post("/") {
            val contentType = call.request.contentType()
            val combined    = LinkedHashMap<String, TreeNode>()
            var parseError: String? = null

            if (contentType.match(ContentType.MultiPart.FormData) ||
                contentType.match(ContentType.MultiPart.Mixed)) {
                call.receiveMultipart().forEachPart { part ->
                    if (parseError != null) { part.dispose(); return@forEachPart }
                    val text = when (part) {
                        is PartData.FileItem -> part.provider().toByteArray()
                            .toString(Charsets.UTF_8).also { part.dispose() }
                        is PartData.FormItem -> part.value.also { part.dispose() }
                        else -> { part.dispose(); return@forEachPart }
                    }
                    ResourceRegistry.parseConfig(text)
                        .onSuccess { combined.putAll(it) }
                        .onFailure { parseError = it.message }
                }
            } else {
                ResourceRegistry.parseConfig(call.receiveText())
                    .onSuccess { combined.putAll(it) }
                    .onFailure { parseError = it.message }
            }

            if (parseError != null) {
                call.respondText("Invalid config: $parseError", status = HttpStatusCode.BadRequest)
                return@post
            }

            ResourceRegistry.replace(combined)
            call.respond(HttpStatusCode.Created)
        }

        // PATCH / appends the supplied config to the currently loaded resources.
        // Existing entries whose top-level key collides with a new entry are replaced;
        // all other entries are preserved.  Accepts the same content types as POST /.
        patch("/") {
            val contentType = call.request.contentType()
            val combined    = LinkedHashMap<String, TreeNode>()
            var parseError: String? = null

            if (contentType.match(ContentType.MultiPart.FormData) ||
                contentType.match(ContentType.MultiPart.Mixed)) {
                call.receiveMultipart().forEachPart { part ->
                    if (parseError != null) { part.dispose(); return@forEachPart }
                    val text = when (part) {
                        is PartData.FileItem -> part.provider().toByteArray()
                            .toString(Charsets.UTF_8).also { part.dispose() }
                        is PartData.FormItem -> part.value.also { part.dispose() }
                        else -> { part.dispose(); return@forEachPart }
                    }
                    ResourceRegistry.parseConfig(text)
                        .onSuccess { combined.putAll(it) }
                        .onFailure { parseError = it.message }
                }
            } else {
                ResourceRegistry.parseConfig(call.receiveText())
                    .onSuccess { combined.putAll(it) }
                    .onFailure { parseError = it.message }
            }

            if (parseError != null) {
                call.respondText("Invalid config: $parseError", status = HttpStatusCode.BadRequest)
                return@patch
            }

            ResourceRegistry.append(combined)
            call.respond(HttpStatusCode.OK)
        }

        // DELETE / resets to the built-in default configuration.
        delete("/") {
            ResourceRegistry.replace(emptyMap())
            loadDefaultResources()
            call.respond(HttpStatusCode.NoContent)
        }

        // ── Request matching ──────────────────────────────────────────────────────
        //
        // All other requests are matched against the loaded tree.
        // Declared after post("/") so that POST / always goes to the loader above.
        // Two catch-all routes are needed: "{...}" for multi-segment paths, "/" for root.
        route("{...}") {
            handle { handleMatch(call) }
        }
        route("/") {
            handle { handleMatch(call) }
        }
    }
}

/**
 * Loads every file found in the bundled `/default` classpath directory and
 * appends them to [ResourceRegistry] in alphabetical order.
 *
 * Works both when running from an exploded Gradle build (file: URLs) and
 * when packaged as a fat JAR (jar: URLs).
 */
private fun loadDefaultResources() {
    val dirName = "default"
    val dirUrl  = Application::class.java.getResource("/$dirName") ?: run {
        println("WARNING: classpath directory /$dirName not found — no defaults loaded")
        return
    }

    val fileNames: List<String> = when (dirUrl.protocol) {
        "file" -> File(dirUrl.toURI()).listFiles()
            ?.filter { it.isFile }
            ?.map    { it.name }
            ?.sorted()
            ?: emptyList()
        "jar"  -> (dirUrl.openConnection() as JarURLConnection).jarFile.use { jar ->
            jar.entries().toList()
                .filter { !it.isDirectory && it.name.startsWith("$dirName/") }
                .map    { it.name.removePrefix("$dirName/") }
                .filter { it.isNotEmpty() }
                .sorted()
        }
        else -> {
            println("WARNING: unsupported classpath protocol '${dirUrl.protocol}' for /$dirName")
            emptyList()
        }
    }

    for (name in fileNames) {
        Application::class.java.getResourceAsStream("/$dirName/$name")
            ?.bufferedReader()
            ?.readText()
            ?.let { text ->
                ResourceRegistry.parseConfig(text)
                    .onSuccess  { ResourceRegistry.append(it) }
                    .onFailure  { ex -> println("WARNING: failed to load $dirName/$name: ${ex.message}") }
            }
    }
}

private suspend fun handleMatch(call: ApplicationCall) {
    val req  = call.request
    val body = runCatching { call.receiveText() }.getOrDefault("")

    val match = RequestMatcher.findMatch(
        roots   = ResourceRegistry.getAll(),
        method  = req.httpMethod.value,
        path    = req.path(),
        query   = req.queryString(),
        headers = req.headers.entries()
            .associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") },
        body    = body,
    )

    if (match == null) {
        call.respondText(
            text        = """{"error": "Resource not found"}""",
            contentType = ContentType.Application.Json,
            status      = HttpStatusCode.NotFound,
        )
        return
    }

    match.headerOut.forEach { (k, v) -> call.response.headers.append(k, v) }

    val bodyText = when (val b = match.resource) {
        null      -> ""
        is String -> b
        else      -> ResourceRegistry.jsonMapper.writeValueAsString(b)
    }

    val ct = match.headerOut["Content-Type"]
        ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        ?: ContentType.Application.Json

    call.respondText(
        text        = bodyText,
        contentType = ct,
        status      = HttpStatusCode.fromValue(match.code),
    )
}
