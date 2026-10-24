package com.bytestudio.deletedsaver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bytestudio.deletedsaver.R
import com.bytestudio.deletedsaver.data.Msg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenChat: (String, String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val results by rememberQuery(emptyList<Msg>(), q) { if (q.length < 2) emptyList() else search(q) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search messages or contacts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                q.length < 2 -> EmptyState("Type at least 2 characters.")
                results.isEmpty() -> EmptyState("No matches.")
                else -> LazyColumn {
                    items(results, key = { it.id }) { m ->
                        ResultRow(m) { onOpenChat(m.pkg, m.chat) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(m: Msg, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (m.isGroup) "${m.sender} · ${m.chat}" else m.chat,
                Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (m.deletedAt != null) {
                DeletedBadge()
                Spacer(Modifier.width(8.dp))
            }
            Text(formatTime(context, m.ts), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            m.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
