package com.gjavadoc.analysis

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project

class StubCGSliceBackend(private val project: Project) : CGSliceBackend {
    override fun analyze(entry: EntryPoint): CGSliceResult = ReadAction.compute<CGSliceResult, RuntimeException> {
        CGSliceResult(
            summary = "[stub] CG+Slice for ${entry.classFqn}#${entry.method}",
            anchors = listOf(SliceAnchor(entry.file, maxOf(1, entry.line - 2), entry.line + 2))
        )
    }
}

