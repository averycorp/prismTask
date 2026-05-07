package com.averycorp.prismtask.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.hilt.navigation.compose.hiltViewModel
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.ui.a11y.asHeading
import com.averycorp.prismtask.ui.a11y.politeLiveRegion
import com.averycorp.prismtask.ui.screens.auth.EmailAuthSection
import com.averycorp.prismtask.ui.screens.templates.TemplatePickerContent
import com.averycorp.prismtask.ui.screens.templates.TemplateSelections
import com.averycorp.prismtask.ui.theme.LocalPrismAttrs
import com.averycorp.prismtask.ui.theme.LocalPrismColors
import com.averycorp.prismtask.ui.theme.LocalPrismFonts
import com.averycorp.prismtask.ui.theme.LocalPrismShapes
import com.averycorp.prismtask.ui.theme.PrismTheme
import com.averycorp.prismtask.ui.theme.ThemeViewModel
import com.averycorp.prismtask.ui.theme.prismThemeAttrs
import com.averycorp.prismtask.ui.theme.prismThemeColors
import com.averycorp.prismtask.ui.theme.prismThemeFonts
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TOTAL_PAGES = 17
private const val LAST_PAGE_INDEX = TOTAL_PAGES - 1

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES })
    val coroutineScope = rememberCoroutineScope()

    // Navigate straight to the main app when sign-in detects an existing user
    // (from either the Welcome page sign-in link or the Setup page sign-in card).
    LaunchedEffect(Unit) {
        viewModel.signInState.collect { state ->
            if (state is SignInState.ExistingUserDetected) {
                onComplete()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomePage(viewModel = viewModel)
                1 -> ThemePickerPage()
                2 -> SmartTasksPage()
                3 -> ProjectsPage()
                4 -> NaturalLanguagePage()
                5 -> HabitsPage(viewModel = viewModel)
                6 -> LifeModesPage(viewModel = viewModel)
                7 -> TemplatesPage(viewModel = viewModel)
                8 -> ViewsPage()
                9 -> BrainModePage(viewModel = viewModel)
                10 -> AccessibilityPage(viewModel = viewModel)
                11 -> AiOverviewPage()
                12 -> PrivacyPage(viewModel = viewModel)
                13 -> NotificationsPage(viewModel = viewModel)
                14 -> DaySetupPage(viewModel = viewModel)
                15 -> ConnectIntegrationsPage()
                LAST_PAGE_INDEX -> SetupPage(
                    viewModel = viewModel,
                    onComplete = {
                        viewModel.completeOnboarding()
                        onComplete()
                    }
                )
            }
        }

        // Skip button — available until the final setup page.
        if (pagerState.currentPage < LAST_PAGE_INDEX) {
            TextButton(
                onClick = {
                    coroutineScope.launch { pagerState.animateScrollToPage(LAST_PAGE_INDEX) }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
            ) {
                Text("Skip")
            }
        }

        // Bottom controls — hidden on the final page which carries its own.
        if (pagerState.currentPage < LAST_PAGE_INDEX) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicators
                val currentPagePosition = pagerState.currentPage + 1
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .semantics {
                            contentDescription = "Page $currentPagePosition of $TOTAL_PAGES"
                        }
                ) {
                    repeat(TOTAL_PAGES) { index ->
                        val isSelected = pagerState.currentPage == index
                        val width by animateFloatAsState(
                            targetValue = if (isSelected) 24f else 8f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "dot_width"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    }
                                )
                        )
                    }
                }

                // Navigation buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text("Back")
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    ) {
                        Text(
                            when (pagerState.currentPage) {
                                0 -> "Get Started"
                                1 -> "Continue"
                                else -> "Next"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val signInState = remember { mutableStateOf<SignInState>(SignInState.NotSignedIn) }
    LaunchedEffect(Unit) { viewModel.signInState.collect { signInState.value = it } }

    val scale = remember { Animatable(0.5f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 64.dp)
        ) {
            Text(
                text = "\uD83D\uDD73",
                fontSize = 80.sp,
                modifier = Modifier.scale(scale.value)
            )
            Spacer(modifier = Modifier.height(24.dp))
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { 30 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Welcome to PrismTask",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.asHeading()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your smart, adaptive productivity companion",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            // "Already have an account?" sign-in link — lets returning users on a
            // new device skip the walkthrough by detecting existing Firestore data.
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(400, delayMillis = 400))
            ) {
                when (val state = signInState.value) {
                    is SignInState.Loading, is SignInState.CheckingExistingUser -> {
                        Spacer(modifier = Modifier.height(24.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    is SignInState.SignedIn, is SignInState.ExistingUserDetected -> {
                        // Signed in — navigation handled by OnboardingScreen LaunchedEffect
                        // or user continues through onboarding as a new user.
                    }
                    is SignInState.ExistingUserCheckFailed -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Couldn't check for existing account — continuing with setup.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .politeLiveRegion()
                        )
                    }
                    else -> {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val activity = run {
                                    var ctx = context
                                    while (ctx is android.content.ContextWrapper && ctx !is Activity) {
                                        ctx = ctx.baseContext
                                    }
                                    ctx as? Activity
                                } ?: return@Button
                                coroutineScope.launch {
                                    try {
                                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
                                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                        val result = CredentialManager.create(context).getCredential(activity, request)
                                        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                                        viewModel.onGoogleSignIn(idToken)
                                    } catch (_: GetCredentialCancellationException) {
                                        // User cancelled — leave state unchanged.
                                    } catch (_: Exception) {
                                        // Credential error — leave state unchanged.
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign In with Google")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        EmailAuthSection(
                            onSignUp = viewModel::onEmailSignUp,
                            onSignIn = viewModel::onEmailSignIn
                        )
                        if (state is SignInState.Error) {
                            Text(
                                text = "Sign-in failed. Tap to try again.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .politeLiveRegion()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartTasksPage() {
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }

    OnboardingPageLayout(
        emoji = "\u2705",
        headline = "Organize Everything",
        body = "Projects, tags, subtasks, and priorities. Drag to reorder, bulk edit, and quick-reschedule with a tap."
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            listOf("Buy groceries", "Finish report", "Call dentist").forEachIndexed { index, task ->
                AnimatedVisibility(
                    visible = animStarted,
                    enter = fadeIn(tween(300, delayMillis = index * 150)) +
                        slideInVertically(tween(300, delayMillis = index * 150)) { it }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(task, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsPage() {
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }

    val projects = listOf(
        Triple("🎯", "Launch v2", 0.7f),
        Triple("🏠", "Move apartments", 0.35f),
        Triple("📚", "Read 12 books", 0.5f)
    )

    OnboardingPageLayout(
        emoji = "📁",
        headline = "Group with Projects",
        body = "Bundle related tasks into a project, set milestones, and track a forgiveness-friendly streak as you make progress."
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            projects.forEachIndexed { index, (icon, name, progress) ->
                AnimatedVisibility(
                    visible = animStarted,
                    enter = fadeIn(tween(300, delayMillis = index * 150)) +
                        slideInVertically(tween(300, delayMillis = index * 150)) { it }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(icon, fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NaturalLanguagePage() {
    var showChips by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    val fullText = "Buy groceries tomorrow !high #errands"

    LaunchedEffect(Unit) {
        for (i in fullText.indices) {
            typedText = fullText.substring(0, i + 1)
            delay(40)
        }
        delay(300)
        showChips = true
    }

    OnboardingPageLayout(
        emoji = "\u2328\uFE0F",
        headline = "Type Naturally",
        body = "Just type 'Buy groceries tomorrow !high #errands' and PrismTask understands instantly."
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = typedText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AnimatedVisibility(
                visible = showChips,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 20 }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChipLabel("Tomorrow", MaterialTheme.colorScheme.primary)
                    ChipLabel("High", LocalPrismColors.current.urgentAccent)
                    ChipLabel("#errands", MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun ChipLabel(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), LocalPrismShapes.current.chip)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun HabitsPage(viewModel: OnboardingViewModel) {
    var streakCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in 1..14) {
            streakCount = i
            delay(40)
        }
    }

    val forgivenessEnabled by collectAsLocalState(viewModel.forgivenessStreaksEnabled, initial = true)
    val streakMaxMissed by collectAsLocalState(
        viewModel.streakMaxMissedDays,
        initial = 1
    )

    OnboardingPageLayout(
        emoji = "\uD83D\uDD25",
        headline = "Build Habits, Stay Focused",
        body = "Track daily habits with streaks and analytics. Use AI-powered focus sessions to get more done."
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("\uD83D\uDD25", fontSize = 36.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$streakCount days this week",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                repeat(7) { day ->
                    val filled = day < 5
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (filled) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(20.dp))
            // Forgiveness card \u2014 opt-in, default ON. Lets the user pick how
            // forgiving their streak should be on the same page that explains
            // streaks, rather than burying it in Settings \u2192 Habits & Streaks.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Forgiving Streaks",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Skip a day without losing your streak.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = forgivenessEnabled,
                            onCheckedChange = viewModel::setForgivenessStreaksEnabled
                        )
                    }
                    AnimatedVisibility(visible = forgivenessEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Allow up to $streakMaxMissed missed " +
                                    if (streakMaxMissed == 1) "day" else "days",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = streakMaxMissed.toFloat(),
                                onValueChange = { viewModel.setStreakMaxMissedDays(it.toInt()) },
                                valueRange = 1f..7f,
                                steps = 5
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplatesPage(viewModel: OnboardingViewModel) {
    val selections by viewModel.templateSelections
        .let { flow ->
            val state = remember { mutableStateOf(TemplateSelections()) }
            LaunchedEffect(Unit) { flow.collect { state.value = it } }
            state
        }
    val selfCareEnabled by viewModel.selfCareEnabled
        .let { flow ->
            val state = remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { flow.collect { state.value = it } }
            state
        }
    val houseworkEnabled by viewModel.houseworkEnabled
        .let { flow ->
            val state = remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { flow.collect { state.value = it } }
            state
        }
    val leisureEnabled by viewModel.leisureEnabled
        .let { flow ->
            val state = remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { flow.collect { state.value = it } }
            state
        }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text("\uD83D\uDCCB", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Pick Your Starting Templates",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Everything is optional — you can add or change these anytime in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Hide template sections whose owning Life Mode is off (toggled on the
        // prior LifeModesPage). Settings "Browse Templates" still shows everything.
        if (!selfCareEnabled && !houseworkEnabled && !leisureEnabled) {
            Text(
                text = "No template sections — every Life Mode is off. Tap Next to skip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            TemplatePickerContent(
                state = selections,
                onChange = viewModel::updateTemplateSelections,
                modifier = Modifier.fillMaxWidth(),
                showLeisure = leisureEnabled,
                showSelfCare = selfCareEnabled,
                showHousework = houseworkEnabled
            )
        }
    }
}

@Composable
private fun ViewsPage() {
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }

    OnboardingPageLayout(
        emoji = "\uD83D\uDC41\uFE0F",
        headline = "See Your Way",
        body = "Today focus, week planner, calendar, timeline, and Eisenhower matrix. Your tasks, your view."
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            listOf("Today" to "\u2600\uFE0F", "Week" to "\uD83D\uDCC5", "Month" to "\uD83D\uDDD3\uFE0F").forEachIndexed {
                    index,
                    (label, icon)
                ->
                AnimatedVisibility(
                    visible = animStarted,
                    enter = fadeIn(tween(400, delayMillis = index * 200)) +
                        slideInVertically(tween(400, delayMillis = index * 200)) { 40 }
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(icon, fontSize = 28.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrainModePage(viewModel: OnboardingViewModel) {
    var adhdSelected by remember { mutableStateOf(false) }
    var calmSelected by remember { mutableStateOf(false) }
    var focusReleaseSelected by remember { mutableStateOf(false) }
    var expandedCard by remember { mutableIntStateOf(-1) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "\uD83E\uDDE0",
                    fontSize = 48.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "How Does Your Brain Work?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select any that apply \u2014 or skip if none fit. You can always change these in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Card 1: ADHD Mode
        BrainModeCard(
            emoji = "\u26A1",
            title = "I Get Distracted Easily",
            subtitle = "Hard to start tasks, lose track of time, need momentum to keep going",
            expandedDescription = "This turns on: task decomposition to break big tasks into small wins, " +
                "focus guard timers, body doubling check-ins, completion celebrations, " +
                "progress bars, and forgiveness streaks.",
            isSelected = adhdSelected,
            isExpanded = expandedCard == 0,
            onToggle = {
                adhdSelected = !adhdSelected
                viewModel.setAdhdMode(!adhdSelected.not()) // toggle already flipped
                if (expandedCard != 0) expandedCard = 0 else expandedCard = -1
            },
            onExpandToggle = { expandedCard = if (expandedCard == 0) -1 else 0 },
            index = 0
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Card 2: Calm Mode
        BrainModeCard(
            emoji = "\uD83C\uDF3F",
            title = "I Get Overstimulated Easily",
            subtitle = "Animations, bright colors, and sounds can be too much",
            expandedDescription = "This turns on: reduced animations, muted color palette, " +
                "quiet mode (no sounds), reduced haptics, and soft contrast throughout the app.",
            isSelected = calmSelected,
            isExpanded = expandedCard == 1,
            onToggle = {
                calmSelected = !calmSelected
                viewModel.setCalmMode(!calmSelected.not())
                if (expandedCard != 1) expandedCard = 1 else expandedCard = -1
            },
            onExpandToggle = { expandedCard = if (expandedCard == 1) -1 else 1 },
            index = 1
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Card 3: Focus & Release Mode
        BrainModeCard(
            emoji = "\uD83E\uDD32",
            title = "I Have Trouble Letting Go of Tasks",
            subtitle = "Spend too long polishing, re-check finished work, struggle to call things done",
            expandedDescription = "This turns on: \u2018good enough\u2019 timers that gently nudge you to finish, " +
                "guards against endlessly re-editing completed work, celebrations for " +
                "shipping (not perfecting), and help when you\u2019re stuck choosing.",
            isSelected = focusReleaseSelected,
            isExpanded = expandedCard == 2,
            onToggle = {
                focusReleaseSelected = !focusReleaseSelected
                viewModel.setFocusReleaseMode(!focusReleaseSelected.not())
                if (expandedCard != 2) expandedCard = 2 else expandedCard = -1
            },
            onExpandToggle = { expandedCard = if (expandedCard == 2) -1 else 2 },
            index = 2
        )
    }
}

@Composable
private fun BrainModeCard(
    emoji: String,
    title: String,
    subtitle: String,
    expandedDescription: String,
    isSelected: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onExpandToggle: () -> Unit,
    index: Int
) {
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 100L)
        animStarted = true
    }

    AnimatedVisibility(
        visible = animStarted,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { 40 }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            border = if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(emoji, fontSize = 28.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Expandable preview
                AnimatedVisibility(visible = isExpanded || isSelected) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = expandedDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPage(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val signInState by viewModel.signInState
        .let { flow ->
            val state = remember { mutableStateOf<SignInState>(SignInState.NotSignedIn) }
            LaunchedEffect(Unit) { flow.collect { state.value = it } }
            state
        }
    var taskText by remember { mutableStateOf("") }
    var taskCreated by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Let's Get You Started",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.asHeading()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Set up your preferences (all optional)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 1. Sign In Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sign In with Google", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Sync your tasks across devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                when (val state = signInState) {
                    is SignInState.SignedIn -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.email, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is SignInState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    is SignInState.CheckingExistingUser -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Checking for existing account…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is SignInState.ExistingUserCheckFailed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(state.email, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Couldn't check for existing account — continuing with setup.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.politeLiveRegion()
                        )
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = {
                                // Unwrap ContextWrapper chain rather than
                                // casting directly — a wrapped context from
                                // a tooltip/dialog host would ClassCastException.
                                val activity = run {
                                    var ctx = context
                                    while (ctx is android.content.ContextWrapper && ctx !is Activity) {
                                        ctx = ctx.baseContext
                                    }
                                    ctx as? Activity
                                } ?: return@FilledTonalButton
                                coroutineScope.launch {
                                    try {
                                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
                                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                        val result = CredentialManager.create(context).getCredential(activity, request)
                                        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                                        viewModel.onGoogleSignIn(idToken)
                                    } catch (_: GetCredentialCancellationException) {
                                        // User cancelled
                                    } catch (_: Exception) {
                                        // Handle error silently for onboarding
                                    }
                                }
                            }
                        ) {
                            Text("Sign In")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Quick Task Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Create Your First Task", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(12.dp))
                if (taskCreated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Task Created", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    OutlinedTextField(
                        value = taskText,
                        onValueChange = { taskText = it },
                        placeholder = { Text("e.g., Buy groceries tomorrow") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (taskText.isNotBlank()) {
                                    viewModel.createQuickTask(taskText)
                                    taskCreated = true
                                }
                            }
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Complete button
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Using PrismTask")
        }
    }
}

// ─── Life Modes Opt-In Page ──────────────────────────────────────────────
//
// Asks the user which Life Modes (self-care, medication, school, housework,
// leisure) they want enabled, *before* `TemplatesPage` so it can hide
// template sections whose mode is off. Defaults all five to on (matches
// `HabitListPreferences` defaults), so toggling is opt-OUT.

@Composable
private fun LifeModesPage(viewModel: OnboardingViewModel) {
    val selfCare by collectAsLocalState(viewModel.selfCareEnabled, initial = true)
    val medication by collectAsLocalState(viewModel.medicationEnabled, initial = true)
    val school by collectAsLocalState(viewModel.schoolEnabled, initial = true)
    val housework by collectAsLocalState(viewModel.houseworkEnabled, initial = true)
    val leisure by collectAsLocalState(viewModel.leisureEnabled, initial = true)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🧩", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "What Do You Want to Track?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Turn off any modes that don't apply. You can flip these back on anytime in Settings → Life Modes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        LifeModeRow(
            emoji = "🌿",
            title = "Self-Care",
            subtitle = "Morning + bedtime routines, hydration, mental health",
            checked = selfCare,
            onCheckedChange = viewModel::setSelfCareEnabled
        )
        LifeModeRow(
            emoji = "💊",
            title = "Medication",
            subtitle = "Dose reminders + adherence tracking",
            checked = medication,
            onCheckedChange = viewModel::setMedicationEnabled
        )
        LifeModeRow(
            emoji = "🎓",
            title = "Schoolwork",
            subtitle = "Courses, assignments, due dates",
            checked = school,
            onCheckedChange = viewModel::setSchoolEnabled
        )
        LifeModeRow(
            emoji = "🧹",
            title = "Housework",
            subtitle = "Daily home upkeep + chores",
            checked = housework,
            onCheckedChange = viewModel::setHouseworkEnabled
        )
        LifeModeRow(
            emoji = "🎲",
            title = "Leisure",
            subtitle = "Music practice, hobbies, downtime",
            checked = leisure,
            onCheckedChange = viewModel::setLeisureEnabled
        )
    }
}

@Composable
private fun LifeModeRow(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (checked) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

// ─── Accessibility Quick-Set Page ─────────────────────────────────────────
//
// Three opt-in toggles for app-level a11y. All default OFF — system-level
// settings (TalkBack, font scale) take precedence; these are the
// PrismTask-specific layers on top.

@Composable
private fun AccessibilityPage(viewModel: OnboardingViewModel) {
    val reduceMotion by collectAsLocalState(viewModel.reduceMotion, initial = false)
    val highContrast by collectAsLocalState(viewModel.highContrast, initial = false)
    val largeTouchTargets by collectAsLocalState(viewModel.largeTouchTargets, initial = false)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "♿", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Accessibility",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Quick toggles for the most common needs. System-wide TalkBack and font " +
                        "scaling work as expected on top of these.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        LifeModeRow(
            emoji = "🌀",
            title = "Reduce Motion",
            subtitle = "Cut animations across the app",
            checked = reduceMotion,
            onCheckedChange = viewModel::setReduceMotion
        )
        LifeModeRow(
            emoji = "🌓",
            title = "High Contrast",
            subtitle = "Stronger text and icon contrast",
            checked = highContrast,
            onCheckedChange = viewModel::setHighContrast
        )
        LifeModeRow(
            emoji = "👆",
            title = "Large Touch Targets",
            subtitle = "Bigger tap zones on rows and chips",
            checked = largeTouchTargets,
            onCheckedChange = viewModel::setLargeTouchTargets
        )
    }
}

// Minimal `collectAsState`-style helper that accepts an initial value so the
// returned `State<T>` is non-null and Compose can short-circuit recomposition
// before the StateFlow emits its first value. Inline-defined here to avoid a
// new shared util file for two callers.
@Composable
private fun <T> collectAsLocalState(
    flow: kotlinx.coroutines.flow.StateFlow<T>,
    initial: T
): androidx.compose.runtime.State<T> {
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(flow) { flow.collect { state.value = it } }
    return state
}

// ─── Privacy & Permissions Page ───────────────────────────────────────────
//
// Voice input + AI features default ON in their respective preference
// stores; this page surfaces both as opt-out toggles before the user
// encounters them in-app. Microphone permission is intentionally NOT
// requested here — it stays gated to first voice-input use so the system
// dialog only fires when the user is actually trying to use voice.

@Composable
private fun AiOverviewPage() {
    var animStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animStarted = true }

    data class AiBucket(
        val emoji: String,
        val title: String,
        val description: String,
        val tier: String
    )

    val buckets = listOf(
        AiBucket(
            emoji = "✍️",
            title = "Capture",
            description = "Type or speak naturally — NLP parses dates, tags, projects, and priority. " +
                "Smart suggestions and defaults learn from your history.",
            tier = "Free"
        ),
        AiBucket(
            emoji = "🗂️",
            title = "Plan",
            description = "Eisenhower auto-classify, daily briefing, and smart Pomodoro coaching pick up where you left off.",
            tier = "Pro"
        ),
        AiBucket(
            emoji = "🪞",
            title = "Reflect",
            description = "Mood + energy correlation, burnout scoring, and weekly review aggregator surface patterns over time.",
            tier = "Free"
        ),
        AiBucket(
            emoji = "🛡️",
            title = "Protect",
            description = "Life-category auto-classify and notification profile auto-switching dial back " +
                "when you're trending toward overload.",
            tier = "Free"
        )
    )

    OnboardingPageLayout(
        emoji = "🤖",
        headline = "AI That Helps, Not Hovers",
        body = "PrismTask's AI runs across four areas. You can disable any of it on the next step."
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            buckets.forEachIndexed { index, bucket ->
                AnimatedVisibility(
                    visible = animStarted,
                    enter = fadeIn(tween(300, delayMillis = index * 120)) +
                        slideInVertically(tween(300, delayMillis = index * 120)) { it }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(bucket.emoji, fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = bucket.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    ChipLabel(
                                        text = bucket.tier,
                                        color = if (bucket.tier == "Pro") {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = bucket.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyPage(viewModel: OnboardingViewModel) {
    val voiceEnabled by collectAsLocalState(viewModel.voiceInputEnabled, initial = true)
    val aiEnabled by collectAsLocalState(viewModel.aiFeaturesEnabled, initial = true)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🛡️", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Privacy & Features",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Both default on. Turn off anything you don't want — " +
                        "voice and AI can be flipped back on in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        LifeModeRow(
            emoji = "🎙️",
            title = "Voice Input",
            subtitle = "Dictate tasks + hands-free commands. Mic permission only fires the first time you use it.",
            checked = voiceEnabled,
            onCheckedChange = viewModel::setVoiceInputEnabled
        )
        LifeModeRow(
            emoji = "✨",
            title = "AI Features",
            subtitle = "NLP parsing, briefings, Eisenhower auto-classify, Pomodoro coaching.",
            checked = aiEnabled,
            onCheckedChange = viewModel::setAiFeaturesEnabled
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Mood + energy logging is opt-in via the Mood Analytics screen — " +
                "nothing is recorded until you start a check-in there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// ─── Notifications & Briefings Page ───────────────────────────────────────
//
// Six default-ON notification streams. Toggling any of these here flips
// the local `*Enabled` flag and — on Android 13+ — also fires the system
// POST_NOTIFICATIONS permission dialog the first time this page is reached
// so the user encounters the permission ask while the consent context
// (the explanatory page they just landed on) is still on screen, instead
// of after onboarding completes. `MainActivity.kt` keeps a re-check for
// users who skipped the page entirely.

@Composable
private fun NotificationsPage(viewModel: OnboardingViewModel) {
    val daily by collectAsLocalState(viewModel.dailyBriefingEnabled, initial = true)
    val evening by collectAsLocalState(viewModel.eveningSummaryEnabled, initial = true)
    val weekly by collectAsLocalState(viewModel.weeklySummaryEnabled, initial = true)
    val overload by collectAsLocalState(viewModel.overloadAlertsEnabled, initial = true)
    val streaks by collectAsLocalState(viewModel.streakAlertsEnabled, initial = true)
    val reengagement by collectAsLocalState(viewModel.reengagementEnabled, initial = true)

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled by MainActivity's existing on-resume re-check */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🔔", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Reminders & Summaries",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "All on by default. Per-channel quiet hours and " +
                        "fine-grained controls live in Settings → Notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        LifeModeRow(
            emoji = "🌅",
            title = "Daily Briefing",
            subtitle = "Morning summary of what's on your plate.",
            checked = daily,
            onCheckedChange = viewModel::setDailyBriefingEnabled
        )
        LifeModeRow(
            emoji = "🌇",
            title = "Evening Summary",
            subtitle = "What got done + what's left.",
            checked = evening,
            onCheckedChange = viewModel::setEveningSummaryEnabled
        )
        LifeModeRow(
            emoji = "📅",
            title = "Weekly Review",
            subtitle = "Habit + task summary at week's end.",
            checked = weekly,
            onCheckedChange = viewModel::setWeeklySummaryEnabled
        )
        LifeModeRow(
            emoji = "⚠️",
            title = "Overload Alerts",
            subtitle = "Heads-up when your day looks too packed.",
            checked = overload,
            onCheckedChange = viewModel::setOverloadAlertsEnabled
        )
        LifeModeRow(
            emoji = "🔥",
            title = "Streak Alerts",
            subtitle = "Reminder when a streak is about to break.",
            checked = streaks,
            onCheckedChange = viewModel::setStreakAlertsEnabled
        )
        LifeModeRow(
            emoji = "👋",
            title = "Re-engagement Nudges",
            subtitle = "Occasional nudge if you've been away.",
            checked = reengagement,
            onCheckedChange = viewModel::setReengagementEnabled
        )
    }
}

// ─── Day Setup Page ───────────────────────────────────────────────────────
//
// Folds the legacy `MainActivity` Start-of-Day modal into the onboarding
// pager so fresh installs set their day-roll-over hour in-flow. The modal
// stays in `MainActivity` as deny-recovery for legacy installs that
// completed onboarding before this page existed. `setStartOfDay` writes
// the hour, the minute, and `hasSetStartOfDay = true` atomically — exactly
// the same write the modal does — so the flag flip prevents a double-prompt.

@Composable
private fun DaySetupPage(viewModel: OnboardingViewModel) {
    val hour by collectAsLocalState(viewModel.startOfDayHour, initial = 4)
    val minute by collectAsLocalState(viewModel.startOfDayMinute, initial = 0)

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🕓", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "When Does Your Day Start?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Habits and streaks roll over at this time. Most people " +
                        "pick between 3–5 AM. Calendar dates and explicit due dates " +
                        "are unaffected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "%02d:%02d %s".format(
                        if (hour == 0 || hour == 12) 12 else hour % 12,
                        minute,
                        if (hour < 12) "AM" else "PM"
                    ),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Hour", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = hour.toFloat(),
                    onValueChange = { viewModel.setStartOfDay(it.toInt(), minute) },
                    valueRange = 0f..23f,
                    steps = 22
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Minute (5-min steps)", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = (minute / 5).toFloat(),
                    onValueChange = { viewModel.setStartOfDay(hour, (it.toInt() * 5).coerceIn(0, 55)) },
                    valueRange = 0f..11f,
                    steps = 10
                )
            }
        }
    }
}

// ─── Connect Integrations Page ────────────────────────────────────────────
//
// Awareness-only page surfacing two integrations the user would otherwise
// only discover by spelunking Settings: Google Calendar two-way sync and
// Google Drive backup. Both are off by default and require scope grants;
// rather than break the onboarding flow with an Activity-result detour,
// this page just tells the user where to find each. The user lands in the
// fully-functional app a moment later and can navigate to either screen.

@Composable
private fun ConnectIntegrationsPage() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp, start = 24.dp, end = 24.dp, bottom = 140.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "🔗", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Connect More Later",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Two integrations live in Settings — both off by default " +
                        "and ready when you want them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        IntegrationInfoCard(
            emoji = "📅",
            title = "Google Calendar",
            subtitle = "Two-way sync between PrismTask tasks and your calendar events.",
            location = "Settings → Calendar"
        )
        Spacer(modifier = Modifier.height(12.dp))
        IntegrationInfoCard(
            emoji = "💾",
            title = "Google Drive Backup",
            subtitle = "Encrypted manual + scheduled backups of your task data.",
            location = "Settings → Data & Backup"
        )
    }
}

@Composable
private fun IntegrationInfoCard(
    emoji: String,
    title: String,
    subtitle: String,
    location: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageLayout(
    emoji: String,
    headline: String,
    body: String,
    illustration: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 30 }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 40.sp)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        illustration()
    }
}

// ─── Theme Picker Page ────────────────────────────────────────────────────────

// Hardcoded neutral palette — intentionally does NOT read from LocalPrismColors
// so the picker chrome stays unbiased regardless of which theme is currently set.
private val ThemePickerBg = Color(0xFF0B0B0F)
private val ThemePickerCardBg = Color(0xFF16161F)
private val ThemePickerText = Color(0xFFE8E8F0)
private val ThemePickerSubtext = Color(0xFF8A8AA8)
private val ThemePickerBorder = Color(0xFF2A2A3A)

private data class OnboardingThemeEntry(
    val theme: PrismTheme,
    val displayName: String,
    val tagline: String
)

private val OnboardingThemeEntries = listOf(
    OnboardingThemeEntry(PrismTheme.CYBERPUNK, "Cyberpunk", "Neon and precise"),
    OnboardingThemeEntry(PrismTheme.SYNTHWAVE, "Synthwave", "Retro and dreamy"),
    OnboardingThemeEntry(PrismTheme.MATRIX, "Matrix", "Terminal and focused"),
    OnboardingThemeEntry(PrismTheme.VOID, "Void", "Calm and minimal")
)

@Composable
private fun ThemePickerPage() {
    val viewModel: ThemeViewModel = hiltViewModel()
    var selectedTheme by remember { mutableStateOf(viewModel.currentTheme.value) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ThemePickerBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 72.dp, bottom = 140.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose your vibe",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    color = ThemePickerText
                ),
                modifier = Modifier.asHeading()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You can always change this in Settings",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Default,
                    color = ThemePickerSubtext
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            OnboardingThemeEntries.forEach { entry ->
                OnboardingThemeCard(
                    entry = entry,
                    isSelected = selectedTheme == entry.theme,
                    onSelect = {
                        selectedTheme = entry.theme
                        viewModel.setTheme(entry.theme)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun OnboardingThemeCard(
    entry: OnboardingThemeEntry,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val themeColors = remember(entry.theme) { prismThemeColors(entry.theme) }
    val themeAttrs = remember(entry.theme) { prismThemeAttrs(entry.theme) }
    val themeFonts = remember(entry.theme) { prismThemeFonts(entry.theme) }
    val borderColor = if (isSelected) themeColors.primary else ThemePickerBorder
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ThemePickerCardBg),
        border = BorderStroke(borderWidth, borderColor)
    ) {
        CompositionLocalProvider(
            LocalPrismColors provides themeColors,
            LocalPrismAttrs provides themeAttrs,
            LocalPrismFonts provides themeFonts
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.displayName,
                            fontFamily = LocalPrismFonts.current.display,
                            fontWeight = FontWeight.Bold,
                            color = LocalPrismColors.current.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = entry.tagline,
                            fontFamily = LocalPrismFonts.current.body,
                            color = LocalPrismColors.current.muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "${entry.displayName} selected",
                            tint = LocalPrismColors.current.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OnboardingMockTaskCard()
            }
        }
    }
}

// Mini task card rendered entirely in the surrounding theme's tokens via
// CompositionLocalProvider — gives each OnboardingThemeCard an authentic
// live preview without a DataStore round-trip.
@Composable
private fun OnboardingMockTaskCard() {
    val colors = LocalPrismColors.current
    val fonts = LocalPrismFonts.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Review weekly goals",
                    fontFamily = fonts.body,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Today",
                    fontFamily = fonts.body,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
