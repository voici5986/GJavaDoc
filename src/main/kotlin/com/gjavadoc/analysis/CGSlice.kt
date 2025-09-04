package com.gjavadoc.analysis

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.project.Project

data class SliceAnchor(val file: String, val startLine: Int, val endLine: Int)

data class CGSliceResult(
    val summary: String,
    val anchors: List<SliceAnchor>,
)

interface CGSliceBackend {
    fun analyze(entry: EntryPoint): CGSliceResult
}

