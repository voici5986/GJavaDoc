package com.gjavadoc.io

import java.io.File

/**
 * Scans project output folders to determine which docs already exist,
 * so a subsequent scan can skip re-enqueuing them.
 */
object ExistingOutputs {
    data class Result(
        val methodsExact: Set<Pair<String, String>>, // (classFqn, methodSafeKey)
        val methodsByName: Set<Pair<String, String>>, // (classFqn, methodBaseName)
        val classSet: Set<String>, // classFqn
    )

    fun index(basePath: String?): Result {
        if (basePath.isNullOrBlank()) return Result(emptySet(), emptySet(), emptySet())
        val docs = File(basePath, "docs") // only docs/ is authoritative
        val exact = LinkedHashSet<Pair<String, String>>()
        val byName = LinkedHashSet<Pair<String, String>>()
        val classes = LinkedHashSet<String>()

        fun trimTimestampSuffix(stem: String): String {
            var s = stem
            var i = s.length - 1
            while (i >= 0 && s[i].isDigit()) i--
            var j = i
            while (j >= 0 && s[j] == '_') j--
            if (j < i) s = s.substring(0, j + 1)
            return s
        }

        fun collect(dir: File, extensions: Set<String>) {
            if (!dir.exists() || !dir.isDirectory) return
            dir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in extensions) return@forEach
                val stem0 = name.substring(0, name.length - ext.length - 1)
                val stem = trimTimestampSuffix(stem0)
                val us = stem.indexOf('_')
                if (us <= 0) {
                    // No underscore => treat as class-level doc (requires plausible FQN)
                    if (stem.contains('.')) classes.add(stem)
                    return@forEach
                }
                val cls = stem.substring(0, us)
                if (cls.isBlank() || !cls.contains('.')) return@forEach
                val methodPart = stem.substring(us + 1)
                if (methodPart.equals("CLASS", ignoreCase = true)) {
                    classes.add(cls)
                } else {
                    val base = methodPart.substringBefore('_')
                    if (base.isBlank()) return@forEach
                    byName.add(cls to base)
                    // exact key is the full methodPart without trailing underscores (already trimmed by timestamp logic)
                    exact.add(cls to methodPart)
                }
            }
        }

        collect(docs, setOf("md", "doc"))
        collect(File(basePath, "md"), setOf("md"))
        return Result(methodsExact = exact, methodsByName = byName, classSet = classes)
    }
}
