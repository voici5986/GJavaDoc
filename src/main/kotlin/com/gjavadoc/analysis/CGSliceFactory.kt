package com.gjavadoc.analysis

import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.project.Project

enum class AnalysisBackend { STUB, WALA }

object CGSliceFactory {
    fun create(project: Project): CGSliceBackend {
        val mode = try { AnalysisBackend.valueOf(SettingsState.getInstance(project).state.analysisBackend) } catch (_: Throwable) { AnalysisBackend.STUB }
        return when (mode) {
            AnalysisBackend.WALA -> WalaCGSliceBackend(project)
            AnalysisBackend.STUB -> StubCGSliceBackend(project)
        }
    }
}

