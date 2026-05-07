package com.averycorp.prismtask.ui.screens.auth

import android.util.Patterns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Email/password sign-up + sign-in form. Collapsed by default behind a
 * "Use Email Instead" toggle so the Google button stays the primary CTA.
 *
 * Email format is validated client-side via [Patterns.EMAIL_ADDRESS]; the
 * password 6-char minimum is enforced by Firebase server-side and surfaced
 * via the caller's auth state error channel.
 *
 * Shared between the dedicated sign-in screen and the onboarding welcome
 * page so users without a Google account discover the option in the
 * primary first-launch flow, not only via Settings post-onboarding.
 */
@Composable
internal fun EmailAuthSection(
    onSignUp: (email: String, password: String) -> Unit,
    onSignIn: (email: String, password: String) -> Unit,
    expandLabel: String = "Use Email Instead"
) {
    var expanded by remember { mutableStateOf(false) }
    var registerMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    if (!expanded) {
        TextButton(onClick = { expanded = true }) {
            Text(expandLabel)
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
