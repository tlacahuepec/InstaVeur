package com.santi.metamediasaver.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.santi.metamediasaver.data.model.AuthUser
import com.santi.metamediasaver.data.model.ConnectedAccount
import com.santi.metamediasaver.data.model.DownloadRecord
import com.santi.metamediasaver.data.model.DownloadState
import com.santi.metamediasaver.data.model.MediaItem
import com.santi.metamediasaver.data.model.MediaType
import com.santi.metamediasaver.data.model.SourceType
import com.santi.metamediasaver.ui.theme.MetaMediaSaverTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onConnect: () -> Unit,
    onRefreshAccounts: () -> Unit,
    onRefreshMedia: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onDisconnect: () -> Unit,
    onLoadMore: () -> Unit,
    onDownload: (MediaItem) -> Unit,
    onRetryDownload: (DownloadRecord) -> Unit,
    onCancelDownload: (DownloadRecord) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingDownload by remember { mutableStateOf<MediaItem?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingDownload
        pendingDownload = null
        if (granted && item != null) {
            onDownload(item)
        }
    }

    fun downloadWithPermission(item: MediaItem) {
        if (needsLegacyWritePermission(context)) {
            pendingDownload = item
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            onDownload(item)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Library", maxLines = 1)
                        Text(
                            text = state.user.username,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshAccounts) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh accounts")
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AccountBar(
                state = state,
                onConnect = onConnect,
                onSelectAccount = onSelectAccount,
                onDisconnect = onDisconnect,
                onRefreshMedia = onRefreshMedia
            )

            if (state.error != null) {
                ErrorBand(message = state.error)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoadingMedia || state.isLoadingAccounts -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    state.accounts.isEmpty() -> {
                        EmptyAccounts(onConnect = onConnect, isConnecting = state.isConnecting)
                    }
                    state.media.isEmpty() -> {
                        EmptyMedia(onRefreshMedia = onRefreshMedia)
                    }
                    else -> {
                        MediaGrid(
                            media = state.media,
                            hasMore = state.nextCursor != null,
                            isLoadingMore = state.isLoadingMore,
                            onLoadMore = onLoadMore,
                            onDownload = ::downloadWithPermission,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            DownloadQueue(
                downloads = state.downloads,
                onRetry = onRetryDownload,
                onCancel = onCancelDownload
            )
        }
    }
}

@Composable
private fun AccountBar(
    state: HomeUiState,
    onConnect: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefreshMedia: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !state.isConnecting
                ) {
                    Icon(Icons.Outlined.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect Meta")
                }
                OutlinedButton(
                    onClick = onRefreshMedia,
                    enabled = state.selectedAccountId != null && !state.isLoadingMedia
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh")
                }
            }

            IconButton(
                onClick = onDisconnect,
                enabled = state.selectedAccountId != null
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Disconnect selected account")
            }
        }

        if (state.accounts.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.accounts, key = { it.id }) { account ->
                    FilterChip(
                        selected = account.id == state.selectedAccountId,
                        onClick = { onSelectAccount(account.id) },
                        label = {
                            Text(
                                text = account.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAccounts(
    onConnect: () -> Unit,
    isConnecting: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No connected accounts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onConnect, enabled = !isConnecting) {
                Icon(Icons.Outlined.Link, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect Meta")
            }
        }
    }
}

@Composable
private fun EmptyMedia(onRefreshMedia: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("No media found", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRefreshMedia) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun ErrorBand(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MediaGrid(
    media: List<MediaItem>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onDownload: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        modifier = modifier,
        columns = GridCells.Adaptive(minSize = 170.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(media, key = { it.id }) { item ->
            MediaCard(
                item = item,
                onDownload = { onDownload(item) }
            )
        }

        if (hasMore) {
            item {
                Button(
                    onClick = onLoadMore,
                    enabled = !isLoadingMore,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isLoadingMore) "Loading" else "Load more")
                }
            }
        }
    }
}

@Composable
private fun MediaCard(
    item: MediaItem,
    onDownload: () -> Unit
) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box {
            AsyncImage(
                model = item.previewUrl,
                contentDescription = item.caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            if (item.mediaType == MediaType.VIDEO) {
                AssistChip(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    onClick = {},
                    label = { Text("Video") },
                    leadingIcon = {
                        Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                    }
                )
            }
        }

        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.caption?.takeIf { it.isNotBlank() } ?: item.sourceType.name.lowercase(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.timestamp?.take(10).orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onDownload,
                    enabled = item.downloadable
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "Download")
                }
            }
        }
    }
}

