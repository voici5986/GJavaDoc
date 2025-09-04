package com.gjavadoc.ui

import com.gjavadoc.bus.GJavaDocBusTopics
import com.gjavadoc.bus.TaskEventsListener
import com.gjavadoc.model.TaskModel
import com.gjavadoc.services.TaskRepository
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

class TaskTableModel(private val project: Project) : AbstractTableModel() {
    private val repo = project.getService(TaskRepository::class.java)

    private val cols = arrayOf("No.", "Entry", "Status", "Progress", "Message", "JSON", "Markdown")

    private val connection = project.messageBus.connect()
    private var allFiltered: List<TaskModel> = repo.all()
    private var paged: List<TaskModel> = allFiltered
    private var statusFilter: String = "ALL"
    private var textFilter: String = ""
    var pageSize: Int = 20
        private set
    var pageIndex: Int = 0
        private set
    var onDataChanged: (() -> Unit)? = null

    enum class SortBy { CREATED_AT, ENTRY, STATUS, PROGRESS }
    private var sortBy: SortBy = SortBy.CREATED_AT
    private var sortAsc: Boolean = false

    init {
        connection.subscribe(GJavaDocBusTopics.TASK_EVENTS, object : TaskEventsListener {
            override fun onEnqueued(task: TaskModel) = scheduleRefresh()
            override fun onStarted(task: TaskModel) = scheduleRefresh()
            override fun onUpdated(task: TaskModel) = scheduleRefresh()
            override fun onFinished(task: TaskModel) = scheduleRefresh()
        })
    }

    fun dispose() { connection.dispose() }

    fun setFilters(status: String, text: String) {
        statusFilter = status
        textFilter = text
        scheduleRefresh()
    }

    fun setSort(sort: SortBy, asc: Boolean) {
        if (sortBy != sort || sortAsc != asc) {
            sortBy = sort
            sortAsc = asc
            scheduleRefresh()
        }
    }

    fun setPageSize(size: Int) {
        val s = if (size < 1) 1 else size
        if (s != pageSize) {
            pageSize = s
            pageIndex = 0
            paginate()
        }
    }

    fun setPage(index: Int) {
        val total = totalPages()
        val idx = index.coerceIn(0, if (total == 0) 0 else total - 1)
        if (idx != pageIndex) {
            pageIndex = idx
            paginate()
        }
    }

    fun nextPage() = setPage(pageIndex + 1)
    fun prevPage() = setPage(pageIndex - 1)

    fun totalItems(): Int = allFiltered.size
    fun totalPages(): Int {
        val n = totalItems()
        return if (n == 0) 0 else (n + pageSize - 1) / pageSize
    }

    fun getItemAt(rowIndex: Int): TaskModel? = paged.getOrNull(rowIndex)

    @Volatile private var pending = false
    private fun scheduleRefresh() {
        if (pending) return
        pending = true
        SwingUtilities.invokeLater {
            try { refresh() } finally { pending = false }
        }
    }

    private fun refresh() {
        val all = repo.all()
        allFiltered = all.filter { matchStatus(it) && matchText(it) }
        allFiltered = sortAll(allFiltered)
        // clamp page index if needed
        val total = totalPages()
        if (pageIndex >= total && total > 0) pageIndex = total - 1
        if (total == 0) pageIndex = 0
        paginate()
    }

    private fun sortAll(list: List<TaskModel>): List<TaskModel> {
        val comparator = when (sortBy) {
            SortBy.CREATED_AT -> compareBy<TaskModel> { it.createdAt }
            SortBy.ENTRY -> compareBy<TaskModel> { (it.entry.classFqn + "#" + it.entry.method).lowercase() }
            SortBy.STATUS -> compareBy<TaskModel> { statusRank(it) }
            SortBy.PROGRESS -> compareBy<TaskModel> { it.progress.fraction }
        }
        val base = list.sortedWith(comparator)
        return if (sortAsc) base else base.asReversed()
    }

    private fun statusRank(t: TaskModel): Int {
        return when (t.status.name) {
            "QUEUED" -> 1
            "RUNNING" -> 2
            "PARTIAL" -> 3
            "SUCCEEDED" -> 4
            "FAILED" -> 5
            "CANCELLED" -> 6
            else -> 99
        }
    }

    private fun paginate() {
        val from = pageIndex * pageSize
        val to = kotlin.math.min(from + pageSize, allFiltered.size)
        paged = if (from in 0..to && to <= allFiltered.size) allFiltered.subList(from, to) else emptyList()
        fireTableDataChanged()
        onDataChanged?.invoke()
    }

    private fun matchStatus(t: TaskModel): Boolean = when (statusFilter) {
        "ALL" -> true
        else -> t.status.name == statusFilter
    }

    private fun matchText(t: TaskModel): Boolean {
        if (textFilter.isBlank()) return true
        val q = textFilter.lowercase()
        return (t.taskId.lowercase().contains(q)
                || t.entry.classFqn.lowercase().contains(q)
                || t.entry.method.lowercase().contains(q)
                || (t.progress.message?.lowercase()?.contains(q) ?: false)
                || (t.cgSummary?.lowercase()?.contains(q) ?: false))
    }

    override fun getRowCount(): Int = paged.size

    override fun getColumnCount(): Int = cols.size

    override fun getColumnName(column: Int): String = cols[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val t = paged[rowIndex]
        return when (columnIndex) {
            0 -> (pageIndex * pageSize + rowIndex + 1) // No.
            1 -> t.entry.classFqn + "#" + t.entry.method
            2 -> t.status.name
            3 -> String.format("%.0f%%", t.progress.fraction * 100)
            4 -> t.progress.message ?: ""
            5 -> t.result?.jsonPath ?: ""
            6 -> t.result?.mdPath ?: ""
            else -> ""
        }
    }
}
