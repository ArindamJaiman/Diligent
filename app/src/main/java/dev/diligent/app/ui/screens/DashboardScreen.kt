package dev.diligent.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.diligent.app.ui.components.ActivityCard
import dev.diligent.app.ui.theme.Cinzel
import dev.diligent.app.ui.theme.DiligentColors
import dev.diligent.app.ui.theme.DotMatrix
import dev.diligent.app.ui.viewmodel.DashboardViewModel
import dev.diligent.app.ui.viewmodel.DashboardViewModel.SortMode

/**
 * Main dashboard screen showing all activities with today's progress.
 * Features:
 * - Search bar with dot-matrix styling
 * - Sort toggle row
 * - Animated activity cards with inline controls
 * - FAB to add new activity
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val activities by viewModel.activitiesWithProgress.collectAsStateWithLifecycle()
    val streaks by viewModel.streaks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DiligentColors.Black,
        topBar = {
            // Custom top bar — no standard TopAppBar for more control
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Title row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Diligent",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = Cinzel,
                            letterSpacing = 2.sp
                        ),
                        color = DiligentColors.White
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onNavigateToStats) {
                            Icon(
                                imageVector = Icons.Outlined.Analytics,
                                contentDescription = "Statistics",
                                tint = DiligentColors.Gray700
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = DiligentColors.Gray700
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = {
                        Text(
                            "search activities...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = DotMatrix),
                            color = DiligentColors.Gray500
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = DotMatrix,
                        color = DiligentColors.White
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = DiligentColors.Gray400,
                        focusedBorderColor = DiligentColors.White,
                        cursorColor = DiligentColors.White,
                        unfocusedContainerColor = DiligentColors.Gray100,
                        focusedContainerColor = DiligentColors.Gray100
                    ),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = DiligentColors.Gray500,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = DiligentColors.Gray500,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Sort row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${activities.size} ACTIVITIES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = DotMatrix,
                            letterSpacing = 2.sp
                        ),
                        color = DiligentColors.Gray500
                    )

                    Box {
                        TextButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = null,
                                tint = DiligentColors.Gray600,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = sortMode.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = DotMatrix,
                                    letterSpacing = 1.sp
                                ),
                                color = DiligentColors.Gray600
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            containerColor = DiligentColors.SurfaceElevated
                        ) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = mode.name,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = DotMatrix
                                            ),
                                            color = if (mode == sortMode) DiligentColors.White
                                            else DiligentColors.Gray600
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortMode(mode)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToEdit(0L) },
                containerColor = DiligentColors.White,
                contentColor = DiligentColors.Black,
                shape = CircleShape,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Activity"
                )
            }
        }
    ) { paddingValues ->
        if (activities.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "·  ·  ·",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontFamily = DotMatrix,
                            letterSpacing = 8.sp
                        ),
                        color = DiligentColors.Gray400
                    )
                    Text(
                        text = "no activities yet",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray500
                    )
                    Text(
                        text = "tap + to begin tracking",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = DotMatrix
                        ),
                        color = DiligentColors.Gray600
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = activities,
                    key = { it.activity.id }
                ) { item ->
                    ActivityCard(
                        item = item,
                        streak = streaks[item.activity.id] ?: 0,
                        onIncrement = { viewModel.incrementProgress(item.activity.id) },
                        onDecrement = { viewModel.decrementProgress(item.activity.id) },
                        onToggleComplete = { viewModel.toggleComplete(item.activity.id) },
                        onClick = { onNavigateToDetail(item.activity.id) },
                        modifier = Modifier.animateItem()
                    )
                }

                // Bottom spacer for FAB
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}
