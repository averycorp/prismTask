package com.averycorp.prismtask.ui.screens.auth

import android.app.Activity
import android.util.Log
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.averycorp.prismtask.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val WEB_CLIENT_ID = BuildConfig.WEB_CLIENT_ID

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onContinue: () -> Unit
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Navigate away once sign-in succeeds
    if (authState is AuthState.SignedIn) {
        LaunchedEffect(Unit) {
            onContinue()
        }
    }

    // Branch on the deletion-related states BEFORE rendering the normal sign-in
    // UI. Both states are full-screen takeovers — they intentionally don't let
    // the user fall back to the standard auth screen, since either choice
    // (restore vs. confirm deletion) needs an explicit decision before sync runs.
    when (val state = authState) {
        is AuthState.RestorePending -> {
            RestoreAccountPrompt(
                scheduledFor = state.scheduledFor,
                onRestore = viewModel::onRestoreAccount,
                onSignOut = viewModel::onAbandonRestore
            )
            return
        }
        AuthState.AccountPurged -> {
            AccountPurgedNotice(
                onDismiss = {
                    viewModel.onSkipSignIn()
                    onContinue()
                }
            )
            return
        }
        else -> {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "PrismTask",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sync your tasks across devices",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        when (authState) {
            is AuthState.Loading -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Signing in\u2026", style = MaterialTheme.typography.bodyMedium)
            }
            is AuthState.Error -> {
                Text(
                    text = (authState as? AuthState.Error)?.message ?: "Unknown error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            else -> {}
        }

        if (authState !is AuthState.Loading) {
            Button(
                onClick = onClick@{
                    // Unwrap ContextWrapper chain to find the hosting Activity;
                    // a direct `context as Activity` cast breaks if the composable
                    // is hosted inside a ContextWrapper (dialogs, tooltips).
                    val activity = run {
                        var ctx = context
                        while (ctx is android.content.ContextWrapper && ctx !is Activity) {
                            ctx = ctx.baseContext
                        }
                        ctx as? Activity
                    }
                    if (activity == null) {
                        viewModel.onSignInError("Could not locate hosting Activity")
                        return@onClick
                    }
                    scope.launch {
                        val credentialManager = CredentialManager.create(context)

                        suspend fun requestAuthorized(): GetCredentialResponse {
                            val googleIdOption = GetGoogleIdOption
                                .Builder()
                                .setServerClientId(WEB_CLIENT_ID)
                                .setFilterByAuthorizedAccounts(true)
                                .setAutoSelectEnabled(true)
                                .build()
                            val request = GetCredentialRequest
                                .Builder()
                                .addCredentialOption(googleIdOption)
                                .build()
                            return credentialManager.getCredential(activity, request)
                        }

                        suspend fun requestSignInButton(): GetCredentialResponse {
                            val signInOption = GetSignInWithGoogleOption
                                .Builder(WEB_CLIENT_ID)
                                .build()
                            val request = GetCredentialRequest
                                .Builder()
                                .addCredentialOption(signInOption)
                                .build()
                            return credentialManager.getCredential(activity, request)
                        }

                        try {
                            // First try returning users (authorized accounts).
                            // Only fall back on NoCredentialException — other
                            // failures (user cancelled, network, etc.) should
                            // surface as-is rather than silently retrying.
                            val result = try {
                                requestAuthorized()
                            } catch (_: NoCredentialException) {
                                requestSignInButton()
                            }

                            val credential = result.credential
                            if (credential is CustomCredential &&
                                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                            ) {
                                try {
                                    val googleIdTokenCredential =
                                        GoogleIdTokenCredential.createFrom(credential.data)
                                    viewModel.onGoogleSignIn(googleIdTokenCredential.idToken)
                                } catch (e: GoogleIdTokenParsingException) {
                                    Log.e("AuthScreen", "Failed to parse Google ID token", e)
                                    // Clear cached credential — a malformed
                                    // token usually means the cached account
                                    // needs reauth.
                                    runCatching {
                                        credentialManager.clearCredentialState(
                                            androidx.credentials.ClearCredentialStateRequest()
                                        )
                                    }
                                    viewModel.onSignInError(
                                        "Google account needs to be re-authenticated. Please try again."
                                    )
                                }
                            } else {
                                Log.e("AuthScreen", "Unexpected credential type: ${credential.type}")
                                viewModel.onSignInError("Unexpected credential type")
                            }
                        } catch (_: GetCredentialCancellationException) {
                            // User dismissed the sheet — return to idle.
                            viewModel.onSignInError("Sign-in cancelled")
                        } catch (e: GetCredentialException) {
                            Log.e("AuthScreen", "Sign-in failed", e)
                            viewModel.onSignInError("Google sign-in failed")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign In with Google")
            }

            if (BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATOR) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { viewModel.signInAsEmulatorTestUser() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign in as test user (emulator)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            EmailAuthSection(
                onSignUp = viewModel::onEmailSignUp,
                onSignIn = viewModel::onEmailSignIn
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = {
                viewModel.onSkipSignIn()
                onContinue()
            }) {
                Text("Continue Without Account")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Your data stays on this device until you sign in",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Email/password sign-up + sign-in form. Collapsed by default behind a
 * "Use Email Instead" toggle so the Google button stays the primary CTA.
 * Email format is validated client-side via [Patterns.EMAIL_ADDRESS]; the
 * password 6-char minimum is enforced by Firebase server-side and surfaced
 * via AuthState.Error.
 */
@Composable
private fun EmailAuthSection(
    onSignUp: (email: String, password: String) -> Unit,
    onSignIn: (email: String, password: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var registerMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    if (!expanded) {
        TextButton(onClick = { expanded = true }) {
            Text("Use Email Instead")
        }
        return
    }

    val emailValid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordValid = password.length >= 6
    val canSubmit = emailValid && passwordValid

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("At Least 6 Characters") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                val trimmed = email.trim()
                if (registerMode) onSignUp(trimmed, password) else onSignIn(trimmed, password)
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (registerMode) "Create Account" else "Sign In")
        }
        TextButton(
            onClick = { registerMode = !registerMode },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (registerMode) {
                    "Already Have an Account? Sign In"
                } else {
                    "New User? Create an Account"
                }
            )
        }
    }
}

/**
 * Shown after sign-in when the account is pending deletion within the grace
 * window. Two choices: restore (cancel deletion + proceed to normal sync) or
 * sign out (let the deletion proceed; permanent purge fires on next post-grace
 * sign-in). Sync has not been started yet — we don't pull data into a Room DB
 * that's about to be wiped.
 */
@Composable
private fun RestoreAccountPrompt(
    scheduledFor: Date,
    onRestore: () -> Unit,
    onSignOut: () -> Unit
) {
    val formattedDate = remember(scheduledFor) {
        DateFormat.getDateInstance(DateFormat.LONG).format(scheduledFor)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Account scheduled for deletion",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your account will be permanently deleted on $formattedDate. " +
                "Restore now to keep your data, or sign out to let the deletion proceed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore Account")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign Out")
        }
    }
}

/**
 * Shown when the post-grace permanent purge has already executed. The user's
 * Firebase Auth record is gone (deleted by the backend via Admin SDK) so
 * they cannot sign in again with this account. The single "Continue" action
 * routes to the local-only flow — they can use the app without an account
 * or sign in with a different one.
 */
@Composable
private fun AccountPurgedNotice(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Account permanently deleted",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your account and all synced data have been permanently deleted. " +
                "You can continue using PrismTask without an account or sign in with a different account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}
