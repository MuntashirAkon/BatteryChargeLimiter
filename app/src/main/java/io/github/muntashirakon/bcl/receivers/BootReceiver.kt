package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.PreUnlockChargeService
import io.github.muntashirakon.bcl.Utils

/**
 * Triggered when the phone finished booting.
 * Checks whether power supply is attached and starts the foreground service if necessary.
 *
 * Two stages are handled:
 *
 * - ACTION_LOCKED_BOOT_COMPLETED (direct boot): the regular settings live in
 *   credential-encrypted storage and are not readable until the user unlocks.
 *   The limit is applied from the device-protected mirror by
 *   [PreUnlockChargeService] so a rebooted, still-locked device does not charge
 *   past the limit.
 * - ACTION_BOOT_COMPLETED (after unlock): the normal foreground service starts
 *   and takes over.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                if (!Utils.isUserUnlocked(appContext)
                    && Utils.getDirectBootPrefs(appContext)
                        .getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
                ) {
                    ContextCompat.startForegroundService(
                        appContext, Intent(appContext, PreUnlockChargeService::class.java)
                    )
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Make sure the settings mirror is fresh and a future plug-in or
                // kill is recovered even if this receiver's direct start fails
                // (see Utils.scheduleChargeStartJob / Utils.ensureServiceWatchdog).
                Utils.syncDirectBootSettings(appContext)
                Utils.ensureServiceWatchdog(appContext)
                Utils.scheduleChargeStartJob(appContext)
                // Immediately after BOOT_COMPLETED the su daemon is usually not accepting
                // commands yet. Issuing them right away fails silently, which left the limit
                // unenforced whenever the device powered on with the charger already
                // plugged in (no ACTION_POWER_CONNECTED is broadcast in that case, so
                // PowerConnectionReceiver cannot recover it either). Wait for a root shell
                // first, then apply the limit.
                val pendingResult = goAsync()
                Shell.getShell { shell ->
                    try {
                        if (!shell.isRoot) {
                            return@getShell
                        }
                        Utils.setVoltageThreshold(null, true, appContext, null)
                        Utils.startServiceIfLimitEnabled(appContext)
                        Shell.cmd("cat ${Utils.getVoltageFile()}").submit {
                            if (it.out.isNotEmpty()) {
                                Utils.getSettings(appContext).edit()
                                    .putString(Constants.DEFAULT_VOLTAGE_LIMIT, it.out[0]).apply()
                            }
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            else -> return
        }
    }
}
