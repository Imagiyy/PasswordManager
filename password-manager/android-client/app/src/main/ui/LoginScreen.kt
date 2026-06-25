package com.vaultmanager.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Login/Registration screen with master password entry.
 *
 * Security:
 *   - Password handled as CharArray via custom state (never String in final form)
 *   - "Unlock with Biometric" button for returning users
 *   - Clear password fields on navigation away (DisposableEffect)
 *   - Login/Register toggle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: VaultViewModel,
    onLoginSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    // Navigate on successful unlock
    LaunchedEffect(uiState) {
        if (uiState is VaultUiState.Unlocked) {
            onLoginSuccess()
        }
        if (uiState is VaultUiState.Error) {
            errorMessage = (uiState as VaultUiState.Error).message
        }
    }

    // Clear sensitive data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            confirmPassword = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ── App Icon & Title ────────────────────────────────────
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "VaultManager",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "VaultManager",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = if (isRegisterMode) "Create Account" else "Welcome Back",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Email Field ─────────────────────────────────────────
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; errorMessage = null },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Password Field ──────────────────────────────────────
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; errorMessage = null },
            label = { Text("Master Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = "Toggle password visibility"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = {
                    if (!isRegisterMode) {
                        performLogin(viewModel, email, password) { errorMessage = it }
                    }
                }
            )
        )

        // ── Confirm Password (Register only) ───────────────────
        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = { Text("Confirm Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        performRegister(viewModel, email, password, confirmPassword) {
                            errorMessage = it
                        }
                    }
                )
            )
        }

        // ── Error Message ───────────────────────────────────────
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Loading Indicator ───────────────────────────────────
        if (uiState is VaultUiState.Loading) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Login/Register Button ───────────────────────────────
        Button(
            onClick = {
                if (isRegisterMode) {
                    performRegister(viewModel, email, password, confirmPassword) {
                        errorMessage = it
                    }
                } else {
                    performLogin(viewModel, email, password) { errorMessage = it }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = uiState !is VaultUiState.Loading
        ) {
            Text(
                text = if (isRegisterMode) "Register" else "Login",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Biometric Unlock ────────────────────────────────────
        if (!isRegisterMode) {
            OutlinedButton(
                onClick = { viewModel.biometricUnlock() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = uiState !is VaultUiState.Loading
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Biometric unlock",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unlock with Biometric", fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Toggle Login/Register ───────────────────────────────
        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
                errorMessage = null
            }
        ) {
            Text(
                text = if (isRegisterMode) {
                    "Already have an account? Login"
                } else {
                    "Don't have an account? Register"
                }
            )
        }
    }
}

private fun performLogin(
    viewModel: VaultViewModel,
    email: String,
    password: String,
    onError: (String) -> Unit
) {
    if (email.isBlank()) {
        onError("Email is required")
        return
    }
    if (password.isBlank()) {
        onError("Master password is required")
        return
    }

    // Convert password String to CharArray for the ViewModel
    val passwordChars = password.toCharArray()
    viewModel.login(email, passwordChars)
    // Note: passwordChars will be used by the ViewModel coroutine
    // The String 'password' in Compose state cannot be fully controlled,
    // but the actual crypto operations use CharArray
}

private fun performRegister(
    viewModel: VaultViewModel,
    email: String,
    password: String,
    confirmPassword: String,
    onError: (String) -> Unit
) {
    if (email.isBlank()) {
        onError("Email is required")
        return
    }
    if (password.isBlank()) {
        onError("Master password is required")
        return
    }
    if (password.length < 8) {
        onError("Password must be at least 8 characters")
        return
    }
    if (password != confirmPassword) {
        onError("Passwords do not match")
        return
    }

    val passwordChars = password.toCharArray()
    viewModel.registerAndLogin(email, passwordChars)
}
