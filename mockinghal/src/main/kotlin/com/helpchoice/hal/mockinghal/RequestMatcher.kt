package com.helpchoice.hal.mockinghal

object RequestMatcher {

    /**
     * Searches [roots] (name → [TreeNode], in insertion order) for the first root whose
     * tree yields a successful match, and returns the accumulated [MatchResult].
     */
    fun findMatch(
        roots:   Map<String, TreeNode>,
        method:  String,
        path:    String,
        query:   String,                // raw query string, without leading '?'
        headers: Map<String, String>,   // keys are already lowercased
        body:    String,
    ): MatchResult? = roots.values.firstNotNullOfOrNull { root ->
        root.match(method, path, query, headers, body, MatchResult())
    }

    /**
     * Recursively matches [this] node against the request, accumulating response elements.
     * Returns null if any filter along the current branch fails to match.
     *
     * ### Filter semantics (all AND-combined)
     * All filter elements present at a node are evaluated simultaneously as AND conditions.
     * A matched child is collected from each filter type, then all children are chained in
     * the fixed order: method → path → query → headerIn → body.
     * Each child's [MatchResult] becomes the [acc] for the next child in the chain.
     *
     * **Strict filters** — return null (no match) when their condition is not met:
     * - **method**: the request method must be a key in the map.
     * - **path**:   patterns tried in order; first that matches as an anchored prefix at a
     *               segment boundary is accepted; the matched prefix is stripped before
     *               recursing. An empty-string pattern matches only an empty remaining path.
     *
     * **Optional selectors** — when a pattern matches, its child is added to the chain
     * (overriding the current node's response elements); when no pattern matches the filter
     * is skipped and the node's own accumulated response elements serve as the default:
     * - **query**:    patterns tried in order (containment); first match accepted.
     *                 An empty-string pattern matches only an empty query string.
     * - **headerIn**: patterns for the named header tried in order (containment); first match
     *                 accepted. A missing header is treated as an empty string.
     * - **body**:     patterns tried in order (containment); first match accepted.
     *
     * ### Response element accumulation
     * Response elements ([TreeNode.code], [TreeNode.resource], [TreeNode.headerOut]) at each
     * node are accumulated before filters are evaluated.  Child values override parent values
     * for [MatchResult.code] and [MatchResult.resource]; [MatchResult.headerOut] maps are
     * merged (child keys override parent keys with the same name).
     *
     * A node with no filter elements is a leaf: reaching it returns the accumulated response.
     */
    private fun TreeNode.match(
        method:        String,
        remainingPath: String,
        query:         String,
        headers:       Map<String, String>,
        body:          String,
        acc:           MatchResult,
    ): MatchResult? {
        // Accumulate response elements from this node
        val newAcc = MatchResult(
            code      = this.code ?: acc.code,
            resource  = this.resource ?: acc.resource,
            headerOut = acc.headerOut + this.headerOut,
        )

        val hasFilters = this.method.isNotEmpty()   || this.path.isNotEmpty()  ||
                         this.query.isNotEmpty()    || this.body.isNotEmpty()   ||
                         this.headerIn.isNotEmpty()

        // Leaf: no filters → return what we have accumulated
        if (!hasFilters) return newAcc

        // Collect the matched child from every filter type present (AND semantics).
        // All filters must match; any mismatch returns null immediately.
        val children = mutableListOf<TreeNode>()
        var pathAfter = remainingPath

        // ── method ────────────────────────────────────────────────────────────
        if (this.method.isNotEmpty()) {
            val child = this.method[method] ?: return null
            children.add(child)
        }

        // ── path (prefix-regex, stripped) ─────────────────────────────────────
        if (this.path.isNotEmpty()) {
            var matched = false
            for ((pattern, child) in this.path) {
                // Empty pattern matches only an empty remaining path
                if (pattern.isEmpty()) {
                    if (pathAfter.isNotEmpty()) continue
                    children.add(child)
                    matched = true
                    break
                }
                val mr = Regex(pattern).find(pathAfter) ?: continue
                if (mr.range.first != 0) continue          // must be a prefix
                val after = pathAfter.drop(mr.value.length)
                if (after.isNotEmpty() && !after.startsWith('/')) continue  // segment boundary
                children.add(child)
                pathAfter = after
                matched = true
                break
            }
            if (!matched) return null
        }

        // ── query (optional selector) ─────────────────────────────────────────
        // When a pattern matches, its child is added to the chain (overriding the
        // current node's response elements).  When no pattern matches the filter is
        // skipped and the node's own accumulated response is used as the default.
        if (this.query.isNotEmpty()) {
            for ((pattern, child) in this.query) {
                // Empty pattern matches only an empty query string
                if (pattern.isEmpty()) {
                    if (query.isNotEmpty()) continue
                    children.add(child)
                    break
                }
                if (!Regex(pattern).containsMatchIn(query)) continue
                children.add(child)
                break
            }
        }

        // ── headerIn (optional selector) ──────────────────────────────────────
        // Same semantics as query: a matching pattern adds its child to the chain;
        // no match leaves the node's own response elements in effect.
        // Take the first (and recommended only) header entry.
        // For AND on multiple headers, nest headerIn inside a child node.
        //
        // The Accept header is negotiated by q-value rather than matched by plain
        // containment: a pattern only wins if the media type it matches is preferred
        // (higher q) over the node's own default Content-Type.  This prevents a lower-
        // priority Accept entry (e.g. application/hal+xml;q=0.9) from overriding the
        // higher-priority default (e.g. application/hal+json, q=1.0).
        if (this.headerIn.isNotEmpty()) {
            val (headerName, patterns) = this.headerIn.entries.first()
            val value = headers[headerName.lowercase()] ?: ""
            if (headerName.equals("Accept", ignoreCase = true)) {
                val defaultContentType = newAcc.headerOut.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                negotiateAccept(patterns, value, defaultContentType)?.let { children.add(it) }
            } else {
                for ((pattern, child) in patterns) {
                    if (!Regex(pattern).containsMatchIn(value)) continue
                    children.add(child)
                    break
                }
            }
        }

        // ── body (optional selector) ──────────────────────────────────────────
        // Same semantics as query and headerIn.
        if (this.body.isNotEmpty()) {
            for ((pattern, child) in this.body) {
                if (!Regex(pattern).containsMatchIn(body)) continue
                children.add(child)
                break
            }
        }

        // Chain through all matched children: each child's result becomes acc for the next.
        var result: MatchResult = newAcc
        for (child in children) {
            result = child.match(method, pathAfter, query, headers, body, result) ?: return null
        }
        return result
    }