@Composable
private fun DownloadQueue(
    downloads: List<DownloadRecord>,
    onRetry: (DownloadRecord) -> Unit,
    onCancel: (DownloadRecord) -> Unit
) {
    if (downloads.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        downloads.take(4).forEach { record ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (record.state == DownloadState.RUNNING) {
                        LinearProgressIndicator(
                            progress = { record.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = record.state.name.lowercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (record.state == DownloadState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                when (record.state) {
                    DownloadState.FAILED -> IconButton(onClick = { onRetry(record) }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = "Retry download")
                    }
                    DownloadState.QUEUED,
                    DownloadState.RUNNING -> IconButton(onClick = { onCancel(record) }) {
                        Icon(Icons.Outlined.Cancel, contentDescription = "Cancel download")
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun needsLegacyWritePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED

@Preview(showBackground = true, name = "Home - Empty")
@Composable
private fun HomeScreenEmptyPreview() {
    MetaMediaSaverTheme {
        HomeScreen(
            state = previewHomeState(),
            onConnect = {},
            onRefreshAccounts = {},
            onRefreshMedia = {},
            onSelectAccount = {},
            onDisconnect = {},
            onLoadMore = {},
            onDownload = {},
            onRetryDownload = {},
            onCancelDownload = {},
            onSignOut = {}
        )
    }
}

@Preview(showBackground = true, name = "Home - Content")
@Composable
private fun HomeScreenContentPreview() {
    MetaMediaSaverTheme {
        HomeScreen(
            state = previewHomeState(hasMedia = true, hasDownloads = true),
            onConnect = {},
            onRefreshAccounts = {},
            onRefreshMedia = {},
            onSelectAccount = {},
            onDisconnect = {},
            onLoadMore = {},
            onDownload = {},
            onRetryDownload = {},
            onCancelDownload = {},
            onSignOut = {}
        )
    }
}

private fun previewHomeState(
    hasMedia: Boolean = false,
    hasDownloads: Boolean = false
): HomeUiState {
    val accounts = listOf(
        ConnectedAccount(
            id = "account_1",
            displayName = "Preview Account",
            username = "preview_user",
            sourceType = SourceType.INSTAGRAM,
            avatarUrl = null
        )
    )
    return HomeUiState(
        user = AuthUser(uid = "preview_uid", email = "preview@example.com", username = "preview"),
        accounts = accounts,
        selectedAccountId = accounts.first().id,
        media = if (hasMedia) {
            listOf(
                MediaItem(
                    id = "media_1",
                    accountId = accounts.first().id,
                    caption = "Sunset at the beach",
                    mediaType = MediaType.IMAGE,
                    mediaUrl = "https://example.com/full.jpg",
                    thumbnailUrl = "https://example.com/thumb.jpg",
                    permalink = null,
                    sourceType = SourceType.INSTAGRAM,
                    timestamp = "2026-04-18T10:00:00Z"
                ),
                MediaItem(
                    id = "media_2",
                    accountId = accounts.first().id,
                    caption = "Short reel",
                    mediaType = MediaType.VIDEO,
                    mediaUrl = "https://example.com/video.mp4",
                    thumbnailUrl = null,
                    permalink = null,
                    sourceType = SourceType.INSTAGRAM,
                    timestamp = "2026-04-18T12:00:00Z"
                )
            )
        } else {
            emptyList()
        },
        nextCursor = if (hasMedia) "next_page" else null,
        downloads = if (hasDownloads) {
            listOf(
                DownloadRecord(
                    workId = "work_1",
                    mediaId = "media_1",
                    title = "Sunset at the beach",
                    state = DownloadState.RUNNING,
                    progress = 45,
                    localUri = null,
                    error = null,
                    retryUrl = null,
                    retryMediaType = MediaType.IMAGE
                )
            )
        } else {
            emptyList()
        }
    )
}
