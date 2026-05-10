package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.ExternalAnchorRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskDependencyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit coverage for the parse/materialise split introduced for the
 * project upload preview feature. The split is the load-bearing
 * refactor — the preview ViewModel calls [ProjectImporter.parse]
 * (read-only) and [ProjectImporter.materialise] (writes to Room)
 * separately, and the per-section [ImportExclusions] honour the
 * user's opt-out checkboxes from the preview screen.
 */
class ProjectImporterTest {
    private lateinit var projectRepository: ProjectRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var externalAnchorRepository: ExternalAnchorRepository
    private lateinit var taskDependencyRepository: TaskDependencyRepository
    private lateinit var checklistParser: ChecklistParser
    private lateinit var todoListParser: TodoListParser
    private lateinit var importer: ProjectImporter

    @Before
    fun setUp() {
        projectRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        externalAnchorRepository = mockk(relaxed = true)
        taskDependencyRepository = mockk(relaxed = true)
        checklistParser = mockk(relaxed = true)
        todoListParser = mockk(relaxed = true)
        importer = ProjectImporter(
            projectRepository = projectRepository,
            taskRepository = taskRepository,
            externalAnchorRepository = externalAnchorRepository,
            taskDependencyRepository = taskDependencyRepository,
            checklistParser = checklistParser,
            todoListParser = todoListParser
        )
    }

    @Test
    fun `parse with createProject true and rich extras returns Rich plan`() = runBlocking {
        coEvery { checklistParser.parse(any()) } returns richResult(
            phases = listOf(phase("Phase 1"))
        )

        val plan = importer.parse("anything", createProject = true)

        assertTrue(plan is ImportPlan.Rich)
        assertEquals("Project A", plan!!.projectName)
        coVerify(exactly = 0) { todoListParser.parse(any()) }
    }

    @Test
    fun `parse with createProject true and no rich extras falls through to flat project`() = runBlocking {
        coEvery { checklistParser.parse(any()) } returns null
        coEvery { todoListParser.parse(any()) } returns ParsedTodoList(
            name = "List X",
            items = listOf(ParsedTodoItem(title = "t1"), ParsedTodoItem(title = "t2"))
        )

        val plan = importer.parse("anything", createProject = true)

        assertTrue(plan is ImportPlan.FlatProject)
        plan as ImportPlan.FlatProject
        assertEquals("List X", plan.projectName)
        assertEquals(listOf("t1", "t2"), plan.taskTitles)
    }

    @Test
    fun `parse with createProject false skips checklist parser and returns FlatOrphans`() = runBlocking {
        coEvery { todoListParser.parse(any()) } returns ParsedTodoList(
            name = null,
            items = listOf(ParsedTodoItem(title = "t1"))
        )

        val plan = importer.parse("anything", createProject = false)

        assertTrue(plan is ImportPlan.FlatOrphans)
        coVerify(exactly = 0) { checklistParser.parse(any()) }
    }

    @Test
    fun `parse returns null when no parser produces output`() = runBlocking {
        coEvery { checklistParser.parse(any()) } returns null
        coEvery { todoListParser.parse(any()) } returns null

        val plan = importer.parse("garbage", createProject = true)

        assertNull(plan)
    }

    @Test
    fun `materialise FlatProject inserts every task when no exclusions`() = runBlocking {
        coEvery { projectRepository.addProject(name = any()) } returns 42L
        val taskSlot = slot<TaskEntity>()
        coEvery { taskRepository.insertTask(capture(taskSlot)) } returns 99L

        val plan = ImportPlan.FlatProject(
            projectName = "X",
            items = listOf(
                ParsedTodoItem(title = "a"),
                ParsedTodoItem(title = "b"),
                ParsedTodoItem(title = "c")
            )
        )

        val outcome = importer.materialise(plan)

        assertTrue(outcome is ImportOutcome.FlatProject)
        outcome as ImportOutcome.FlatProject
        assertEquals(3, outcome.taskCount)
        coVerify(exactly = 3) { taskRepository.insertTask(any()) }
    }

    @Test
    fun `materialise FlatProject skips excluded task indices`() = runBlocking {
        coEvery { projectRepository.addProject(name = any()) } returns 42L
        coEvery { taskRepository.insertTask(any()) } returns 99L

        val plan = ImportPlan.FlatProject(
            projectName = "X",
            items = listOf(
                ParsedTodoItem(title = "a"),
                ParsedTodoItem(title = "b"),
                ParsedTodoItem(title = "c")
            )
        )

        val outcome = importer.materialise(
            plan = plan,
            exclusions = ImportExclusions(excludedTaskIndices = setOf(1))
        )

        assertTrue(outcome is ImportOutcome.FlatProject)
        outcome as ImportOutcome.FlatProject
        assertEquals(2, outcome.taskCount)
        coVerify(exactly = 2) { taskRepository.insertTask(any()) }
    }

    @Test
    fun `materialise Rich honours excluded risk indices`() = runBlocking {
        coEvery {
            projectRepository.addProject(name = any(), color = any(), icon = any())
        } returns 42L
        coEvery { taskRepository.insertTask(any()) } returns 99L

        val plan = ImportPlan.Rich(
            richResult(
                tasks = listOf(checklistTask("t1")),
                risks = listOf(
                    ParsedProjectRiskDomain("r0", null, "LOW"),
                    ParsedProjectRiskDomain("r1", null, "HIGH"),
                    ParsedProjectRiskDomain("r2", null, "MEDIUM")
                )
            )
        )

        val outcome = importer.materialise(
            plan = plan,
            exclusions = ImportExclusions(excludedRiskIndices = setOf(0, 2))
        )

        assertTrue(outcome is ImportOutcome.Rich)
        outcome as ImportOutcome.Rich
        assertEquals(1, outcome.riskCount)
        coVerify(exactly = 1) {
            projectRepository.addRisk(
                projectId = any(),
                title = "r1",
                level = any(),
                mitigation = any()
            )
        }
    }

