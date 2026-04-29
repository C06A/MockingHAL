package com.helpchoice.hal.mockinghal

/**
 * A node in the request-matching tree.
 *
 * ## Filter elements
 * Filters constrain which requests can traverse deeper into the tree.
 * Any root→leaf path may contain at most one [method] filter and any number of
 * [path], [body], and [headerIn] filters (each nested level adds another AND condition).
 *
 * - [method]   maps HTTP method names (GET, POST, …) to child nodes.
 * - [path]     maps path-prefix regex patterns to child nodes.
 *              The matched prefix is stripped from the remaining path before recursing.
 *              Use an empty-string key to match when no path segment remains.
 * - [query]    maps query-string regex patterns (containment) to child nodes.
 *              Use an empty-string key to match when the query string is empty.
 * - [body]     maps body-regex patterns (containment) to child nodes.
 * - [headerIn] maps header names to (value-regex → child) maps.
 *              Multiple patterns under the same header name are tried in order (OR).
 *              Nest another [headerIn] inside a child to require an additional header (AND).
 *
 * ## Response elements
 * Response elements accumulate as the tree is traversed toward a leaf.
 * Any root→leaf path may contain at most one [code] and at most one [resource];
 * [headerOut] maps from all nodes along the path are merged.
 *
 * - [code]      HTTP status code.
 * - [resource]  Response body; any YAML value (map, list, string, number, …).
 * - [headerOut] Response headers merged from every node along the matched path.
 *
 * A node with no filter elements is a leaf: reaching it returns the accumulated response.
 */
data class TreeNode(
    // ── filter elements ───────────────────────────────────────────────────────
    val method:   Map<String, TreeNode>              = emptyMap(),
    val path:     Map<String, TreeNode>              = emptyMap(),
    val query:    Map<String, TreeNode>              = emptyMap(),
    val body:     Map<String, TreeNode>              = emptyMap(),
    val headerIn: Map<String, Map<String, TreeNode>> = emptyMap(),

    // ── response elements ─────────────────────────────────────────────────────
    val code:      Int?                = null,
    val resource:  Any?               = null,
    val headerOut: Map<String, String> = emptyMap(),
)

/** Response accumulated while descending the matching tree. */
data class MatchResult(
    val code:      Int                 = 200,
    val resource:  Any?               = null,
    val headerOut: Map<String, String> = emptyMap(),
)
