package com.gjavadoc.scan

import com.gjavadoc.model.EntryPoint
import com.gjavadoc.settings.SettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

class EntryScanner(private val project: Project) {

    fun scan(scope: GlobalSearchScope? = null): List<EntryPoint> {
        val settings = SettingsState.getInstance(project).state
        val targets: List<String> = settings.annotation
            .split(',', ';', '\n', '\t', ' ')
            .map { it.trim().trimStart('@') }
            .filter { it.isNotEmpty() }
        val sc = scope ?: GlobalSearchScope.projectScope(project)
        val result = mutableListOf<EntryPoint>()
        val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", sc)
        val psiManager = PsiManager.getInstance(project)

        for (vf in javaFiles) {
            val psi = psiManager.findFile(vf) as? PsiJavaFile ?: continue
            for (cls in psi.classes) {
                val classTagged = hasAnyAnnotation(cls.modifierList, targets)
                for (method in cls.methods) {
                    val methodTagged = hasAnyAnnotation(method.modifierList, targets)
                    val match = if (settings.perClassDocument) {
                        classTagged || methodTagged
                    } else {
                        methodTagged
                    }
                    if (!match) continue
                    val ep = entryPointFor(vf, cls, method, settings.annotation)
                    result.add(ep)
                }
            }
        }
        return result
    }

    private fun hasAnyAnnotation(modifierList: PsiModifierList?, targets: List<String>): Boolean {
        if (modifierList == null) return false
        for (ann in modifierList.annotations) {
            val qn = ann.qualifiedName ?: ""
            val sn = ann.nameReferenceElement?.referenceName ?: ""
            for (t in targets) {
                if (qn.endsWith(".$t") || sn == t || qn == t) return true
            }
        }
        return false
    }

    private fun entryPointFor(vf: VirtualFile, cls: PsiClass, method: PsiMethod, rawAnnotation: String): EntryPoint {
        val doc = PsiDocumentManager.getInstance(project).getDocument(method.containingFile)
        val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 1
        val signature = buildString {
            append(method.name)
            append('(')
            append(method.parameterList.parameters.joinToString(",") { it.type.presentableText })
            append(')')
        }
        val classFqn = cls.qualifiedName ?: cls.name ?: "UnknownClass"
        return EntryPoint(
            classFqn = classFqn,
            method = signature,
            file = vf.path,
            line = line,
            annotation = rawAnnotation,
        )
    }
}
