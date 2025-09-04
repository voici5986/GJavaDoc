package com.gjavadoc.util

import com.gjavadoc.settings.SettingsState

enum class MethodCategory { CREATE, READ, UPDATE, DELETE, OTHER }

/**
 * Legacy default classifier (kept for fallback/tests).
 */
fun classifyMethodName(name: String): MethodCategory =
    classifyWithPatterns(name, null)

/**
 * Classify using user-maintained prefixes; falls back to defaults when list is empty.
 */
fun classifyMethodName(name: String, patterns: SettingsState.CrudPatterns?): MethodCategory =
    classifyWithPatterns(name, patterns)

private fun classifyWithPatterns(name: String, p: SettingsState.CrudPatterns?): MethodCategory {
    val n = name.lowercase()
    val create = (p?.create?.takeIf { it.isNotEmpty() } ?: listOf("create", "add", "insert", "save", "new")).map { it.lowercase() }
    val read = (p?.read?.takeIf { it.isNotEmpty() } ?: listOf("get", "query", "list", "find", "select", "count", "load")).map { it.lowercase() }
    val update = (p?.update?.takeIf { it.isNotEmpty() } ?: listOf("update", "set", "modify", "patch", "enable", "disable")).map { it.lowercase() }
    val delete = (p?.delete?.takeIf { it.isNotEmpty() } ?: listOf("delete", "remove", "del", "clear")).map { it.lowercase() }
    if (create.any { n.startsWith(it) }) return MethodCategory.CREATE
    if (read.any { n.startsWith(it) }) return MethodCategory.READ
    if (update.any { n.startsWith(it) }) return MethodCategory.UPDATE
    if (delete.any { n.startsWith(it) }) return MethodCategory.DELETE
    return MethodCategory.OTHER
}
