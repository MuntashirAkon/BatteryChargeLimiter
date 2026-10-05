package io.github.muntashirakon.bcl

/**
 * Created by Michael on 26.03.2017.
 *
 * This class holds constants for internal use that are not shown to the user
 */

object Constants {
    const val SETTINGS = "Settings"
    const val SETTINGS_VERSION = "SettingsVersion"

    const val FILE_KEY = "ctrl_file"
    const val CHARGE_ON_KEY = "charge_on"
    const val CHARGE_OFF_KEY = "charge_off"

    const val DEFAULT_FILE = "/sys/class/power_supply/battery/charging_enabled"
    const val DEFAULT_ENABLED = "1"
    const val DEFAULT_DISABLED = "0"

    const val LIMIT = "limit"
    const val MIN = "min"
    const val CHARGE_LIMIT_ENABLED = "enable"
    const val DISABLE_CHARGE_NOW = "disable_charge_now"
    const val NOTIFICATION_LIVE = "notificationLive"
    const val AUTO_RESET_STATS = "auto_reset_stats"
    const val NOTIFICATION_SOUND = "notificationSound"

    const val DISCHARGE_ACTIVE = "discharge_active"
    const val DISCHARGE_TARGET = "discharge_target"
    const val MIN_DISCHARGE_TARGET_PC: Int = 20

    // Heat protection: resume charging this many degrees below the maximum.
    const val HEAT_HYSTERESIS_C: Int = 2
    const val DEFAULT_HEAT_MAX_TEMP_C: Int = 40
    const val MIN_HEAT_MAX_TEMP_C: Int = 30
    const val MAX_HEAT_MAX_TEMP_C: Int = 50

    const val LIMIT_BY_VOLTAGE = "limit_by_voltage"
    const val DEFAULT_VOLTAGE_LIMIT = "default_voltage_limit"
    const val CUSTOM_VOLTAGE_LIMIT = "custom_voltage_limit"
//    const val CURRENT_VOLTAGE_LIMIT = "current_voltage_limit"

    // ms after reaching limit, where the "unplug" event is recognized as power cut instead of action unplugging
    const val POWER_CHANGE_TOLERANCE_MS: Long = 3000
    const val CHARGING_CHANGE_TOLERANCE_MS: Long = 500
    const val MAX_BACK_OFF_TIME: Long = 30000

    const val MAX_ALLOWED_LIMIT_PC: Int = 100
    const val DEFAULT_LIMIT_PC: Int = 80
    const val MIN_ALLOWED_LIMIT_PC: Int = 40

    //voltage thresholds in mV, inclusive
    const val DEFAULT_VOLTAGE_FILE = "/sys/class/power_supply/battery/voltage_max"
    const val MIN_VOLTAGE_THRESHOLD_MV = "3700"
    const val DEFAULT_VOLTAGE_THRESHOLD_MV = "4100"
    const val MAX_VOLTAGE_THRESHOLD_MV = "4400"

    const val NOTIF_MAINTAIN = "ic_maintain"
    const val NOTIF_CHARGE = "ic_charge"

    const val INTENT_TOGGLE_ACTION = BuildConfig.APPLICATION_ID + ".action.TOGGLE"
    const val INTENT_DISABLE_ACTION = BuildConfig.APPLICATION_ID + ".action.DISABLE"
    const val INTENT_CHANGE_LIMIT_ACTION = BuildConfig.APPLICATION_ID + ".action.CHANGE_LIMIT"
    const val FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID = BuildConfig.APPLICATION_ID + ".action.FOREGROUND_SERVICE"

    const val JOB_CHARGE_START_ID = 1
    const val JOB_SERVICE_WATCHDOG_ID = 2

    /** Device-protected storage: readable before the user unlocks the device. */
    const val DIRECT_BOOT_PREFS = "DirectBoot"

    const val SAVED_PATH_DATA = "saved_ctrl_path_data"
    const val SAVED_ENABLED_DATA = "saved_ctrl_enabled_data"
    const val SAVED_DISABLED_DATA = "saved_ctrl_disabled_data"

    const val LIGHT = "light"
    const val DARK = "dark"
    const val BLACK = "black"
}
