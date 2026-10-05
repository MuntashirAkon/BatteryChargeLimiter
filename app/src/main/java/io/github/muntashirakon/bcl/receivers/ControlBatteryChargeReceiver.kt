// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment

class ControlBatteryChargeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (Constants.INTENT_CHANGE_LIMIT_ACTION == intent.action) {
            Utils.handleLimitChange(context, intent.extras?.get(Intent.EXTRA_TEXT))
        } else if (intent.action == Constants.INTENT_DISABLE_ACTION) {
            // "Dismiss" / "Disable temporarily": remember it so the watchdog does
            // not resurrect the service while the device stays plugged in.
            Utils.getPrefs(context).edit()
                .putBoolean(PrefsFragment.KEY_SERVICE_DISMISSED, true).apply()
            Utils.stopService(context, false)
        } else if (intent.action == Constants.INTENT_TOGGLE_ACTION) {
            val settings = Utils.getSettings(context)
            if (Shell.getShell().isRoot) {
                val enable = !settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
                settings.edit().putBoolean(Constants.CHARGE_LIMIT_ENABLED, enable).apply()
                if (enable) {
                    Utils.startServiceIfLimitEnabled(context)
                } else {
                    Utils.stopService(context)
                }
            } else {
                Toast.makeText(context, R.string.root_denied, Toast.LENGTH_LONG).show()
            }
        }
    }
}
