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

data class CorsConfig(
    val origins: List<String> = emptyList(),
    val methods: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"),
    val headers: List<String> = listOf("Content-Type", "Authorization", "Accept"),
    val allowCredentials: Boolean = false,
    val maxAgeSeconds: Long = 3600,
)

/** Default listen port, used when no override is configured. */
const val DEFAULT_PORT = 8080

/**
 * Resolves the listen port, in order of precedence:
 *   1. `MOCKINGHAL_PORT` environment variable
 *   2. `PORT` environment variable (PaaS convention, e.g. Heroku)
 *   3. `mockinghal.port` JVM system property
 *   4. [DEFAULT_PORT] (8080)
 *
 * A non-numeric or out-of-range (1..65535) value logs a warning and falls back to the default.
 */
fun resolvePort(
    mockinghalPort: String? = System.getenv("MOCKINGHAL_PORT"),
    portEnv:        String? = System.getenv("PORT"),
    sysProp:        String? = System.getProperty("mockinghal.port"),
): Int {
    val raw = mockinghalPort ?: portEnv ?: sysProp ?: return DEFAULT_PORT
    val port = raw.trim().toIntOrNull()
    if (port == null || port !in 1..65535) {
        println("WARNING: invalid port '$raw' — using default $DEFAULT_PORT")
        return DEFAULT_PORT
    }
    return port
}

fun main() {
    embeddedServer(CIO, port = resolvePort(), module = Application::module).start(wait = true)
}

