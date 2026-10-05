// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.muntashirakon.bcl.settings.PrefsFragment

/**
 * JobScheduler entry point that (re)starts the charge limit.
 *
 * Two jobs are handled here:
 *
 * - [Constants.JOB_CHARGE_START_ID]: a persisted one-shot job constrained on
 *   `requiresCharging`. Manifest power broadcasts are not delivered to apps
 *   that are not running on several ROMs (observed on a Samsung Galaxy A7 Lite
 *   running an Android 13 GSI: "Background execution not allowed"), so the
 *   system runs this job instead when charging begins.
 * - [Constants.JOB_SERVICE_WATCHDOG_ID]: a persisted periodic job that recovers
 *   the service after the app process is killed in the background. It also ends
 *   a temporary "dismissed" state once the charger is disconnected.
 */
class ChargeStartJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // Credential-encrypted settings are not readable before unlock; the
        // pre-unlock service handles that phase (see PreUnlockChargeService).
        if (!Utils.isUserUnlocked(this)) {
            return false
        }
        val prefs = Utils.getPrefs(this)
        if (params?.jobId == Constants.JOB_SERVICE_WATCHDOG_ID) {
            if (!Utils.isPhonePluggedIn(this)) {
                // The charger is gone; a temporary dismissal ends with it.
                prefs.edit().putBoolean(PrefsFragment.KEY_SERVICE_DISMISSED, false).apply()
                return false
            }
            if (prefs.getBoolean(PrefsFragment.KEY_SERVICE_DISMISSED, false)) {
                // The user disabled the limit temporarily; leave it alone.
                return false
            }
        } else {
            // The plug-in job ran because charging has started: re-arm.
            prefs.edit().putBoolean(PrefsFragment.KEY_SERVICE_DISMISSED, false).apply()
        }
        if (!ForegroundService.isRunning
            && Utils.getSettings(this).getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
        ) {
            ContextCompat.startForegroundService(this, Intent(this, ForegroundService::class.java))
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}
