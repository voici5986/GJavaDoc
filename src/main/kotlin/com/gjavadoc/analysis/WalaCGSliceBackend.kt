package com.gjavadoc.analysis

import com.gjavadoc.model.EntryPoint
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.module.ModuleManager
import java.io.File

/**
 * Best-effort WALA integration. Requires WALA jars reachable at runtime.
 * Falls back to a minimal summary if something goes wrong.
 */
class WalaCGSliceBackend(private val project: Project) : CGSliceBackend {
    private val log = Logger.getInstance(WalaCGSliceBackend::class.java)

    override fun analyze(entry: EntryPoint): CGSliceResult {
        return try {
            val classpath = collectClasspath()
            val summary = buildCallGraphSummary(entry, classpath)
            // Mapping WALA statements to source ranges is non-trivial; anchor to entry site for now.
            CGSliceResult(
                summary = summary,
                anchors = listOf(SliceAnchor(entry.file, maxOf(1, entry.line - 2), entry.line + 2))
            )
        } catch (t: Throwable) {
            log.warn("WALA analysis failed; falling back to stub summary", t)
            CGSliceResult(
                summary = "[wala-error] ${t::class.java.simpleName}: ${t.message}",
                anchors = listOf(SliceAnchor(entry.file, maxOf(1, entry.line - 2), entry.line + 2))
            )
        }
    }

    private fun collectClasspath(): List<File> {
        val files = mutableListOf<File>()
        val mm = ModuleManager.getInstance(project)
        for (module in mm.modules) {
            val cme = CompilerModuleExtension.getInstance(module)
            val out = cme?.compilerOutputPath
            if (out != null) files += File(out.path)
            // Also add dependencies (libraries) to widen resolution if available
            val orderEntries = ModuleRootManager.getInstance(module).orderEntries().librariesOnly().classes().roots
            for (vf in orderEntries) {
                files += File(vf.path)
            }
        }
        return files.filter { it.exists() }
    }

    private fun buildCallGraphSummary(entry: EntryPoint, classpath: List<File>): String {
        // Reflection-based invocation to avoid compile-time dependency in case WALA jars are missing
        val AnalysisScopeReader = Class.forName("com.ibm.wala.core.util.config.AnalysisScopeReader")
        // WALA 1.6.0 将 AnalysisScope 移至 com.ibm.wala.ipa.callgraph.AnalysisScope
        val AnalysisScope = try {
            Class.forName("com.ibm.wala.ipa.callgraph.AnalysisScope")
        } catch (_: ClassNotFoundException) {
            Class.forName("com.ibm.wala.classLoader.AnalysisScope")
        }
        val ClassHierarchyFactory = Class.forName("com.ibm.wala.ipa.cha.ClassHierarchyFactory")
        val AnalysisOptions = Class.forName("com.ibm.wala.ipa.callgraph.AnalysisOptions")
        val AnalysisCacheImpl = Class.forName("com.ibm.wala.ipa.callgraph.impl.AnalysisCacheImpl")
        val Util = Class.forName("com.ibm.wala.ipa.callgraph.impl.Util")
        val CallGraphBuilder = Class.forName("com.ibm.wala.ipa.callgraph.CallGraphBuilder")
        val CallGraph = Class.forName("com.ibm.wala.ipa.callgraph.CallGraph")
        val PrettyPrinter = Class.forName("com.ibm.wala.util.graph.Graph") // used for counts only

        // scope with exclusions (optional): use default scope and add application entries
        val exclusionsUrl = this::class.java.classLoader.getResource("wala-exclusions.txt")
        val makeScope = AnalysisScopeReader.getMethod("makeJavaBinaryAnalysisScope", String::class.java, java.io.InputStream::class.java)
        // create a temp dummy file list by joining classpaths with path separator; WALA accepts jar or dir
        val appPath = classpath.joinToString(File.pathSeparator) { it.absolutePath }
        val scope = makeScope.invoke(null, appPath, exclusionsUrl?.openStream())

        val cha = ClassHierarchyFactory.getMethod("make", AnalysisScope).invoke(null, scope)

        // Set entrypoints: we approximate by using all public methods as entrypoints for breadth
        val addDefaultSelectors = Util.getMethod("addDefaultSelectors", AnalysisOptions, Class.forName("com.ibm.wala.ipa.cha.IClassHierarchy"))
        val addDefaultBypass = Util.getMethod("addDefaultBypassLogic", AnalysisOptions, ClassLoader::class.java, Class.forName("com.ibm.wala.ipa.cha.IClassHierarchy"))

        val options = AnalysisOptions.getConstructor(AnalysisScope).newInstance(scope)
        addDefaultSelectors.invoke(null, options, cha)
        addDefaultBypass.invoke(null, options, this::class.java.classLoader, cha)

        val cache = AnalysisCacheImpl.getConstructor().newInstance()
        val makeBuilder = Util.getMethod("makeZeroCFABuilder", Class.forName("com.ibm.wala.ipa.callgraph.AnalysisOptions"), cache::class.java, Class.forName("com.ibm.wala.ipa.cha.IClassHierarchy"), AnalysisScope)
        val builder = makeBuilder.invoke(null, options, cache, cha, scope)

        val makeCG = builder.javaClass.getMethod("makeCallGraph", AnalysisOptions)
        val cg = makeCG.invoke(builder, options)

        // Count nodes reachable from any entry
        val numNodes = (cg as java.lang.Object).javaClass.getMethod("getNumberOfNodes").invoke(cg) as Int
        return "[wala] nodes=$numNodes, classpath=${classpath.size}"
    }
}
