package com.heygobi.app.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Reads other apps' notifications (WhatsApp, SMS apps, ...) so Hey Gobi
 * can read them aloud and reply by voice.
 *
 * Everything stays on the phone: notifications are kept only in memory
 * (the last 30, newest first), never written to disk, never uploaded.
 * The user enables this once in Setup (or Settings) — Android shows a
 * warning when enabling it, which is normal: the app can only read
 * notifications because the user explicitly allowed it.
 *
 * Replies use the notification's own reply action (RemoteInput), so a
 * reply works exactly where the messaging app itself offers a reply
 * button — WhatsApp, Telegram, SMS apps and the like.
 */
class GobiNotificationListenerService : NotificationListenerService() {

    data class HeardNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val timeMillis: Long,
        val replyAction: Notification.Action?
    ) {
        val canReply: Boolean get() = replyAction != null
    }

    companion object {
        private const val MAX_KEPT = 30
        private const val MAX_SUMMARY_ITEMS = 5
        private const val MAX_TEXT_CHARS = 120

        private val lock = Any()
        private val recent = ArrayDeque<HeardNotification>()

        /** True when the user enabled notification access for this app. */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** Newest-first snapshot of the notifications kept in memory. */
        fun recentNotifications(): List<HeardNotification> = synchronized(lock) {
            recent.toList()
        }

        /**
         * Plain Tanglish-friendly summary for reading aloud, e.g.
         * "WhatsApp nunchi 2 messages: Raju — hello. Telegram nunchi 1 message: Neha — call me".
         */
        fun summaryText(): String {
            val list = recentNotifications()
            if (list.isEmpty()) return "Ye kottha notifications levu"
            val shown = list.take(MAX_SUMMARY_ITEMS)
            val parts = shown.map { item ->
                val who = if (item.title.isNotBlank() && item.title != item.text) item.title else ""
                val text = item.text.take(MAX_TEXT_CHARS)
                if (who.isNotBlank()) "${item.appLabel} nunchi: $who — $text"
                else "${item.appLabel} nunchi: $text"
            }
            val extra = list.size - shown.size
            val base = parts.joinToString(". ")
            return if (extra > 0) "$base. Inka $extra notifications unnayi" else base
        }

        /**
         * Latest notification that can take a reply, filtered by app
         * (label or package contains [appHint]) and sender (title contains
         * [senderHint]). If a hint is given and nothing matches it, null
         * is returned — a reply never goes to the wrong app or person.
         * With no hints at all, the latest replyable notification wins.
         */
        fun latestReplyable(appHint: String?, senderHint: String?): HeardNotification? {
            val replyable = recentNotifications().filter { it.canReply }
            if (replyable.isEmpty()) return null
            val app = appHint?.trim()?.lowercase().orEmpty()
            val sender = senderHint?.trim()?.lowercase().orEmpty()
            val byApp = if (app.isEmpty()) {
                replyable
            } else {
                replyable.filter {
                    it.appLabel.lowercase().contains(app) ||
                        it.packageName.lowercase().contains(app)
                }
            }
            if (app.isNotEmpty() && byApp.isEmpty()) return null
            if (sender.isEmpty()) return byApp.firstOrNull()
            return byApp.firstOrNull { it.title.lowercase().contains(sender) }
        }

        /**
         * Sends [replyText] through the notification's own reply action.
         * Returns true only if the reply was actually handed to the app.
         */
        fun replyTo(key: String, replyText: String): Boolean {
            val item = synchronized(lock) { recent.firstOrNull { it.key == key } }
                ?: return false
            val action = item.replyAction ?: return false
            val remoteInput = action.remoteInputs?.firstOrNull() ?: return false
            return try {
                val intent = Intent()
                val results = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
                action.actionIntent.send(null, 0, intent)
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            } catch (_: Exception) {
                false
            }
        }

        internal fun onPosted(sbn: StatusBarNotification, context: Context) {
            val heard = toHeard(sbn, context) ?: return
            synchronized(lock) {
                recent.removeAll { it.key == heard.key }
                recent.addFirst(heard)
                while (recent.size > MAX_KEPT) recent.removeLast()
            }
        }

        internal fun onRemoved(key: String) {
            synchronized(lock) { recent.removeAll { it.key == key } }
        }

        internal fun onConnectedActive(
            notifications: Array<StatusBarNotification>,
            context: Context
        ) {
            // Seed from what is already showing; newest first.
            val heard = notifications
                .mapNotNull { toHeard(it, context) }
                .sortedByDescending { it.timeMillis }
            synchronized(lock) {
                recent.clear()
                recent.addAll(heard.take(MAX_KEPT))
            }
        }

        private fun toHeard(sbn: StatusBarNotification, context: Context): HeardNotification? {
            val pkg = sbn.packageName
            // Never our own notifications, never system ones.
            if (pkg == context.packageName) return null
            if (pkg == "android" || pkg == "com.android.systemui") return null
            val notification = sbn.notification
            // Ongoing (music player, download, ...) and group summaries are
            // not messages — skip them so the list stays about real messages.
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val body = (bigText.ifBlank { text }).trim()
            if (title.isBlank() && body.isBlank()) return null
            val replyAction = notification.actions?.firstOrNull {
                it.remoteInputs?.isNotEmpty() == true
            }
            val appLabel = try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info)?.toString() ?: pkg
            } catch (_: Exception) {
                pkg
            }
            return HeardNotification(
                key = sbn.key,
                packageName = pkg,
                appLabel = appLabel,
                title = title.trim(),
                text = body,
                timeMillis = sbn.postTime,
                replyAction = replyAction
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        onPosted(sbn, this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        onRemoved(sbn.key)
    }

    override fun onListenerConnected() {
        onConnectedActive(activeNotifications, this)
    }
}
