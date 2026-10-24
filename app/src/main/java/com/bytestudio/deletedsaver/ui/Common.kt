package com.bytestudio.deletedsaver.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.R
import com.bytestudio.deletedsaver.data.Db
import com.bytestudio.deletedsaver.data.MediaItem
import com.bytestudio.deletedsaver.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Context.app: App get() = applicationContext as App

/** Runs [query] off the main thread and re-runs it whenever the database changes. */
@Composable
fun <T> rememberQuery(initial: T, vararg keys: Any?, query: Db.() -> T): State<T> {
    val db = LocalContext.current.app.db
    return produceState(initial, db, *keys) {
        db.changes.collect { value = withContext(Dispatchers.IO) { db.query() } }
    }
}

fun formatTime(context: Context, ts: Long): String =
    if (DateUtils.isToday(ts)) DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    else DateUtils.formatDateTime(
        context, ts,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

fun openFile(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val ext = file.extension.lowercase()
    val mime = when (ext) {
        "opus" -> "audio/ogg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Simple "are you sure?" dialog for removing things from the app. */
@Composable
fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Removes a saved media file from the app (the original in WhatsApp is untouched). */
fun deleteMedia(context: Context, item: MediaItem) {
    context.app.db.hideMedia(item.id)
    File(item.localPath).delete()
    thumbCache.remove(item.localPath)
}

@Composable
fun DeletedBadge(modifier: Modifier = Modifier, text: String = "Deleted") {
    Text(
        text,
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onError,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val thumbCache = LruCache<String, Bitmap>(120)

@Composable
fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier) {
    val thumb by produceState(thumbCache.get(item.localPath), item.localPath) {
        if (value != null || (item.kind != MediaKind.IMAGE && item.kind != MediaKind.VIDEO)) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(item.localPath)
                val size = Size(320, 320)
                if (item.kind == MediaKind.IMAGE) ThumbnailUtils.createImageThumbnail(f, size, null)
                else ThumbnailUtils.createVideoThumbnail(f, size, null)
            }.getOrNull()?.also { thumbCache.put(item.localPath, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bmp = thumb
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (item.kind == MediaKind.AUDIO) R.drawable.ic_mic else R.drawable.ic_doc),
                    null,
                    Modifier.size(32.dp).align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.kind == MediaKind.AUDIO || item.kind == MediaKind.DOC) {
                Text(
                    item.name, Modifier.align(Alignment.BottomCenter).padding(4.dp),
                    fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.kind == MediaKind.VIDEO) {
            Icon(
                painterResource(R.drawable.ic_play), null,
                Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(6.dp),
                tint = Color.White,
            )
        }
    }
}
