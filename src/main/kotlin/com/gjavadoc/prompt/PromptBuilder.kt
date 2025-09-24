package com.gjavadoc.prompt

import com.gjavadoc.model.EntryPoint
import com.gjavadoc.settings.SettingsState
import com.gjavadoc.util.MethodCategory
import com.gjavadoc.util.classifyMethodName
import com.intellij.openapi.project.Project

object PromptBuilder {
    fun build(project: Project, entry: EntryPoint, context: String): String {
        val s = SettingsState.getInstance(project).state
        if (s.customPromptEnabled && s.customPrompt.isNotBlank()) {
            return renderCustomTemplate(project, s.customPrompt, entry, context)
        }
        return buildDefault(project, entry, context)
    }

    fun javaReviewTemplate(): String = buildString {
        appendLine("你现在是“Java 代码走查”评审专家，负责严格审阅给定的 Java 代码变更。")
        appendLine("请聚焦真实缺陷（准确性、性能、安全性、可维护性），忽略纯风格问题。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("评审准则")
        appendLine()
        appendLine("1. 仅在问题明确由本次改动引入且可行动时报告；不要假设作者意图。")
        appendLine("2. 优先检查：空指针风险、集合/并发误用、资源关闭、异常处理、事务/JPA 语义、性能退化。")
        appendLine("3. 发现问题时写出触发路径或示例输入，说明影响面与前置条件。")
        appendLine("4. 不要混合多个缺陷；每条记录聚焦单一问题。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("输出格式")
        appendLine()
        appendLine("请返回 JSON（UTF-8，无额外注释）：")
        appendLine("{")
        appendLine("  \"findings\": [")
        appendLine("    {")
        appendLine("      \"title\": \"[Px] 简短命令式标题\",")
        appendLine("      \"body\": \"使用 Markdown 描述缺陷原因、触发条件和影响，可引用 `类#方法` 或 1-3 行代码。\",")
        appendLine("      \"confidence_score\": 0.0,")
        appendLine("      \"priority\": 0,")
        appendLine("      \"code_location\": { \"absolute_file_path\": \"路径\", \"line_range\": { \"start\": 0, \"end\": 0 } }")
        appendLine("    }")
        appendLine("  ],")
        appendLine("  \"overall_correctness\": \"patch is correct\",")
        appendLine("  \"overall_explanation\": \"一句总结整体评估理由\",")
        appendLine("  \"overall_confidence_score\": 0.0")
        appendLine("}")
        appendLine()
        appendLine("请在实际输出中：")
        appendLine("- 使用 P0~P3 设置标题前缀，并将 priority 映射为 0~3。")
        appendLine("- 用 0.0~1.0 填写 confidence_score，以反映确信度。")
        appendLine("- 精确填写 code_location 的路径与行号。")
        appendLine("- 若没有缺陷，findings 应为空数组，同时在 overall_explanation 中说明剩余风险或关注点。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("Java 代码粘贴区：")
        appendLine("```Java")
        appendLine("${'$'}{CONTEXT}")
        appendLine("```")
    }

    fun defaultTemplate(): String = buildString {
        appendLine("你现在是“Java 接口文档生成器”。")
        appendLine("请将 Service 的每个 public 方法视为对外接口，按以下规则输出接口文档。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("任务")
        appendLine()
        appendLine("阅读下面的 Java 代码片段，对每个 public 方法生成接口文档，严格输出以下三部分：")
        appendLine("\t1. 接口名称/说明/请求方式（元信息）")
        appendLine("\t2. 输入参数表（含 qc.*、Map 展开、DTO 字段展开）")
        appendLine("\t3. 输出参数表（直接列字段，含包装类、VO、Map 展开）")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("解析规则")
        appendLine()
        appendLine("1. 接口基础信息")
        appendLine("\t• 接口名称：类名.方法名。")
        appendLine("\t• 接口说明：若注释缺失，则基于方法名/领域词语义给一句话。")
        appendLine("\t• 请求方式：按方法名推断：")
        appendLine("\t\t• GET：find/get/query/select/list/page/detail/count")
        appendLine("\t\t• POST：create/add/save/submit/import/export")
        appendLine("\t\t• PUT：update/modify/enable/disable/audit/approve")
        appendLine("\t\t• DELETE：delete/remove/clear")
        appendLine("\t\t• 不确定 → POST")
        appendLine()
        appendLine("2. 输入参数表")
        appendLine("\t• 普通参数：形参直接列出。")
        appendLine("\t• DTO/VO：展开一层字段，格式：obj.field。")
        appendLine("\t• Map/JSONObject：展开键，推断类型；若键不固定，用 map.* 行表示动态键。")
        appendLine("\t• QueryCondition (qc)：必须展开字段，字段名前加 qc.。来源包含 qc.get(\"xxx\")/getOrDefault/containsKey、SQL、Mapper、判空分支。")
        appendLine("\t• 类型：按转换/命名规则推断（id→String、*Date→String(yyyy-MM-dd)、*Count→Integer）。")
        appendLine("\t• 默认值：在说明列出（例如 qc.pageNo 默认 1）。")
        appendLine()
        appendLine("3. 输出参数表")
        appendLine("\t• 返回包装类：如 Result<T>，展开公共字段（code/message/success/timestamp）+ T。")
        appendLine("\t• 分页结构：列出 total/pageNo/pageSize/records[]。")
        appendLine("\t• VO/DTO：展开一层主要字段。")
        appendLine("\t• Map/JSONObject：展开键，推断类型与含义。")
        appendLine("\t• 说明：写明字段的主要业务含义，不要只写“见 XXX”。")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("输出格式（每个方法独立；允许 Markdown 混合 HTML；不要使用代码块围栏）")
        appendLine("## 标题")
        appendLine()
        appendLine("<!-- 统一大表：元信息 + 参数分组（去掉“参数类型/必输”两列） -->")
        appendLine("<table style=\"width:100%;border-collapse:collapse;\">")
        appendLine("  <tbody>")
        appendLine("    <tr>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;text-align:left;white-space:nowrap;\">接口名称</th>")
        appendLine("      <td colspan=\"2\" style=\"border:1px solid #ccc;padding:6px;\">类名.方法名</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;text-align:left;white-space:nowrap;\">接口说明</th>")
        appendLine("      <td colspan=\"2\" style=\"border:1px solid #ccc;padding:6px;\">一句话描述</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;text-align:left;white-space:nowrap;\">请求方式</th>")
        appendLine("      <td colspan=\"2\" style=\"border:1px solid #ccc;padding:6px;\">GET/POST/PUT/DELETE</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;\">参数</th>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;\">参数名称</th>")
        appendLine("      <th style=\"border:1px solid #ccc;padding:6px;\">说明</th>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <td colspan=\"3\" style=\"border:1px solid #ccc;padding:6px;background:#f2f2f2;font-weight:bold;\">输入</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">qc.pageNo</td>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">页码</td>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">默认 1；若不存在按 1 处理</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <td colspan=\"3\" style=\"border:1px solid #ccc;padding:6px;background:#f2f2f2;font-weight:bold;\">输出[0..n]</td>")
        appendLine("    </tr>")
        appendLine("    <tr>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">records[].id</td>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">记录ID</td>")
        appendLine("      <td style=\"border:1px solid #ccc;padding:6px;\">主键</td>")
        appendLine("    </tr>")
        appendLine("  </tbody>")
        appendLine("</table>")
        appendLine()
        appendLine("⸻")
        appendLine()
        appendLine("Java 代码粘贴区")
        appendLine()
        appendLine("```java")
        appendLine("${'$'}{CONTEXT}")
        appendLine("```")
        appendLine()
        appendLine("注意：")
        appendLine("- 严格按上面的“输出格式”输出，表格请使用 HTML 的 <table> 元素（可用 <thead>/<tbody>、colspan 控制分组行）；不要输出 JSON，也不要使用围栏包裹最终结果。")
        appendLine("- 只根据粘贴区内的代码做推断，不要编造。")
        // appendLine("- 若某字段类型或含义不确定，请给出合理推断并在说明列写明依据（如：来源于判空/默认值/命名推断）。")
    }

    private fun buildDefault(project: Project, entry: EntryPoint, context: String): String {
        // Keep previous default behavior
        val methodBase = entry.method.substringBefore('(')
        val cat = classifyMethodName(methodBase, SettingsState.getInstance(project).state.crudPatterns)
        val http = when (cat) {
            MethodCategory.CREATE -> "POST"
            MethodCategory.READ -> "GET"
            MethodCategory.UPDATE -> "PUT"
            MethodCategory.DELETE -> "DELETE"
            MethodCategory.OTHER -> "POST"
        }
        return defaultTemplate().replace("${'$'}{CONTEXT}", context)
    }

    private fun renderCustomTemplate(project: Project, tpl: String, entry: EntryPoint, context: String): String {
        val methodBase = entry.method.substringBefore('(')
        val http = when (classifyMethodName(methodBase, SettingsState.getInstance(project).state.crudPatterns)) {
            MethodCategory.CREATE -> "POST"
            MethodCategory.READ -> "GET"
            MethodCategory.UPDATE -> "PUT"
            MethodCategory.DELETE -> "DELETE"
            MethodCategory.OTHER -> "POST"
        }
        return tpl
            .replace("${'$'}{ENTRY_CLASS_FQN}", entry.classFqn)
            .replace("${'$'}{ENTRY_METHOD}", entry.method)
            .replace("${'$'}{ENTRY_METHOD_BASE}", methodBase)
            .replace("${'$'}{HTTP_METHOD}", http)
            .replace("${'$'}{CONTEXT}", context)
    }
}
