package de.ostlertekker.ultraiptv.core.notify

import kotlinx.coroutines.launch
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.ostlertekker.ultraiptv.MainActivity
import de.ostlertekker.ultraiptv.R
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.ExtrasDao
import de.ostlertekker.ultraiptv.core.database.NotificationEntity
import de.ostlertekker.ultraiptv.core.database.ReminderEntity
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.update.UpdateManager
import de.ostlertekker.ultraiptv.core.update.UpdateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persönliche Benachrichtigungen: Programm-Erinnerungen, neue Folgen bei Lieblingsserien,
 * ablaufendes Konto und verfügbare App-Updates. Alles landet im App-Postfach und – sofern das
 * System es anzeigt – zusätzlich als Android-Benachrichtigung.
 */
@Singleton
class NotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extras: ExtrasDao,
    private val content: ContentDao,
    private val user: UserRepository,
    private val sync: SyncRepository,
    private val updates: UpdateManager,
    private val settingsRepo: de.ostlertekker.ultraiptv.core.data.SettingsRepository,
) {
    val notifications = extras.notifications()
    val unread = extras.unreadCount()
    fun upcomingReminders() = extras.upcomingReminders(System.currentTimeMillis())

    /** Aktuelle Erinnerung als Einblendung oben links (statt Postfach). */
    val popup = kotlinx.coroutines.flow.MutableStateFlow<ReminderEntity?>(null)
    private val shown = java.util.Collections.synchronizedSet(HashSet<Long>())
    /** Neues App-Update als Einblendung (statt selbst in den Einstellungen suchen). */
    val updatePopup = kotlinx.coroutines.flow.MutableStateFlow<UpdateState.Available?>(null)
    val updateState get() = updates.state
    private val prefs = context.getSharedPreferences("update_popup", Context.MODE_PRIVATE)
    private var lastUpdateCheck = 0L

    init {
        createChannel(context)
        // Zuverlässig, solange die App läuft: alle 15 s prüfen, ob eine Erinnerung fällig ist
        // (der System-Wecker wird auf manchen TV-Geräten stark verzögert oder gar nicht ausgelöst)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                runCatching { checkDue() }
                // Alle 5 Minuten (und kurz nach dem Start) nach einem neuen App-Update schauen
                if (System.currentTimeMillis() - lastUpdateCheck > 5 * 60_000L) {
                    lastUpdateCheck = System.currentTimeMillis()
                    runCatching { if (settingsRepo.current().updateCheck) checkAppUpdate() }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    private fun show(r: ReminderEntity) { if (shown.add(r.id)) popup.value = r }

    /** Fällige Erinnerung (ab 2 Min. vor Beginn bis 5 Min. danach) einblenden – jede nur einmal. */
    suspend fun checkDue() {
        val now = System.currentTimeMillis()
        extras.allReminders().filter { it.id !in shown && now >= it.startMs - lead() && now < it.startMs + 5 * 60_000L }
            .minByOrNull { it.startMs }?.let { show(it) }
    }

    suspend fun post(kind: String, title: String, body: String, targetKind: String? = null, targetId: String? = null, system: Boolean = true) {
        extras.insertNotification(NotificationEntity(createdAt = System.currentTimeMillis(), title = title, body = body, kind = kind,
            targetKind = targetKind, targetId = targetId, isRead = false))
        if (system) showSystem(context, title, body, (kind + (targetId ?: "")).hashCode())
    }

    fun laterUpdate() {
        updatePopup.value?.let { prefs.edit().putString("version", it.version).putLong("at", System.currentTimeMillis()).apply() }
        updatePopup.value = null
    }
    suspend fun downloadUpdate(u: UpdateState.Available) = updates.download(u)
    fun canInstallUpdate() = updates.canInstall()
    fun openInstallPermission() = updates.openInstallPermissionSettings()
    fun installUpdate(f: java.io.File) = updates.install(f)

    suspend fun markAllRead() = extras.markAllRead()
    suspend fun clearAll() = extras.clearNotifications()

    // ---------------------------------------------------------- Erinnerungen

    suspend fun toggleReminder(channelId: String, channelName: String, title: String, startMs: Long): Boolean {
        val existing = extras.findReminder(channelId, startMs)
        if (existing != null) {
            extras.deleteReminder(existing.id); cancelAlarm(existing.id); return false
        }
        val id = extras.insertReminder(ReminderEntity(channelId = channelId, channelName = channelName, title = title, startMs = startMs))
        scheduleAlarm(id, startMs - lead())
        return true
    }

    /** Erinnerungen erscheinen immer 2 Minuten vor Sendungsbeginn. */
    private fun lead(): Long = 2 * 60_000L



    suspend fun hasReminder(channelId: String, startMs: Long) = extras.findReminder(channelId, startMs) != null

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleAlarm(id: Long, atMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(atMs, System.currentTimeMillis() + 5_000)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, alarmIntent(id))
        else try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(id)) }
        catch (_: SecurityException) { am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, alarmIntent(id)) }
    }

    private fun cancelAlarm(id: Long) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(id))
    }

    suspend fun fireReminder(id: Long) {
        val r = extras.reminder(id) ?: return
        // Nicht ins Postfach – die App blendet die Erinnerung 30 Sekunden oben links ein
        show(r)
    }

    /** Nach Neustart/Update: offene Erinnerungen neu planen. */
    suspend fun rescheduleAll() {
        extras.purgeReminders(System.currentTimeMillis() - 3_600_000L)
        extras.allReminders().filter { it.startMs > System.currentTimeMillis() }.let { list -> val l = lead(); list.forEach { scheduleAlarm(it.id, it.startMs - l) } }
    }

    // ---------------------------------------------------------- Prüfungen

    /** Synchronisiert und meldet dabei neue Folgen bei Lieblingsserien. */
    suspend fun syncWithChecks(): Boolean {
        val favSeries = user.favoriteIds(ContentKind.SERIES).first()
        val before = if (favSeries.isEmpty()) emptyMap() else content.seriesStamps(favSeries).associate { it.id to it.addedAt }
        val ok = sync.syncAll(includeEpg = true)
        if (ok && before.isNotEmpty()) {
            content.seriesStamps(before.keys.toList()).forEach { s ->
                val old = before[s.id] ?: return@forEach
                if (s.addedAt > old && old > 0) {
                    val name = content.series(s.id)?.name ?: "Serie"
                    post("series", "Neue Folgen: $name", "Bei einer deiner Lieblingsserien gibt es Neuigkeiten.", ContentKind.SERIES.name, s.id)
                }
            }
        }
        checkAccount()
        return ok
    }

    suspend fun checkAccount() {
        val info = sync.accountInfo.value ?: return
        val exp = info.expiresAt ?: return
        val days = (exp - System.currentTimeMillis()) / 86_400_000L
        if (days in 0..7 && extras.countRecent("account", "expiry", System.currentTimeMillis() - 86_400_000L) == 0) {
            post("account", "Dein Anbieter-Konto läuft bald ab", "Noch $days Tag(e) – Ablauf am ${DateFormat.getDateInstance().format(Date(exp))}.", targetId = "expiry")
        }
    }

    suspend fun checkAppUpdate() {
        val st = updates.check()
        if (st !is UpdateState.Available) return
        // „Später“ gedrückt → dieselbe Version frühestens nach 24 h erneut anbieten
        val skipped = prefs.getString("version", null) == st.version && System.currentTimeMillis() - prefs.getLong("at", 0L) < 86_400_000L
        if (!skipped) updatePopup.value = st
    }

    companion object {
        const val CHANNEL = "ultra_general"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "ULTRA IPTV", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        fun showSystem(context: Context, title: String, body: String, id: Int) {
            val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ultra)
                .setContentTitle(title).setContentText(body)
                .setContentIntent(pi).setAutoCancel(true)
                .build()
            // Android 13+: nur mit erteilter Benachrichtigungs-Berechtigung (sonst still überspringen)
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
        }

        /** Plant die automatische Playlist-/EPG-Aktualisierung (0 = aus). */
        fun scheduleAutoUpdate(context: Context, hours: Int) {
            val wm = WorkManager.getInstance(context)
            if (hours <= 0) { wm.cancelUniqueWork(AutoUpdateWorker.NAME); return }
            val req = PeriodicWorkRequestBuilder<AutoUpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(AutoUpdateWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotifyEntryPoint {
    fun notificationCenter(): NotificationCenter
}

/** Feuert Programm-Erinnerungen (AlarmManager). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val center = EntryPointAccessors.fromApplication(context.applicationContext, NotifyEntryPoint::class.java).notificationCenter()
        val pending = goAsync()
        Thread {
            try { runBlocking { center.fireReminder(id) } } finally { pending.finish() }
        }.start()
    }
}

/** Plant Erinnerungen nach einem Geräteneustart neu. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val center = EntryPointAccessors.fromApplication(context.applicationContext, NotifyEntryPoint::class.java).notificationCenter()
        val pending = goAsync()
        Thread { try { runBlocking { center.rescheduleAll() } } finally { pending.finish() } }.start()
    }
}

/** Automatische Playlist-/EPG-Aktualisierung im Hintergrund inkl. Prüfungen. */
class AutoUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val center = EntryPointAccessors.fromApplication(applicationContext, NotifyEntryPoint::class.java).notificationCenter()
        center.syncWithChecks()
        runCatching { center.checkAppUpdate() }
        return Result.success()
    }

    companion object { const val NAME = "ultra_auto_update" }
}
