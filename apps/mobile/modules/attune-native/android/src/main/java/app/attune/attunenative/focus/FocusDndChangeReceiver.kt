package app.attune.attunenative.focus

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Protected system broadcasts; no polling or foreground service is required. */
class FocusDndChangeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val policyChanged = when (intent.action) {
      NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED -> true
      NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> false
      else -> return
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val observed = runCatching {
      if (!policyChanged) manager.currentInterruptionFilter.toString() else manager.notificationPolicy.let {
        listOf(it.priorityCategories, it.priorityCallSenders, it.priorityMessageSenders,
          if (Build.VERSION.SDK_INT >= 24) it.suppressedVisualEffects else 0,
          if (Build.VERSION.SDK_INT >= 30) it.priorityConversationSenders else 0).joinToString(",")
      }
    }.getOrNull()
    val receivedAtMs = System.currentTimeMillis()
    val result = goAsync()
    FocusReceiverExecutor.executor.execute {
      try { FocusRuntime.get(context).onDndChanged(policyChanged, observed, receivedAtMs) }
      finally { result.finish() }
    }
  }
}
