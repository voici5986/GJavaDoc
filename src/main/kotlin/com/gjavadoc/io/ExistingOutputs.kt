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

        fun collect(dir: File) {
            if (!dir.exists() || !dir.isDirectory) return
            dir.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                if (!name.endsWith(".md", true)) return@forEach // only docs/*.md
                val stem0 = name.removeSuffix(".md")
                val stem = trimTimestampSuffix(stem0)
                val us = stem.indexOf('_')
                if (us <= 0) {
                    // No underscore => treat as class-level doc (entire stem is classFqn)
                    classes.add(stem)
                    return@forEach
                }
                val cls = stem.substring(0, us)
                val methodPart = stem.substring(us + 1)
                if (methodPart.equals("CLASS", ignoreCase = true)) {
                    classes.add(cls)
                } else {
                    val base = methodPart.substringBefore('_')
                    if (cls.isNotBlank() && base.isNotBlank()) {
                        byName.add(cls to base)
                        // exact key is the full methodPart without trailing underscores (already trimmed by timestamp logic)
                        exact.add(cls to methodPart)
                    }
                }
            }
        }

        collect(docs)
        return Result(methodsExact = exact, methodsByName = byName, classSet = classes)
    }
}
