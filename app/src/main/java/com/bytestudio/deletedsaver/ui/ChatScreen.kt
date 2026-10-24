package com.bytestudio.deletedsaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bytestudio.deletedsaver.R
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.bytestudio.deletedsaver.data.MediaItem
import com.bytestudio.deletedsaver.data.Msg
import com.bytestudio.deletedsaver.data.appLabel
import com.bytestudio.deletedsaver.data.mediaKindOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(pkg: String, chat: String, onBack: () -> Unit) {
    val messages by rememberQuery(emptyList<Pair<Msg, MediaItem?>>(), pkg, chat) { messagesWithMedia(pkg, chat) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    val context = LocalContext.current
    var confirmChat by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Pair<Msg, MediaItem?>?>(null) }

    if (confirmChat) {
        ConfirmDelete(
            title = "Delete chat?",
            text = "All saved messages from $chat will be removed from this app. WhatsApp is not affected.",
            onConfirm = { context.app.db.hideChat(pkg, chat); onBack() },
            onDismiss = { confirmChat = false },
        )
    }
    toDelete?.let { (msg, media) ->
        ConfirmDelete(
            title = "Delete message?",
            text = if (media != null) "The message and its saved file will be removed from this app."
            else "The message will be removed from this app.",
            onConfirm = {
                context.app.db.hideMessage(msg.id)
                if (media != null) deleteMedia(context, media)
            },
            onDismiss = { toDelete = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chat, maxLines = 1)
                        Text(appLabel(pkg), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmChat = true }) {
                        Icon(painterResource(R.drawable.ic_delete), "Delete chat")
                    }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyState("Nothing saved for this chat.") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.first.id }) { pair ->
                Bubble(pair.first, pair.second, onLongPress = { toDelete = pair })
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, media: MediaItem?, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val deleted = m.deletedAt != null
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .background(
                    if (deleted) colors.errorContainer else colors.surfaceVariant,
                    RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                )
                .then(
                    if (deleted) Modifier.border(1.dp, colors.error, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (m.isGroup) {
                Text(m.sender, style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
            }
            if (media != null) {
                MediaThumb(
                    media,
                    Modifier
                        .padding(vertical = 4.dp)
                        .size(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { openFile(context, media.localPath) }, onLongClick = onLongPress),
                )
            }
            Text(m.text, color = if (deleted) colors.onErrorContainer else colors.onSurfaceVariant)
            if (media == null && mediaKindOf(m.text) != null) {
                Text(
                    "File not saved — WhatsApp didn't auto-download it",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deleted) colors.onErrorContainer else colors.outline,
                )
            }
            Spacer(Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (deleted) {
                    DeletedBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    formatTime(context, m.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deleted) colors.onErrorContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}
