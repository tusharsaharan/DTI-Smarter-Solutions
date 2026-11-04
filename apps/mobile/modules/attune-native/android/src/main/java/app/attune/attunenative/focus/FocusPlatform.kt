package app.attune.attunenative.focus

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors

/** Inexact native alarms: no exact-alarm permission, polling loop, FGS, mic or camera. */
internal class FocusPlatform(private val context: Context) {
  private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
  private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

  fun warningAvailable(): Boolean = runCatching {
    val manager = notifications ?: return false
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= 29 && manager.areNotificationsPaused()) return false
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Smart Focus warnings", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "A one-minute warning before a local Focus prediction changes Do Not Disturb"
      })
      val channel = manager.getNotificationChannel(CHANNEL) ?: return false
      if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
      if (Build.VERSION.SDK_INT >= 28 && channel.group != null && manager.getNotificationChannelGroup(channel.group)?.isBlocked == true) return false
    }
    true
  }.getOrDefault(false)

  fun postWarning(pending: FocusPending): Boolean = runCatching {
    if (!warningAvailable()) return false
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
    open.action = Intent.ACTION_VIEW
    open.data = Uri.parse("attune://feature/focus")
    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    // An activity PendingIntent directly opens the app; no notification trampoline.
    val openIntent = PendingIntent.getActivity(context, 7132, open,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val skip = PendingIntent.getBroadcast(context, 7133, Intent(context, FocusAlarmReceiver::class.java)
      .setAction(ACTION_SKIP).setData(Uri.parse("attune-focus://skip/${pending.id}"))
      .putExtra(EXTRA_TOKEN, pending.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
    val notification = builder.setSmallIcon(android.R.drawable.ic_lock_silent_mode)
      .setContentTitle("Focus may start in 1 minute")
      .setContentText("Local prediction, not certainty. Skip to keep interruptions on.")
      .setCategory(Notification.CATEGORY_REMINDER).setPriority(Notification.PRIORITY_HIGH)
      .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setAutoCancel(false)
      .setContentIntent(openIntent).setWhen(pending.deadlineAtMs).setShowWhen(pending.confirmed).setUsesChronometer(pending.confirmed)
      .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Skip", skip).build())
      .setOnlyAlertOnce(true)
    if (Build.VERSION.SDK_INT >= 24) builder.setChronometerCountDown(pending.confirmed)
    builder.extras.putString(EXTRA_TOKEN, pending.id)
    notifications?.notify(TAG, NOTIFICATION_ID, builder.build()) ?: return false
    // System notification posting is asynchronous. The runtime checks actual presence once
    // shortly after this accepted post, and again before the DND write, without polling.
    warningAvailable()
  }.getOrDefault(false)

  /** Also checks dismissal/app/channel revocation immediately before the real DND write. */
  fun warningVisible(token: String): Boolean = runCatching {
    warningAvailable() && notifications?.activeNotifications?.any {
      it.tag == TAG && it.id == NOTIFICATION_ID && it.notification.extras.getString(EXTRA_TOKEN) == token
    } == true
  }.getOrDefault(false)

  fun cancelWarning(token: String? = null) { runCatching {
    notifications?.cancel(TAG, NOTIFICATION_ID)
    if (token != null) PendingIntent.getBroadcast(context, 7133,
      Intent(context, FocusAlarmReceiver::class.java).setAction(ACTION_SKIP)
        .setData(Uri.parse("attune-focus://skip/$token")),
      PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
  } }

  fun schedule(slot: String, atMs: Long, token: String = ""): Boolean = runCatching {
    val manager = alarms ?: return false
    val operation = alarmIntent(slot, token)
    // ELAPSED_REALTIME counts sleep; the runtime separately rejects civil clock changes.
    val trigger = SystemClock.elapsedRealtime() + (atMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
    if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
    else manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, operation)
    true
  }.getOrDefault(false)

  fun cancel(slot: String) { runCatching { alarms?.cancel(alarmIntent(slot, "")) } }

  private fun alarmIntent(slot: String, token: String): PendingIntent = PendingIntent.getBroadcast(context,
    when (slot) { DEADLINE -> 7141; END -> 7142; else -> 7140 },
    Intent(context, FocusAlarmReceiver::class.java).setAction(ACTION_ALARM)
      .setData(Uri.parse("attune-focus://alarm/$slot")).putExtra(EXTRA_SLOT, slot).putExtra(EXTRA_TOKEN, token),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  companion object {
    const val EVALUATE = "evaluate"
    const val DEADLINE = "deadline"
    const val END = "end"
    const val ACTION_ALARM = "app.attune.focus.ALARM"
    const val ACTION_SKIP = "app.attune.focus.SKIP"
    const val EXTRA_SLOT = "focus_slot"
    const val EXTRA_TOKEN = "focus_token"
    private const val CHANNEL = "attune_focus_warning_v1"
    private const val TAG = "attune_focus"
    private const val NOTIFICATION_ID = 7131
  }
}

/** Receiver work is bounded on a single local CPU executor; never starts a background FGS. */
class FocusAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in setOf(FocusPlatform.ACTION_ALARM, FocusPlatform.ACTION_SKIP)) return
    val result = goAsync()
    FocusReceiverExecutor.executor.execute {
      try {
        val runtime = FocusRuntime.get(context)
        val token = intent.getStringExtra(FocusPlatform.EXTRA_TOKEN).orEmpty()
        if (intent.action == FocusPlatform.ACTION_SKIP) runtime.skipPending(token)
        else runtime.onAlarm(intent.getStringExtra(FocusPlatform.EXTRA_SLOT).orEmpty(), token)
      } finally { result.finish() }
    }
  }
}

class FocusRescheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
    val result = goAsync()
    FocusReceiverExecutor.executor.execute {
      try { FocusRuntime.get(context).onClockOrBootChanged(intent.action.orEmpty()) }
      finally { result.finish() }
    }
  }
}

internal object FocusReceiverExecutor { val executor = Executors.newSingleThreadExecutor { runnable ->
  Thread(runnable, "AttuneFocusLocal").apply { isDaemon = true }
} }
