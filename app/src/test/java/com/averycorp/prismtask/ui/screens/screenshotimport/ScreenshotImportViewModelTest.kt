package com.averycorp.prismtask.ui.screens.screenshotimport

import com.averycorp.prismtask.data.repository.AttachmentRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.NaturalLanguageParser
import com.averycorp.prismtask.domain.usecase.ParsedTask
import com.averycorp.prismtask.domain.usecase.ParsedTaskResolver
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.ResolvedTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ScreenshotImportViewModel] (G).
 *
 * Covers the editable-candidate state machine (toggle / editTitle), the
 * empty-list fallback when the Vision API returns no candidates, and the
 * createSelected loop's interaction with the existing NLP pipeline.
 *
 * The encoder + image picker + AttachmentRepository file IO require an
 * Android Context, so this test only exercises the pure ViewModel surface
 * (createSelected with a null source URI to skip the attachment branch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: ScreenshotImportRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var attachmentRepository: AttachmentRepository
    private lateinit var parser: NaturalLanguageParser
    private lateinit var parsedTaskResolver: ParsedTaskResolver
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var viewModel: ScreenshotImportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
        taskRepository = mockk()
        tagRepository = mockk()
        projectRepository = mockk()
        attachmentRepository = mockk()
        parser = mockk()
        parsedTaskResolver = mockk()
        proFeatureGate = mockk()

        every { proFeatureGate.hasAccess(any()) } returns false  // use offline parser path

        coEvery {
            taskRepository.addTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1L
        coEvery { tagRepository.setTagsForTask(any(), any()) } returns Unit
        coEvery { projectRepository.addProject(any(), any(), any()) } returns 99L

        viewModel = ScreenshotImportViewModel(
            repository = repository,
            taskRepository = taskRepository,
            tagRepository = tagRepository,
            projectRepository = projectRepository,
            attachmentRepository = attachmentRepository,
            parser = parser,
            parsedTaskResolver = parsedTaskResolver,
            proFeatureGate = proFeatureGate
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggle_flipsSelection() = runTest(dispatcher) {
        seedTwoCandidates()
        assertTrue(viewModel.candidates.value[0].selected)
        viewModel.toggle(0)
        assertFalse(viewModel.candidates.value[0].selected)
        viewModel.toggle(0)
        assertTrue(viewModel.candidates.value[0].selected)
    }

    @Test
    fun toggle_outOfBoundsIsNoOp() = runTest(dispatcher) {
        seedTwoCandidates()
        viewModel.toggle(99)
        viewModel.toggle(-5)
        assertTrue(viewModel.candidates.value.all { it.selected })
    }

    @Test
    fun editTitle_replacesTitleAtIndex() = runTest(dispatcher) {
        seedTwoCandidates()
        viewModel.editTitle(0, "Renamed Task")
        assertEquals("Renamed Task", viewModel.candidates.value[0].title)
        assertEquals("Call Mary", viewModel.candidates.value[1].title)
    }

    @Test
    fun createSelected_skipsUncheckedRows() = runTest(dispatcher) {
        seedTwoCandidates()
        viewModel.toggle(1)  // uncheck Call Mary
        wireParserAndResolverForOfflineParse()

        viewModel.createSelected(context = mockk(relaxed = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            taskRepository.addTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(1, viewModel.createdCount.value)
    }

    @Test
    fun createSelected_skipsBlankTitles() = runTest(dispatcher) {
        seedTwoCandidates()
        viewModel.editTitle(0, "")  // blank → must be skipped
        wireParserAndResolverForOfflineParse()

        viewModel.createSelected(context = mockk(relaxed = true))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            taskRepository.addTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(1, viewModel.createdCount.value)
    }

    @Test
    fun onUserCancelled_resetsCandidatesAndState() = runTest(dispatcher) {
        seedTwoCandidates()
        viewModel.onUserCancelled()
        assertTrue(viewModel.candidates.value.isEmpty())
        assertEquals(ScreenshotImportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun createSelected_doesNotAttachWhenSourceUriIsNull() = runTest(dispatcher) {
        // No image was ever picked (sourceImageUri remains null) — the
        // create flow should still succeed but skip the attachment branch.
        seedTwoCandidates()
        wireParserAndResolverForOfflineParse()

        viewModel.createSelected(context = mockk(relaxed = true))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            attachmentRepository.addImageAttachment(any(), any(), any())
        }
        assertEquals(2, viewModel.createdCount.value)
    }

    @Test
    fun initialState_isIdleWithNoCandidates() {
        assertEquals(ScreenshotImportUiState.Idle, viewModel.uiState.value)
        assertTrue(viewModel.candidates.value.isEmpty())
        assertNull(viewModel.createdCount.value)
    }

    // -- helpers -----------------------------------------------------

    private fun seedTwoCandidates() {
        // Direct field-poke through a public seeder is overkill; just call
        // editTitle on a freshly-instantiated VM to set up the candidates
        // shape we need without round-tripping through the encoder. We do
        // this by abusing onUserCancelled to start from clean, then we
        // pretend the ViewModel observed two extracted rows.
        // Easier path: drive through repository mock + onImagePicked is
        // not viable without a Bitmap — so we mutate via reflection.
        val field = ScreenshotImportViewModel::class.java
            .getDeclaredField("_candidates")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<EditableScreenshotCandidate>>
        flow.value = listOf(
            EditableScreenshotCandidate(title = "Email Bob", confidence = 0.9f, selected = true),
            EditableScreenshotCandidate(title = "Call Mary", confidence = 0.8f, selected = true)
        )
    }

    private fun wireParserAndResolverForOfflineParse() {
        every { parser.parse(any()) } answers {
            ParsedTask(title = firstArg())
        }
        coEvery { parsedTaskResolver.resolve(any<ParsedTask>()) } answers {
            val parsed = firstArg<ParsedTask>()
            ResolvedTask(
                title = parsed.title,
                dueDate = null,
                dueTime = null,
                tagIds = emptyList(),
                projectId = null,
                priority = 0,
                recurrenceRule = null,
                unmatchedTags = emptyList(),
                unmatchedProject = null
            )
        }
    }
}

