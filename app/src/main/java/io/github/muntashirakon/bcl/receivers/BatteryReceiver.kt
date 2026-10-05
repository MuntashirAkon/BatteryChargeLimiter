package io.github.muntashirakon.bcl.receivers

import android.content.*
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MAX_BACK_OFF_TIME
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment


/**
 * Created by Michael on 01.04.2017.
 *
 * Dynamically created receiver for battery events. Only registered if power supply is attached.
 */
class BatteryReceiver(private val service: ForegroundService) : BroadcastReceiver() {

    private var chargedToLimit = false
    private var useFahrenheit = false
    private var lastState = -1
    private var limitPercentage: Int = 0
    private var rechargePercentage: Int = 0
    private val prefs = Utils.getPrefs(service.baseContext)
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val settings = service.getSharedPreferences(SETTINGS, 0)
    private var useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)
    private var heatProtectionEnabled = prefs.getBoolean(PrefsFragment.KEY_HEAT_PROTECTION, false)
    private var heatProtectionMaxTemp =
        prefs.getInt(PrefsFragment.KEY_HEAT_PROTECTION_MAX_TEMP, Constants.DEFAULT_HEAT_MAX_TEMP_C)
    private var heatPaused = false
    private var dischargeActive = false
    private var dischargeTarget = Constants.MIN_DISCHARGE_TARGET_PC

    init {
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                PrefsFragment.KEY_TEMP_FAHRENHEIT -> {
                    useFahrenheit = sharedPreferences.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
                    service.setNotificationContentText(
                        Utils.getBatteryInfo(
                            service,
                            service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!,
                            useFahrenheit
                        )
                    )
                    service.updateNotification()
                }
                LIMIT, MIN, Constants.DISCHARGE_ACTIVE, Constants.DISCHARGE_TARGET -> {
                    reset(sharedPreferences)
                }
                PrefsFragment.KEY_NOTIFICATION_SOUND -> {
                    this.useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)
                }
                PrefsFragment.KEY_HEAT_PROTECTION -> {
                    heatProtectionEnabled = sharedPreferences
                        .getBoolean(PrefsFragment.KEY_HEAT_PROTECTION, false)
                    reEvaluate()
                }
                PrefsFragment.KEY_HEAT_PROTECTION_MAX_TEMP -> {
                    heatProtectionMaxTemp = sharedPreferences
                        .getInt(PrefsFragment.KEY_HEAT_PROTECTION_MAX_TEMP, Constants.DEFAULT_HEAT_MAX_TEMP_C)
                    reEvaluate()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        settings.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        this.useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
        reset(settings)
    }

    private fun reset(settings: SharedPreferences) {
        chargedToLimit = false
        lastState = -1
        backOffTime = CHARGING_CHANGE_TOLERANCE_MS
        limitPercentage = settings.getInt(LIMIT, 80)
        rechargePercentage = settings.getInt(MIN, limitPercentage - 2)
        dischargeActive = settings.getBoolean(Constants.DISCHARGE_ACTIVE, false)
        dischargeTarget = settings.getInt(Constants.DISCHARGE_TARGET, Constants.MIN_DISCHARGE_TARGET_PC)
        // manually fire onReceive() to update state if service is enabled
        reEvaluate()
    }

    /** Re-applies the state machine to the current battery state. */
    private fun reEvaluate() {
        onReceive(service, service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!)
    }

    /**
     * Remembers the new state and returns whether the state was changed
     *
     * @param newState the new state
     * @return whether the state has changed
     */
    private fun switchState(newState: Int): Boolean {
        val oldState = lastState
        lastState = newState
        return oldState != newState
    }

    /**
     * If battery should be charging, but there's no power supply, stop the service.
     * NOT to be called if charging is expected to be disabled!
     */
    private fun stopIfUnplugged() {
        // save the state that caused this function call
        val triggerState = lastState
        handler.postDelayed({
            // continue only if the state didn't change in the meantime
            if (triggerState == lastState && !Utils.isPhonePluggedIn(service)) {
                Utils.stopService(service, false)
                Utils.scheduleChargeStartJob(service)
            }
        }, POWER_CHANGE_TOLERANCE_MS)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ignore events while trying to fix charging state, see below
        if (Utils.isChangePending(backOffTime * 2)) {
            return
        }

        val batteryLevel = Utils.getBatteryLevel(intent)
        val currentStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val showTempInNotif = preferences.getBoolean("temp_in_notif", false)

        if (!showTempInNotif) {
            service.setNotificationContentText(service.getString(R.string.waiting_description))
        } else {
            service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
        }

        // A discharge session keeps the charger input off until the target level is
        // reached, then hands over to the normal limit logic.
        if (dischargeActive) {
            if (batteryLevel > dischargeTarget) {
                if (switchState(CHARGE_DISCHARGE)) {
                    Log.d("Charging State", "CHARGE_DISCHARGE " + this.hashCode())
                    Utils.changeState(service, Utils.CHARGE_OFF)
                    service.setNotificationTitle(service.getString(R.string.discharging_to_x, dischargeTarget))
                    service.setNotificationIcon(NOTIF_MAINTAIN)
                    service.setNotificationActionText(service.getString(R.string.dismiss))
                }
                service.updateNotification()
                service.removeNotificationSound()
                return
            }
            // Target reached: end the session and continue with the normal logic.
            dischargeActive = false
            chargedToLimit = false
            lastState = -1
            settings.edit().putBoolean(Constants.DISCHARGE_ACTIVE, false).apply()
        }

        // Heat protection pauses charging while the battery is hotter than the
        // maximum, with a hysteresis so it does not toggle at the threshold.
        if (heatProtectionEnabled && batteryTemperature > 0) {
            val temperatureC = batteryTemperature / 10
            when {
                temperatureC >= heatProtectionMaxTemp -> heatPaused = true
                temperatureC <= heatProtectionMaxTemp - Constants.HEAT_HYSTERESIS_C -> heatPaused = false
                // In between: keep the previous decision.
            }
        } else {
            heatPaused = false
        }
        if (heatPaused) {
            if (switchState(CHARGE_HEAT)) {
                Log.d("Charging State", "CHARGE_HEAT " + this.hashCode())
                Utils.changeState(service, Utils.CHARGE_OFF)
                service.setNotificationTitle(service.getString(R.string.cooling_down_at_x, batteryTemperature / 10))
                service.setNotificationIcon(NOTIF_MAINTAIN)
                service.setNotificationActionText(service.getString(R.string.dismiss))
            }
            service.updateNotification()
            service.removeNotificationSound()
            return
        }

        // when the service was "freshly started", charge until limit
        if (!chargedToLimit && batteryLevel < limitPercentage) {
            if (switchState(CHARGE_FULL)) {
                Log.d("Charging State", "CHARGE_FULL " + this.hashCode())
                Utils.changeState(service, Utils.CHARGE_ON)
                service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                stopIfUnplugged()
            }
        } else if (batteryLevel >= limitPercentage) {
            if (switchState(CHARGE_STOP)) {
                Log.d("Charging State", "CHARGE_STOP " + this.hashCode())
                // play sound only the first time when the limit was reached
                if (useNotificationSound && !chargedToLimit) {
                    service.setNotificationSound()
                }
                // remember that we let the device charge until limit at least once
                chargedToLimit = true
                // active auto reset on service shutdown
                service.enableAutoReset()
                Utils.changeState(service, Utils.CHARGE_OFF)

                if (preferences.getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
                    Utils.stopService(service, false)
                }

                // set the "maintain" notification, this must not change from now
                service.setNotificationTitle(
                    service.getString(R.string.maintaining_x_to_y, rechargePercentage, limitPercentage)
                )
                service.setNotificationIcon(NOTIF_MAINTAIN)
                service.setNotificationActionText(service.getString(R.string.dismiss))
            } else if (currentStatus == BatteryManager.BATTERY_STATUS_CHARGING
                && prefs.getBoolean(PrefsFragment.KEY_ENFORCE_CHARGE_LIMIT, true)
            ) {
                //Double the back off time with every unsuccessful round up to MAX_BACK_OFF_TIME
                backOffTime = (backOffTime * 2).coerceAtMost(MAX_BACK_OFF_TIME)
                Log.d(
                    "Charging State",
                    "Fixing state w. CHARGE_ON/CHARGE_OFF " + this.hashCode() + " (Delay: $backOffTime)"
                )
                // if the device did not stop charging, try to "cycle" the state to fix this
                Utils.changeState(service, Utils.CHARGE_ON)
                // schedule the charging stop command to be executed after CHARGING_CHANGE_TOLERANCE_MS
                val service = this.service
                handler.postDelayed({ Utils.changeState(service, Utils.CHARGE_OFF) }, backOffTime)
            } else {
                backOffTime = CHARGING_CHANGE_TOLERANCE_MS
                // Re-assert the charge-off state on every battery update. If the
                // write that reached the limit was lost, this repairs it instead
                // of staying in "maintaining" while the device keeps charging.
                // Utils.changeState() only writes when the file differs.
                Utils.changeState(service, Utils.CHARGE_OFF)
            }
        } else if (batteryLevel < rechargePercentage) {
            if (switchState(CHARGE_REFRESH)) {
                Log.d("Charging State", "CHARGE_REFRESH " + this.hashCode())
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                Utils.changeState(service, Utils.CHARGE_ON)
                stopIfUnplugged()
            }
        }

        // update battery status information and rebuild notification
        // service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
        service.updateNotification()
        service.removeNotificationSound()
    }

    fun detach(context: Context) {
        // unregister the listener that listens for relevant change events
        prefs.unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        Utils.getSettings(context)
            .unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        this.preferenceChangeListener = null
    }

    companion object {
        private const val CHARGE_FULL = 0
        private const val CHARGE_STOP = 1
        private const val CHARGE_REFRESH = 2
        private const val CHARGE_DISCHARGE = 3
        private const val CHARGE_HEAT = 4

        private val handler = Handler(Looper.getMainLooper())
        internal var backOffTime = CHARGING_CHANGE_TOLERANCE_MS
    }

}