    @Test
    fun `importContent composes parse and materialise for backward compat`() = runBlocking {
        coEvery { checklistParser.parse(any()) } returns null
        coEvery { todoListParser.parse(any()) } returns ParsedTodoList(
            name = "List",
            items = listOf(ParsedTodoItem(title = "t1"))
        )
        coEvery { projectRepository.addProject(name = any()) } returns 1L
        coEvery { taskRepository.insertTask(any()) } returns 1L

        val outcome = importer.importContent("anything", createProject = true)

        assertTrue(outcome is ImportOutcome.FlatProject)
    }

    @Test
    fun `importContent returns Unparseable when parsers yield nothing`() = runBlocking {
        coEvery { checklistParser.parse(any()) } returns null
        coEvery { todoListParser.parse(any()) } returns null

        val outcome = importer.importContent("garbage", createProject = true)

        assertEquals(ImportOutcome.Unparseable, outcome)
    }

    @Test
    fun `materialise FlatProject preserves description and nested subtasks`() = runBlocking {
        coEvery { projectRepository.addProject(name = any()) } returns 42L
        val inserted = mutableListOf<TaskEntity>()
        var nextId = 100L
        coEvery { taskRepository.insertTask(any()) } answers {
            inserted += firstArg<TaskEntity>()
            nextId++
        }

        val plan = ImportPlan.FlatProject(
            projectName = "X",
            items = listOf(
                ParsedTodoItem(
                    title = "parent",
                    description = "parent details",
                    subtasks = listOf(
                        ParsedTodoItem(
                            title = "child",
                            description = "child details",
                            subtasks = listOf(
                                ParsedTodoItem(title = "grandchild", description = "deep details")
                            )
                        )
                    )
                )
            )
        )

        val outcome = importer.materialise(plan) as ImportOutcome.FlatProject

        assertEquals(3, outcome.taskCount)
        assertEquals(3, inserted.size)
        assertEquals("parent details", inserted[0].description)
        assertEquals("child details", inserted[1].description)
        assertEquals("deep details", inserted[2].description)
        // Parent → child → grandchild parent links
        assertEquals(null, inserted[0].parentTaskId)
        assertEquals(100L, inserted[1].parentTaskId)
        assertEquals(101L, inserted[2].parentTaskId)
    }

    @Test
    fun `materialise Rich preserves description and nested subtasks`() = runBlocking {
        coEvery {
            projectRepository.addProject(name = any(), color = any(), icon = any())
        } returns 42L
        val inserted = mutableListOf<TaskEntity>()
        var nextId = 200L
        coEvery { taskRepository.insertTask(any()) } answers {
            inserted += firstArg<TaskEntity>()
            nextId++
        }

        val grandchild = ChecklistParsedTask(
            title = "grandchild", description = "g-desc", dueDate = null, priority = 0,
            completed = false, tags = emptyList(), subtasks = emptyList(), estimatedMinutes = null
        )
        val child = ChecklistParsedTask(
            title = "child", description = "c-desc", dueDate = null, priority = 0,
            completed = false, tags = emptyList(), subtasks = listOf(grandchild), estimatedMinutes = null
        )
        val parent = ChecklistParsedTask(
            title = "parent", description = "p-desc", dueDate = null, priority = 0,
            completed = false, tags = emptyList(), subtasks = listOf(child), estimatedMinutes = null
        )

        val outcome = importer.materialise(
            ImportPlan.Rich(richResult(tasks = listOf(parent)))
        ) as ImportOutcome.Rich

        assertEquals(3, outcome.taskCount)
        assertEquals("p-desc", inserted[0].description)
        assertEquals("c-desc", inserted[1].description)
        assertEquals("g-desc", inserted[2].description)
        assertEquals(null, inserted[0].parentTaskId)
        assertEquals(200L, inserted[1].parentTaskId)
        assertEquals(201L, inserted[2].parentTaskId)
    }

    // ---- helpers ----

    private fun richResult(
        tasks: List<ChecklistParsedTask> = emptyList(),
        phases: List<ParsedProjectPhaseDomain> = emptyList(),
        risks: List<ParsedProjectRiskDomain> = emptyList()
    ) = ComprehensiveImportResult(
        course = ParsedCourse(code = "C1", name = "Course 1", deadline = null, assignments = emptyList()),
        project = ParsedProject(name = "Project A", color = "#000", icon = "📁"),
        tags = emptyList(),
        tasks = tasks,
        phases = phases,
        risks = risks,
        externalAnchors = emptyList(),
        taskDependencies = emptyList()
    )

    private fun phase(name: String) = ParsedProjectPhaseDomain(
        name = name,
        description = null,
        startDate = null,
        endDate = null,
        orderIndex = 0
    )

    private fun checklistTask(title: String) = ChecklistParsedTask(
        title = title,
        description = null,
        dueDate = null,
        priority = 0,
        completed = false,
        tags = emptyList(),
        subtasks = emptyList(),
        estimatedMinutes = null
    )
}
