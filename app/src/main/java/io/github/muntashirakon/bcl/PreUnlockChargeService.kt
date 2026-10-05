// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.muntashirakon.bcl.Constants.CHARGE_LIMIT_ENABLED
import io.github.muntashirakon.bcl.Constants.CHARGE_OFF_KEY
import io.github.muntashirakon.bcl.Constants.CHARGE_ON_KEY
import io.github.muntashirakon.bcl.Constants.FILE_KEY
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MIN

/**
 * Minimal charge-limit enforcer that runs during direct boot, i.e. after a
 * reboot but before the user unlocks the device. The regular service cannot run
 * in that phase because its settings live in credential-encrypted storage.
 *
 * The enforcement values are read from the device-protected mirror kept up to
 * date by [Utils.syncDirectBootSettings]. As soon as the user is unlocked the
 * service stops itself and the regular [ForegroundService] takes over.
 */
class PreUnlockChargeService : Service() {

    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        NotificationManagerCompat.from(this)
    }
    private var batteryReceiver: BroadcastReceiver? = null
    private var userReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        val channel = NotificationChannelCompat.Builder(
            Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName("Charge Limit Status")
            .build()
        notificationManager.createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.please_wait), R.drawable.ic_notif_charge))

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                enforce(intent)
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        userReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                    // The regular service can run now; BootReceiver starts it
                    // when it receives ACTION_BOOT_COMPLETED.
                    stopSelf()
                }
            }
        }
        registerReceiver(userReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Utils.isUserUnlocked(this)) {
            // Started (or restarted) after unlock: the regular service handles it.
            stopSelf()
            return START_NOT_STICKY
        }
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { enforce(it) }
        return START_STICKY
    }

    private fun enforce(batteryIntent: Intent) {
        val mirror = Utils.getDirectBootPrefs(this)
        if (!mirror.getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            stopSelf()
            return
        }
        val level = Utils.getBatteryLevel(batteryIntent)
        val limit = mirror.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
        val min = mirror.getInt(MIN, limit - 2)
        val newState: String
        val title: String
        val icon: Int
        if (level >= limit) {
            newState = mirror.getString(CHARGE_OFF_KEY, Constants.DEFAULT_DISABLED)!!
            title = getString(R.string.maintaining_x_to_y, min, limit)
            icon = R.drawable.ic_notif_maintain
        } else if (level < min) {
            newState = mirror.getString(CHARGE_ON_KEY, Constants.DEFAULT_ENABLED)!!
            title = getString(R.string.waiting_until_x, limit)
            icon = R.drawable.ic_notif_charge
        } else {
            // Between the recharge threshold and the limit: keep the current state.
            return
        }
        val file = mirror.getString(FILE_KEY, Constants.DEFAULT_FILE)!!
        Utils.writeCtrlFile(file, newState)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, icon))
        }
    }

    private fun buildNotification(title: String, icon: Int): android.app.Notification {
        return NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setContentTitle(title)
            .setSmallIcon(icon)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        userReceiver?.let { unregisterReceiver(it) }
        userReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}
