package com.bytestudio.deletedsaver.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bytestudio.deletedsaver.R
import com.bytestudio.deletedsaver.capture.Cleanup
import com.bytestudio.deletedsaver.capture.MediaBackup
import com.bytestudio.deletedsaver.capture.WaUsage
import com.bytestudio.deletedsaver.data.RawEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupStatus(
    val notificationAccess: Boolean,
    val storageAccess: Boolean,
    val postNotifications: Boolean,
    val batteryUnrestricted: Boolean,
    val usageAccess: Boolean,
    val contentHidden: Boolean,
) {
    val coreReady get() = notificationAccess && storageAccess && usageAccess
}

private fun readStatus(context: Context): SetupStatus {
    val prefs = context.app.prefs
    return SetupStatus(
        notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        storageAccess = MediaBackup.hasAccess(),
        postNotifications = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        usageAccess = WaUsage.hasAccess(context),
        contentHidden = prefs.lastHiddenAt > prefs.lastReadableAt,
    )
}

/** Re-checks permissions every time the screen resumes (e.g. coming back from system settings). */
@Composable
fun rememberSetupStatus(): SetupStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readStatus(context)) }
    LifecycleResumeEffect(Unit) {
        status = readStatus(context)
        onPauseOrDispose { }
    }
    return status
}

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.prefs
    val scope = rememberCoroutineScope()
    val status = rememberSetupStatus()
    // Status re-reads on resume, which happens when the permission dialog closes.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var alerts by remember { mutableStateOf(prefs.alerts) }
    var business by remember { mutableStateOf(prefs.business) }
    var keepDays by remember { mutableIntStateOf(prefs.keepDeletedDays) }
    var scanning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Permissions")
            PermissionRow(
                "Notification access", "Required to read incoming WhatsApp messages.", status.notificationAccess,
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            PermissionRow(
                "All files access", "Required to back up photos, videos, voice notes and documents.", status.storageAccess,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            }
            PermissionRow(
                "Usage access",
                "Required to detect deleted text messages (tells them apart from messages you opened).",
                status.usageAccess,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
            PermissionRow(
                "Show alerts", "Lets the app notify you when something is deleted.", status.postNotifications,
            ) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermissionRow(
                "Unrestricted battery", "Stops the phone from killing the listener in the background.", status.batteryUnrestricted,
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }

            if (status.contentHidden) LockerHelpCard()

            SectionTitle("Options")
            SwitchRow("Alert when something is deleted", alerts) { alerts = it; prefs.alerts = it }
            SwitchRow("Also capture WhatsApp Business", business) { business = it; prefs.business = it }

            Text("Keep deleted items for", style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 0 to "Forever").forEach { (days, label) ->
                    FilterChip(
                        selected = keepDays == days,
                        onClick = {
                            keepDays = days; prefs.keepDeletedDays = days
                            scope.launch(Dispatchers.IO) { Cleanup.run(context.app) }
                        },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "Messages and media that weren't deleted are removed automatically once WhatsApp's " +
                    "\"Delete for everyone\" window (about 2 days 12 hours) has passed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilledTonalButton(
                enabled = !scanning && status.storageAccess,
                onClick = {
                    scanning = true
                    scope.launch {
                        withContext(Dispatchers.IO) { MediaBackup.scan(context.app) }
                        scanning = false
                    }
                },
            ) { Text(if (scanning) "Scanning…" else "Scan media now") }

            Inspector()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Text("✓ On", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        } else {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LockerHelpCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WhatsApp text is hidden", fontWeight = FontWeight.SemiBold)
            Text(
                "Recent WhatsApp notifications reached this app without the message text, so only media can be saved. " +
                    "Your app locker is probably replacing the content. Look in its settings for an option like " +
                    "\"Hide notification content\" / \"Hide notifications\" for WhatsApp and turn it off. " +
                    "WhatsApp itself stays locked.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Use the notification inspector below to confirm: after the change, entries should contain android.text with the real message.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Inspector() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val entries by rememberQuery(emptyList<RawEntry>(), open) { if (open) raws() else emptyList() }

    SectionTitle("Notification inspector")
    Text(
        "Shows exactly what WhatsApp puts in its notifications (last 50). Useful to check whether an app locker hides the text.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show raw notifications") }
        if (open) TextButton(onClick = { context.app.db.clearRaw() }) { Text("Clear") }
    }
    if (open) {
        if (entries.isEmpty()) Text("Nothing captured yet. Get someone to message you on WhatsApp.")
        entries.forEach { e ->
            SelectionContainer {
                Text(
                    "${formatTime(context, e.at)}\n${e.dump}",
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