    /** A single media range parsed from an Accept header, with its quality factor. */
    private data class MediaRange(val type: String, val q: Double)

    /**
     * Picks the [headerIn] child for an Accept header by HTTP content negotiation.
     *
     * Each candidate representation is scored by the quality factor (q) the client
     * assigns to its media type.  The node's own response (its [defaultContentType])
     * competes against every [patterns] entry; a pattern is selected only when the
     * media type it matches is *strictly* preferred over the default, so equal-q ties
     * fall to the default.  Returns null to keep the node's default response.
     */
    private fun negotiateAccept(
        patterns:           Map<String, TreeNode>,
        acceptValue:        String,
        defaultContentType: String?,
    ): TreeNode? {
        val ranges = parseAccept(acceptValue).filter { it.q > 0.0 }
        if (ranges.isEmpty()) return null   // no (acceptable) Accept → keep default

        // The default representation's score; -1 when the node declares no Content-Type,
        // so any matching pattern (q > 0) still wins, preserving plain-selector behaviour.
        val defaultScore = defaultContentType
            ?.let { ct -> ranges.filter { rangeMatchesType(it.type, ct) }.maxOfOrNull { it.q } ?: 0.0 }
            ?: -1.0

        var bestChild: TreeNode? = null
        var bestScore = defaultScore
        for ((pattern, child) in patterns) {
            val regex = Regex(pattern)
            val score = ranges.filter { regex.containsMatchIn(it.type) }.maxOfOrNull { it.q } ?: continue
            if (score > bestScore) {        // strictly greater: ties stay with the default
                bestScore = score
                bestChild = child
            }
        }
        return bestChild
    }

    /** Parses an Accept header value into its media ranges; absent q defaults to 1.0. */
    private fun parseAccept(value: String): List<MediaRange> =
        value.split(',').mapNotNull { part ->
            val segments = part.split(';').map { it.trim() }
            val type = segments.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val q = segments.drop(1)
                .firstOrNull { it.startsWith("q=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.toDoubleOrNull()
                ?: 1.0
            MediaRange(type, q)
        }

    /** True when an Accept media [range] (possibly wildcarded) matches a [contentType]. */
    private fun rangeMatchesType(range: String, contentType: String): Boolean {
        if (range == "*/*") return true
        val ct = contentType.substringBefore(';').trim().lowercase()
        val (rType, rSub) = range.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val (cType, cSub) = ct.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (rType != cType) return false
        return rSub == "*" || rSub == cSub
    }
}
