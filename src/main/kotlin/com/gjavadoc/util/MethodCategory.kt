package com.gjavadoc.util

enum class MethodCategory { CREATE, READ, UPDATE, DELETE, OTHER }

fun classifyMethodName(name: String): MethodCategory {
    val n = name.lowercase()
    val create = listOf("create", "add", "insert", "save", "new")
    val read = listOf("get", "query", "list", "find", "select", "count", "load")
    val update = listOf("update", "set", "modify", "patch")
    val delete = listOf("delete", "remove", "del")
    if (create.any { n.startsWith(it) }) return MethodCategory.CREATE
    if (read.any { n.startsWith(it) }) return MethodCategory.READ
    if (update.any { n.startsWith(it) }) return MethodCategory.UPDATE
    if (delete.any { n.startsWith(it) }) return MethodCategory.DELETE
    return MethodCategory.OTHER
}

