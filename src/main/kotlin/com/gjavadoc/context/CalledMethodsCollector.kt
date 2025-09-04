package com.gjavadoc.context

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.impl.source.PsiJavaFileImpl

class CalledMethodsCollector(private val project: Project) {
    fun collect(root: PsiMethod, maxDepth: Int = 1): List<PsiMethod> {
        val visited = LinkedHashSet<String>()
        val result = LinkedHashSet<PsiMethod>()

        fun key(m: PsiMethod): String {
            val cls = m.containingClass?.qualifiedName ?: "Unknown"
            val sig = m.name + "(" + m.parameterList.parameters.joinToString(",") { it.type.presentableText } + ")"
            return "$cls#$sig"
        }

        fun walk(method: PsiMethod, depth: Int) {
            if (depth > maxDepth) return
            val k = key(method)
            if (!visited.add(k)) return
            result.add(method)
            val body = method.body ?: return
            body.accept(object : JavaRecursiveElementWalkingVisitor() {
                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    val target = expression.resolveMethod() ?: return
                    // Skip JDK & 3rd-party by file type heuristic
                    val vfile = target.containingFile?.virtualFile
                    if (vfile != null) {
                        val path = vfile.path
                        if (!path.contains(project.basePath ?: "")) return
                    }
                    walk(target, depth + 1)
                }
            })
        }

        walk(root, 1)
        result.remove(root) // exclude root method itself
        return result.toList()
    }
}

