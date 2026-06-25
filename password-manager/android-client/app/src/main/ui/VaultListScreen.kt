package com.vaultmanager.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest

/**
 * Vault list screen — searchable list of credentials.
 *
 * Features:
 *   - Searchable list with name/username display
 *   - FAB for adding new credentials
 *   - Sync status indicator
 *   - Lock button in top bar
 *   - Idle timer reset on pointer input
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    viewModel: VaultViewModel,
    onItemClick: (String) -> Unit,
    onLock: () -> Unit,
    onResetIdle: () -> Unit
) {
    val items by viewModel.vaultItems.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    // Listen for lock events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is VaultEvent.LockEvent -> onLock()
                is VaultEvent.ConflictEvent -> {
                    // TODO: Show conflict resolution dialog
                }
            }
        }
    }

    // Filter items based on search
    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) {
            items
        } else {
            val query = searchQuery.lowercase()
            items.filter {
                it.name.lowercase().contains(query) ||
                    it.username.lowercase().contains(query) ||
                    it.url.lowercase().contains(query)
            }
        }
    }

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
                title = { Text("VaultManager") },
                actions = {
                    IconButton(onClick = { viewModel.syncNow(); onResetIdle() }) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync")
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    onResetIdle()
                    val newItem = VaultItem()
                    onItemClick(newItem.id)
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add credential")
            }
        },
        bottomBar = {
            if (syncStatus.isNotBlank()) {
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = syncStatus,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Search Bar ──────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; onResetIdle() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search vault...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                },
                singleLine = true
            )

            // ── Item Count ──────────────────────────────────────
            Text(
                text = "${filteredItems.size} items",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── Vault List ──────────────────────────────────────
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "No credentials yet.\nTap + to add one."
                        } else {
                            "No results for \"$searchQuery\""
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(
                        items = filteredItems,
                        key = { it.id }
                    ) { item ->
                        VaultListItem(
                            item = item,
                            onClick = {
                                onResetIdle()
                                onItemClick(item.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single vault item row in the list.
 */
@Composable
private fun VaultListItem(
    item: VaultItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = item.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.username.isNotBlank()) {
                Text(
                    text = item.username,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    HorizontalDivider()
}
