package com.averycorp.prismtask.ui.screens.schoolwork

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.ui.components.ProFeature
import com.averycorp.prismtask.ui.components.ProUpgradePrompt
import com.averycorp.prismtask.ui.navigation.PrismTaskRoute
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable private fun schoolAccent(): Color =
    LocalPrismColors.current.dataVisualizationPalette.getOrElse(0) { LocalPrismColors.current.primary }

@Composable private fun doneGreen(): Color = LocalPrismColors.current.successColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolworkScreen(
    navController: NavController,
    viewModel: SchoolworkViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val assignments by viewModel.activeAssignments.collectAsStateWithLifecycle()
    val completions by viewModel.todayCompletions.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importChecklist(context, it) }
    }

    val syllabusPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions
            } catch (_: IllegalArgumentException) {
                // URI not backed by a DocumentsProvider
            }
            navController.navigate(PrismTaskRoute.SyllabusReview.createRoute(it.toString()))
        }
    }

    val userTier by viewModel.proFeatureGate.userTier.collectAsStateWithLifecycle()
    var showUpgradePrompt by remember { mutableStateOf(false) }
    var deletingCourse by remember { mutableStateOf<CourseEntity?>(null) }

    if (showUpgradePrompt) {
        AlertDialog(
            onDismissRequest = { showUpgradePrompt = false },
            confirmButton = {},
            text = {
                ProUpgradePrompt(
                    feature = ProFeature.SYLLABUS_IMPORT,
                    currentTier = userTier,
                    onUpgrade = { _ ->
                        showUpgradePrompt = false
                        navController.navigate("settings/subscription")
                    },
                    onDismiss = { showUpgradePrompt = false }
                )
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.snackbar.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val completedIds = completions.filter { it.completed }.map { it.courseId }.toSet()
    val doneCount = courses.count { it.id in completedIds }
    val totalCount = courses.size
    val progress = if (totalCount > 0) doneCount.toFloat() / totalCount else 0f
    val allDone = totalCount > 0 && doneCount == totalCount

    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasteDialog = false
                pasteContent = ""
            },
            title = { Text("Paste Checklist JSX") },
            text = {
                OutlinedTextField(
                    value = pasteContent,
                    onValueChange = { pasteContent = it },
                    placeholder = { Text("Paste JSX file contents here\u2026") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 50
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pasteContent.isNotBlank()) {
                            viewModel.importFromText(pasteContent)
                        }
                        showPasteDialog = false
                        pasteContent = ""
                    },
                    enabled = pasteContent.isNotBlank()
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasteDialog = false
                    pasteContent = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            "DAILY",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Schoolwork",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetToday() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reset",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        if (viewModel.proFeatureGate.hasAccess(ProFeatureGate.SYLLABUS_IMPORT)) {
                            syllabusPicker.launch(arrayOf("application/pdf"))
                        } else {
                            showUpgradePrompt = true
                        }
                    },
                    containerColor = schoolAccent()
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        contentDescription = "Import Syllabus",
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                }
                SmallFloatingActionButton(
                    onClick = { showPasteDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste Checklist", modifier = Modifier.size(20.dp))
                }
                SmallFloatingActionButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Import File", modifier = Modifier.size(20.dp))
                }
                FloatingActionButton(
                    onClick = { navController.navigate(PrismTaskRoute.AddEditCourse.createRoute()) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Course")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = schoolAccent()
                )
            }

            if (courses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("\uD83C\uDF93", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No Courses Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Import a checklist file or tap + to add a course",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }

                    // Progress card
                    item {
                        ProgressCard(
                            doneCount = doneCount,
                            totalCount = totalCount,
                            progress = progress,
                            allDone = allDone
                        )
                        Spacer(Modifier.height(20.dp))
                    }

                    // Daily course checklist
                    item {
                        SectionHeader(icon = "\uD83D\uDCDA", title = "Daily Course Work")
                        Spacer(Modifier.height(8.dp))
                    }

                    items(courses, key = { it.id }) { course ->
                        val done = course.id in completedIds
                        CourseCheckItem(
                            course = course,
                            done = done,
                            onToggle = { viewModel.toggleCourseCompletion(course.id) },
                            onDelete = { deletingCourse = course }
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    item { Spacer(Modifier.height(16.dp)) }

                    // Courses & Assignments management
                    item {
                        SectionHeader(icon = "\uD83C\uDFEB", title = "Courses & Assignments")
                        Spacer(Modifier.height(8.dp))
                    }

                    items(courses, key = { "manage_${it.id}" }) { course ->
                        CourseCard(
                            course = course,
                            assignments = assignments.filter { it.courseId == course.id },
                            onToggleAssignment = { viewModel.toggleAssignmentComplete(it) },
                            onAddAssignment = { title, dueDate ->
                                viewModel.addAssignment(course.id, title, dueDate)
                            },
                            onDeleteAssignment = { viewModel.deleteAssignment(it) },
                            onEditCourse = {
                                navController.navigate(PrismTaskRoute.AddEditCourse.createRoute(course.id))
                            },
                            onDeleteCourse = { viewModel.deleteCourse(course.id) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    deletingCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { deletingCourse = null },
            title = { Text("Delete Course") },
            text = {
                Text(
                    "Delete \"${course.code} ${course.name}\"? Its assignments and " +
                        "today's completion will also be removed."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCourse(course.id)
                    deletingCourse = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCourse = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProgressCard(doneCount: Int, totalCount: Int, progress: Float, allDone: Boolean) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(400),
        label = "progress"
    )
    val progressColor by animateColorAsState(
        targetValue = if (allDone) doneGreen() else schoolAccent(),
        animationSpec = tween(400),
        label = "progressColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$doneCount / $totalCount courses done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = progressColor
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            if (allDone) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "\u2713 All courses done for today. Solid work.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = doneGreen(),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CourseCheckItem(
    course: CourseEntity,
    done: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (done) schoolAccent().copy(alpha = 0.27f) else MaterialTheme.colorScheme.outline,
        label = "border"
    )
    val bgColor by animateColorAsState(
        targetValue = if (done) schoolAccent().copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant,
        label = "bg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (done) schoolAccent() else Color.Transparent)
                    .border(
                        2.dp,
                        if (done) schoolAccent() else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${course.icon} ${course.code}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    course.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Trailing remove affordance — opens a confirm dialog at screen
            // level so accidental taps on the row's clickable surface don't
            // double-book a delete.
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${course.code}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CourseCard(
    course: CourseEntity,
    assignments: List<AssignmentEntity>,
    onToggleAssignment: (Long) -> Unit,
    onAddAssignment: (String, Long?) -> Unit,
    onDeleteAssignment: (Long) -> Unit,
    onEditCourse: () -> Unit,
    onDeleteCourse: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddField by remember { mutableStateOf(false) }
    var newAssignmentTitle by remember { mutableStateOf("") }

    val activeCount = assignments.count { !it.completed }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(course.icon, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        course.code,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        course.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (activeCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(LocalPrismShapes.current.chip)
                            .background(schoolAccent().copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$activeCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = schoolAccent()
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                assignments.forEach { assignment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleAssignment(assignment.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (assignment.completed) doneGreen() else Color.Transparent)
                                .border(
                                    1.5.dp,
                                    if (assignment.completed) doneGreen() else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (assignment.completed) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Completed",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            assignment.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (assignment.completed) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (assignment.completed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (assignment.dueDate != null) {
                            Text(
                                formatDate(assignment.dueDate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteAssignment(assignment.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showAddField) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newAssignmentTitle,
                            onValueChange = { newAssignmentTitle = it },
                            placeholder = { Text("Assignment Title") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newAssignmentTitle.isNotBlank()) {
                                    onAddAssignment(newAssignmentTitle.trim(), null)
                                    newAssignmentTitle = ""
                                    showAddField = false
                                }
                            })
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { showAddField = !showAddField }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Assignment", style = MaterialTheme.typography.labelSmall)
                    }
                    Row {
                        TextButton(onClick = onEditCourse) {
                            Text("Edit", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onDeleteCourse) {
                            Text(
                                "Delete",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d", Locale.US)
    return sdf.format(Date(millis))
}
