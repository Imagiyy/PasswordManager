package com.vaultmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Item detail screen — view, edit, and manage a single credential.
 *
 * Features:
 *   - View/edit all credential fields
 *   - Masked password with show/hide toggle
 *   - Copy username/password to clipboard
 *   - Delete credential with confirmation
 *   - Save changes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    viewModel: VaultViewModel,
    itemId: String,
    onBack: () -> Unit,
    onResetIdle: () -> Unit
) {
    val items by viewModel.vaultItems.collectAsState()
    val existingItem = items.find { it.id == itemId }
    val isNewItem = existingItem == null

    var name by remember { mutableStateOf(existingItem?.name ?: "") }
    var username by remember { mutableStateOf(existingItem?.username ?: "") }
    var password by remember { mutableStateOf(existingItem?.password ?: "") }
    var url by remember { mutableStateOf(existingItem?.url ?: "") }
    var notes by remember { mutableStateOf(existingItem?.notes ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    onResetIdle()
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isNewItem) "Add Credential" else "Edit Credential")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isNewItem) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Name ────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onResetIdle() },
                label = { Text("Name") },
                placeholder = { Text("e.g., GitHub") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Username ────────────────────────────────────────
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; onResetIdle() },
                label = { Text("Username / Email") },
                placeholder = { Text("user@example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        copyToClipboard(context, "Username", username)
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy username")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Password ────────────────────────────────────────
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; onResetIdle() },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                "Toggle password visibility"
                            )
                        }
                        IconButton(onClick = {
                            copyToClipboard(context, "Password", password)
                        }) {
                            Icon(Icons.Default.ContentCopy, "Copy password")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── URL ─────────────────────────────────────────────
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; onResetIdle() },
                label = { Text("URL") },
                placeholder = { Text("https://github.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Notes ───────────────────────────────────────────
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it; onResetIdle() },
                label = { Text("Notes") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Save Button ─────────────────────────────────────
            Button(
                onClick = {
                    onResetIdle()
                    val item = if (isNewItem) {
                        VaultItem(
                            name = name,
                            username = username,
                            password = password,
                            url = url,
                            notes = notes
                        )
                    } else {
                        existingItem!!.copy(
                            name = name,
                            username = username,
                            password = password,
                            url = url,
                            notes = notes
                        ).withUpdatedTimestamp()
                    }
                    viewModel.saveItem(item)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = name.isNotBlank()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save", fontSize = 16.sp)
            }
        }

        // ── Delete Confirmation Dialog ──────────────────────────
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete \"$name\"?") },
                text = {
                    Text("This credential will be permanently removed from your vault.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteItem(itemId)
                            showDeleteDialog = false
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * Copy text to the system clipboard.
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
