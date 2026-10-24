package com.bytestudio.deletedsaver.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import com.bytestudio.deletedsaver.data.MediaItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytestudio.deletedsaver.R
import com.bytestudio.deletedsaver.data.ChatSummary
import com.bytestudio.deletedsaver.data.PKG_BUSINESS
import com.bytestudio.deletedsaver.data.appLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    startOnMedia: Boolean,
    onOpenChat: (pkg: String, chat: String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(if (startOnMedia) 1 else 0) }
    var deletedOnly by rememberSaveable { mutableStateOf(false) }
    val status = rememberSetupStatus()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), "Search") }
                    IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!status.coreReady || status.contentHidden) {
                SetupBanner(status, onSettings)
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chats") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Media") })
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !deletedOnly, onClick = { deletedOnly = false }, label = { Text("All") })
                FilterChip(selected = deletedOnly, onClick = { deletedOnly = true }, label = { Text("Deleted only") })
            }
            if (tab == 0) ChatList(deletedOnly, onOpenChat) else MediaGrid(deletedOnly)
        }
    }
}

@Composable
private fun SetupBanner(status: SetupStatus, onSettings: () -> Unit) {
    Card(
        onClick = onSettings,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (!status.coreReady) "Setup needed" else "Notification text is hidden",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                if (!status.coreReady) "Grant the missing permissions so messages and media can be saved. Tap to open settings."
                else "WhatsApp notifications arrive without message text (likely your app locker). Text can't be saved until that's turned off. Tap for details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ChatList(deletedOnly: Boolean, onOpenChat: (String, String) -> Unit) {
    val context = LocalContext.current
    val chats by rememberQuery(emptyList<ChatSummary>(), deletedOnly) { chats(deletedOnly) }
    var toDelete by remember { mutableStateOf<ChatSummary?>(null) }
    toDelete?.let { c ->
        ConfirmDelete(
            title = "Delete chat?",
            text = "All saved messages from ${c.chat} will be removed from this app. WhatsApp is not affected.",
            onConfirm = { context.app.db.hideChat(c.pkg, c.chat) },
            onDismiss = { toDelete = null },
        )
    }
    if (chats.isEmpty()) {
        EmptyState(
            if (deletedOnly) "No deleted messages yet."
            else "No messages yet.\nNew WhatsApp messages will show up here and are kept for ~2.5 days. Deleted ones are kept longer."
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(chats, key = { it.pkg + "\u0000" + it.chat }) { c ->
            ChatRow(c, onClick = { onOpenChat(c.pkg, c.chat) }, onLongPress = { toDelete = c })
            HorizontalDivider(Modifier.padding(start = 76.dp))
        }
    }
}

@Composable
private fun ChatRow(c: ChatSummary, onClick: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.chat)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.chat, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (c.pkg == PKG_BUSINESS) {
                    Spacer(Modifier.width(6.dp))
                    Text(appLabel(c.pkg), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                c.lastText, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTime(context, c.lastTs), style = MaterialTheme.typography.labelSmall)
            if (c.deleted > 0) {
                Spacer(Modifier.padding(top = 4.dp))
                DeletedBadge(text = "${c.deleted} deleted")
            }
        }
    }
}

@Composable
private fun MediaGrid(deletedOnly: Boolean) {
    val context = LocalContext.current
    val items by rememberQuery(emptyList(), deletedOnly) { media(deletedOnly) }
    var toDelete by remember { mutableStateOf<MediaItem?>(null) }
    toDelete?.let { item ->
        ConfirmDelete(
            title = "Delete file?",
            text = "${item.name} will be removed from this app. The original in WhatsApp is not affected.",
            onConfirm = { deleteMedia(context, item) },
            onDismiss = { toDelete = null },
        )
    }
    if (items.isEmpty()) {
        EmptyState(
            if (deletedOnly) "No deleted media yet."
            else "No media yet.\nPhotos, videos, voice notes and documents that WhatsApp auto-downloads are backed up here."
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Box(
                Modifier.aspectRatio(1f).combinedClickable(
                    onClick = { openFile(context, item.localPath) },
                    onLongClick = { toDelete = item },
                )
            ) {
                MediaThumb(item, Modifier.fillMaxSize())
                if (item.deletedAt != null) DeletedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
