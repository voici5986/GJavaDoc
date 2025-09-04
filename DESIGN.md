# DESIGN.md — GJavaDoc（IDEA 插件）：基于调用链切片 + 本地 LLM 的接口文档生成器

> 目标：在遗留 Java 项目中，按“入口注解（默认 `@RpcService`）→ 调用链 + 相关代码切片 → 本地 LLM 生成结构化入参/出参文档”的流水线自动化完成文档产出，并提供**任务队列**与**可配置并发/速率**来保护本地 LLM 资源与保证 IDE 流畅度。

---

## 1. 范围与非目标
**范围**
- 在 IntelliJ IDEA 中：扫描入口方法（注解可配置），构建调用链，切片提取必要上下文，调用**本地** LLM 生成文档（JSON/Markdown），在 Tool Window 可视化并写入项目文件。
- 可观测性与控制：任务队列、进度/取消、并发与速率可调、失败重试、历史记录。

**非目标**
- 不内置运行被测服务；无需真正启动 Spring，只做静态分析为主（可后续扩展）。
- 不改动 vLLM/llama.cpp 的启动参数，仅在 UI 中给出调优提示。

---

## 2. 关键设计决策（TL;DR）
- **IDE 侧并发模型**：`RateLimiter`（RPS）+ `Bulkhead`（并发上限）+ 有界队列；后台任务用 `ProgressManager/Task.Backgroundable`，可取消、可汇报进度。  
- **配置与持久化**：使用 IntelliJ 的 `PersistentStateComponent` 存储设置（包含并发/速率/队列/超时/重试等）。  
- **线程与 PSI 安全**：读 PSI 用 **Read Action**；写文件/修改 PSI 用 **Write Action**。  
- **任务可观测性**：通过 IntelliJ **MessageBus** 广播任务生命周期事件，Tool Window 被动刷新。  
- **调用链与切片**：调用图由 **SootUp** 或 **WALA** 构建；切片使用 **WALA Slicer** 获取“最小必要代码集合”。  
- **LLM 侧提示**：在设置页提供 vLLM 的批量与并行参数提示（如 `max_num_seqs` / `max_num_batched_tokens`），仅做文档性建议。

---

## 3. 架构与模块

### 3.1 模块分层

1. **Settings & State（配置/状态）**
2. **Entry Scanner（入口扫描）**
3. **CG & Slice（调用图与切片）**
4. **Context Packager（上下文打包）**
5. **LLM Client（本地）**
6. **Task Queue（任务队列）**
7. **UI（Tool Window + Actions）**

> 备选：使用 `scip-java` 作为语义索引辅助。

---

## 4. 数据模型

### 4.1 TaskModel
```ts
type TaskStatus = "QUEUED" | "RUNNING" | "CANCELLED" | "FAILED" | "PARTIAL" | "SUCCEEDED";

interface EntryPoint {
  classFqn: string;
  method: string;
  file: string;
  line: number;
  annotation: string;
}

interface TaskModel {
  taskId: string;
  entry: EntryPoint;
  createdAt: number;
  status: TaskStatus;
  progress: { fraction: number; message?: string };
  cgSummary?: string;
  sliceAnchors?: Array<{file: string; lines: [number, number]}>;
  result?: { jsonPath?: string; mdPath?: string };
  error?: { type: string; message: string };
  attempt: number;
}

4.2 SettingsState

interface SettingsState {
  annotation: string;
  llmEndpoint: string;
  model: string;
  maxConcurrentRequests: number;
  requestsPerSecond: number;
  queueSize: number;
  requestTimeoutSec: number;
  retry: { enabled: boolean; maxAttempts: number; backoffMs: number };
  persist: { historyLimit: number };
}


⸻

5. 执行流程与线程模型

5.1 端到端流程

Entry Scanner -> TaskQueue.addAll -> QUEUED
QueueManager.start -> RateLimiter + Bulkhead -> Backgroundable Task {
  ReadAction (CG + Slice)
  LLM call (timeout/retry/cancel)
  WriteAction (write JSON/MD)
  Update Task Status + MessageBus
}

5.2 状态机

NEW -> QUEUED -> RUNNING -> SUCCEEDED / PARTIAL / FAILED / CANCELLED
FAILED/CANCELLED/PARTIAL -> (Retry) -> QUEUED


⸻

6. 调用图与切片参考
	•	调用图：SootUp（CHA/Spark）或 WALA
	•	切片：WALA Slicer

⸻

7. LLM 调度与提示
	•	IDE 调度：RateLimiter + Bulkhead 模式控制并发与速率
	•	vLLM 参数提示：如 max_num_seqs / max_num_batched_tokens 的作用说明

⸻

8. Prompt 结构与输出契约

Prompt 分段示例
	1.	方法签名 + 调用链摘要
	2.	参数切片
	3.	返回值切片
	4.	DTO/枚举定义
	5.	输出要求（JSON Schema）

JSON 输出结构

{
  "method": "...",
  "params": [...],
  "returns": [...],
  "notes": [...],
  "sources": [...]
}

输出文件
	•	method-docs/...json
	•	docs/...md

⸻

9. UI/UX 概述
	•	Tool Window：顶部控制栏（Run / Stop / 参数微调）+ 任务表 + 详情预览
	•	Settings：注解、LLM 配置、并发/速率/队列等项

⸻

10. 错误恢复 & 历史机制
	•	队列溢出提示
	•	重试策略（配置驱动）
	•	IDE 关闭后恢复 QUEUED 状态任务

⸻

11. 性能与扩展建议
	•	增量分析（PSI/VFS/Git diff）
	•	推荐配置样例
	•	可替换分析工具（scip-java, WALA, SootUp）

⸻

12. 开发拆解建议
	1.	实现 Settings
	2.	Entry Scanner
	3.	Task Queue 调度
	4.	CG + Slice 接入
	5.	LLM 调用接口
	6.	Tool Window 实现
	7.	输出（JSON/MD）功能
	8.	异常处理、历史持久化、压力测试

⸻

13. 测试方案建议
	•	单元测试配置与调度逻辑
	•	集成测试（Sandbox 插件）
	•	正确性验证与性能测试

⸻

14. 风险识别与缓解措施
	•	反射、动态代理的覆盖问题
	•	长调用链极端情况应控制切片范围
	•	LLM OOM / 卡顿提示用户调优

⸻

附录 A. 用户默认配置示例

annotation: "@RpcService"
llm:
  endpoint: "http://127.0.0.1:8000/v1/chat/completions"
  model: "Qwen-32B"
throttle:
  maxConcurrentRequests: 2
  requestsPerSecond: 1.5
  queueSize: 32
  requestTimeoutSec: 60
  retry:
    enabled: true
    maxAttempts: 2
    backoffMs: 500
persist:
  historyLimit: 200


⸻

附录 B. 参考资料
	•	IntelliJ 插件文档（持久化、后台任务、PSI、Tool Window、消息总线）
	•	Guava RateLimiter、Resilience4j Bulkhead
	•	WALA 切片与 SootUp 调用图
	•	vLLM 优化参数说明