fun Application.module() {
    val corsConfig = loadCorsConfig()
    if (corsConfig.origins.isNotEmpty()) {
        val originPatterns = corsConfig.origins.map { wildcardOriginToRegex(it) }
        intercept(ApplicationCallPipeline.Plugins) {
            val requestOrigin = call.request.headers[HttpHeaders.Origin] ?: return@intercept
            val matchedOrigin = corsConfig.origins.zip(originPatterns)
                .firstOrNull { (_, pattern) -> pattern.matches(requestOrigin) }
                ?.let { (origin, _) -> if (origin == "*") "*" else requestOrigin }
                ?: return@intercept

            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, matchedOrigin)
            if (corsConfig.allowCredentials) {
                call.response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
            }
            if (matchedOrigin != "*") {
                call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
            }

            if (call.request.httpMethod == HttpMethod.Options &&
                call.request.headers.contains(HttpHeaders.AccessControlRequestMethod)) {
                call.response.headers.append(
                    HttpHeaders.AccessControlAllowMethods,
                    corsConfig.methods.joinToString(", ")
                )
                call.response.headers.append(
                    HttpHeaders.AccessControlAllowHeaders,
                    corsConfig.headers.joinToString(", ")
                )
                call.response.headers.append(
                    HttpHeaders.AccessControlMaxAge,
                    corsConfig.maxAgeSeconds.toString()
                )
                call.respond(HttpStatusCode.NoContent)
                finish()
            }
        }
    }

    // Load all YAML files from the bundled /default classpath directory in
    // sorted order so the server is useful out of the box.
    // POST / replaces this at runtime; PATCH / appends to it.
    loadDefaultResources()

    // When the loaded configuration itself defines handlers for POST /, PATCH /,
    // and DELETE / (all three), the built-in runtime config-management endpoints
    // are disabled so those requests are served by the config instead of being
    // interpreted as replace/append/reset operations. Evaluated once at startup.
    val configOwnsRootMutations = listOf("POST", "PATCH", "DELETE").all { method ->
        RequestMatcher.findMatch(ResourceRegistry.getAll(), method, "/", "", emptyMap(), "") != null
    }
    if (configOwnsRootMutations) {
        println("INFO: config defines POST/PATCH/DELETE / — runtime config replacement is disabled")
    }

    routing {

        // ── Config loading ────────────────────────────────────────────────────────
        //
        // POST / replaces all loaded resources with the config supplied in the body.
        // Accepts:
        //   - a plain YAML or JSON body
        //   - multipart/form-data or multipart/mixed with one part per config block
        //     (parts are appended in order into a single resource map)
        //
        // These three endpoints are skipped entirely when [configOwnsRootMutations]
        // is set, letting the catch-all routes below serve those methods from config.
        if (!configOwnsRootMutations) {
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
        }

        // ── Request matching ──────────────────────────────────────────────────────
        //
        // All other requests are matched against the loaded tree. Declared after the
        // config-management routes so that, when those are registered, POST/PATCH/DELETE /
        // reach the loader above; when they are skipped (configOwnsRootMutations), those
        // methods fall through here and are served from config.
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
 * Converts an origin pattern with optional wildcards into a [Regex].
 *
 * `*` in the host matches a single domain label (no dots), e.g. `*.example.com`
 * matches `sub.example.com` but not `sub.sub.example.com`.
 * `*` as the port matches any port number, e.g. `http://localhost:*`.
 * A bare `*` matches any origin.
 */
internal fun wildcardOriginToRegex(pattern: String): Regex {
    if (pattern == "*") return Regex(".*")
    val schemeDelim = pattern.indexOf("://")
    val scheme    = if (schemeDelim >= 0) pattern.substring(0, schemeDelim) else ""
    val authority = if (schemeDelim >= 0) pattern.substring(schemeDelim + 3) else pattern
    // Split host and port; lastIndexOf(']') guards against IPv6 addresses like [::1]:8080
    val portIdx = authority.lastIndexOf(':')
    val hasPort = portIdx > authority.lastIndexOf(']')
    val host = if (hasPort) authority.substring(0, portIdx) else authority
    val port = if (hasPort) authority.substring(portIdx + 1) else ""
    fun String.toSegmentRegex() = split('*').joinToString("[^.]+") { Regex.escape(it) }
    val schemePart = if (scheme.isNotEmpty()) "${Regex.escape(scheme)}://" else ""
    val portPart   = when {
        port.isEmpty() -> ""
        port == "*"    -> ":\\d+"
        else           -> ":${Regex.escape(port)}"
    }
    return Regex("^$schemePart${host.toSegmentRegex()}$portPart$")
}

/**
 * Loads the CORS configuration from (in order) the file named by the
 * `MOCKINGHAL_CORS` environment variable, the bundled `/cors.yaml` resource, or
 * the [CorsConfig] defaults. Parsed with the shared [ResourceRegistry.yamlMapper].
 *
 * ## Why a hand-rolled interceptor instead of Ktor's `CORS` plugin
 *
 * This is a deliberately lightweight, permissive header-stamper tuned for a mock
 * server — not a spec-enforcing gate. It differs from `io.ktor:ktor-server-cors`:
 *
 * - **Config source** — read from an external YAML file (env var or bundled
 *   resource), so origins can change without a recompile. The Ktor plugin is
 *   configured in code via `install(CORS){…}` and is fixed at build time.
 * - **Origin matching** — custom wildcard syntax compiled by
 *   [wildcardOriginToRegex] (`*` host label, `*` port); the exact request origin
 *   is echoed back (or literal `*`). Ktor uses `allowHost(...)`/`anyHost()` with
 *   explicit subdomain/scheme lists and no port-wildcard equivalent.
 * - **Preflight** — handled manually here (`OPTIONS` + `Access-Control-Request-Method`
 *   → emit Allow-Methods/Headers/Max-Age → 204). The Ktor plugin owns preflight
 *   automatically.
 * - **Validation strictness** — this code only *adds* headers when the origin
 *   matches; it never rejects a request for a disallowed method/header, and the
 *   preflight always advertises the full configured lists. The Ktor plugin
 *   *enforces* the allowlists and rejects violations (403). Permissiveness is
 *   intentional so CORS never blocks test traffic.
 * - **Spec corners not covered here** — no `Access-Control-Expose-Headers`, and no
 *   guard against the invalid `allowCredentials: true` + `origins: ["*"]` combo
 *   (the README warns; the code does not). The Ktor plugin handles both.
 */
private fun loadCorsConfig(): CorsConfig {
    val text = System.getenv("MOCKINGHAL_CORS")
        ?.let { path ->
            val f = File(path)
            if (f.exists()) f.readText()
            else { println("WARNING: MOCKINGHAL_CORS file not found: $path — using bundled cors.yaml"); null }
        }
        ?: Application::class.java.getResourceAsStream("/cors.yaml")?.bufferedReader()?.readText()
        ?: return CorsConfig()
    return ResourceRegistry.yamlMapper.readValue(text, CorsConfig::class.java)
}

/**
 * Loads the startup configuration into [ResourceRegistry]. This is also what
 * `DELETE /` resets to.
 *
 * When the `MOCKINGHAL_CONFIG` environment variable is set, its value is a
 * [File.pathSeparator]-delimited list of files and/or directories loaded in
 * place of the bundled defaults (directories are loaded alphabetically). This
 * lets the server start from external configs without an initial `POST /`.
 * If nothing loadable is found there, the bundled defaults are used instead so
 * the server still boots.
 *
 * Otherwise the bundled `/default` classpath directory is loaded.
 */
private fun loadDefaultResources() {
    val external = System.getenv("MOCKINGHAL_CONFIG")?.takeIf { it.isNotBlank() }
    if (external != null && loadExternalResources(external)) return
    loadBundledResources()
}

/**
 * Loads config from a [File.pathSeparator]-delimited [spec] of files and/or
 * directories, appending each to [ResourceRegistry] in order (directory entries
 * alphabetically). Returns true when at least one config was loaded successfully.
 */
private fun loadExternalResources(spec: String): Boolean {
    val files: List<File> = spec.split(File.pathSeparatorChar)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { path ->
            val f = File(path)
            when {
                f.isDirectory -> f.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                f.isFile      -> listOf(f)
                else          -> { println("WARNING: MOCKINGHAL_CONFIG path not found: $path"); emptyList() }
            }
        }

    var loaded = false
    for (file in files) {
        ResourceRegistry.parseConfig(file.readText())
            .onSuccess { ResourceRegistry.append(it); loaded = true }
            .onFailure { ex -> println("WARNING: failed to load ${file.path}: ${ex.message}") }
    }
    if (!loaded) println("WARNING: MOCKINGHAL_CONFIG loaded no resources — falling back to bundled defaults")
    return loaded
}

/**
 * Loads every file found in the bundled `/default` classpath directory and
 * appends them to [ResourceRegistry] in alphabetical order.
 *
 * Works both when running from an exploded Gradle build (file: URLs) and
 * when packaged as a fat JAR (jar: URLs).
 */
private fun loadBundledResources() {
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
