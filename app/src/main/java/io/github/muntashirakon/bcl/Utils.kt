package io.github.muntashirakon.bcl

import android.annotation.SuppressLint
import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.internal.ViewUtils.doOnApplyWindowInsets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants.CHARGE_LIMIT_ENABLED
import io.github.muntashirakon.bcl.Constants.CHARGE_OFF_KEY
import io.github.muntashirakon.bcl.Constants.CHARGE_ON_KEY
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.DEFAULT_DISABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_ENABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_FILE
import io.github.muntashirakon.bcl.Constants.FILE_KEY
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.activities.MainActivity
import io.github.muntashirakon.bcl.settings.PrefsFragment
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


object Utils {
    private val TAG = Utils::class.java.simpleName
    const val CHARGE_ON = 0
    const val CHARGE_OFF = 1

    // remember pending state change
    private var changePending: Long = 0

    // remember initialization
    private var cfInitialized: Boolean = false

    /**
     * Inform the BatteryReceiver instance(es) to ignore events for CHARGING_CHANGE_TOLERANCE_MS,
     * in order to let the state change settle.
     */
    private fun setChangePending() {
        // update changePending to prevent concurrent state changes before execution
        changePending = System.currentTimeMillis()
    }

    /**
     * Returns whether some change happened at most CHARGING_CHANGE_TOLERANCE_MS ago.
     *
     * @return Whether state change is pending
     */
    fun isChangePending(tolerance: Long): Boolean {
        return System.currentTimeMillis() <= changePending + tolerance
    }

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun changeState(context: Context, chargeMode: Int) {
        val preferences = getPrefs(context)
        val alwaysWrite = preferences.getBoolean(PrefsFragment.KEY_ALWAYS_WRITE_CF, false)

        val file = getCtrlFileData(context)
        val newState = if (chargeMode == CHARGE_ON) {
            getCtrlEnabledData(context)
        } else {
            getCtrlDisabledData(context)
        }

        if (alwaysWrite) {
            writeCtrlFile(file, newState)
        } else {
            Shell.cmd("cat $file").submit {
                if (it.out.size == 0 || it.out[0] != newState) {
                    setChangePending()
                    writeCtrlFile(file, newState)
                }
            }
        }
    }

    /**
     * Writes [newState] to the control [file] and verifies the result.
     *
     * The preparation commands must not be joined with `&&`: on many kernels
     * (observed on a Samsung MediaTek device) `mount -o rw,remount <file>`
     * fails because a sysfs path is not a mount point. With `&&` that failure
     * skipped the `echo` as well, silently dropping the first write of every
     * process - the app reported "maintaining" while the file kept its old
     * value and the device kept charging past the limit.
     *
     * Failures and ignored writes are logged instead of being swallowed.
     */
    internal fun writeCtrlFile(file: String, newState: String) {
        val write = "echo \"$newState\" > $file && cat $file"
        val command = if (cfInitialized) {
            write
        } else {
            "mount -o rw,remount $file 2>/dev/null; chmod u+w $file 2>/dev/null; $write"
        }
        Shell.cmd(command).submit { result ->
            val written = result.out.lastOrNull()?.trim()
            when {
                !result.isSuccess -> Log.e(
                    TAG, "Failed to write '$newState' to $file: " +
                        result.err.joinToString(separator = " ")
                )
                written != newState -> {
                    cfInitialized = true
                    Log.e(TAG, "Wrote '$newState' to $file but it reads back '$written'")
                }
                else -> cfInitialized = true
            }
        }
    }

    private var ctrlFiles: List<ControlFile>? = null
    fun getCtrlFiles(context: Context): List<ControlFile> {
        if (ctrlFiles == null) {
            try {
                val r = InputStreamReader(
                    context.resources.openRawResource(R.raw.control_files),
                    Charset.forName("UTF-8")
                )
                val type = object : TypeToken<List<ControlFile>>() {}.type
                ctrlFiles = Gson().fromJson<List<ControlFile>>(r, type)!!.sortedWith(compareBy(
                    { it.order }, { it.issues }, { it.experimental }, { it.file })
                )
            } catch (e: Exception) {
                Log.wtf(context.javaClass.simpleName, e)
                return emptyList()
            }
        }
        return ctrlFiles!!
    }

    @WorkerThread
    fun validateCtrlFiles(context: Context) {
        for (cf in getCtrlFiles(context)) {
            cf.validate()
        }
    }

    fun setCtrlFile(context: Context, cf: ControlFile) {
        //This will immediately reset the current control file
        stopService(context)
        getPrefs(context)
            .edit().putString(PrefsFragment.KEY_CONTROL_FILE, cf.file).apply()
        getSettings(context)
            .edit().putString(FILE_KEY, cf.file)
            .putString(CHARGE_ON_KEY, cf.chargeOn)
            .putString(CHARGE_OFF_KEY, cf.chargeOff).apply()
        //Respawn the service if necessary
        startServiceIfLimitEnabled(context)
    }

    fun isPhonePluggedIn(context: Context): Boolean {
        val batteryIntent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )!!
        return (batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                == BatteryManager.BATTERY_STATUS_CHARGING
                || batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0)
    }

    /**
     * Returns whether the system is allowed to skip battery optimizations (Doze,
     * background restrictions) for the app.
     *
     * Without the exemption the system may kill the foreground service in the
     * background, and the charge limit stops being applied until the app runs
     * again. The check answers `true` on devices without battery optimization.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog that asks the user to exempt the app from battery
     * optimizations. Some ROMs do not implement the direct request, so the
     * battery optimization settings screen is used as a fallback.
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Direct battery optimization request not available", e)
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "Battery optimization settings not available", e2)
            }
        }
    }

    fun getBatteryLevel(batteryIntent: Intent): Int {
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        return if (level == -1 || scale == -1) {
            50
        } else {
            level * 100 / scale
        }
    }

    fun getBatteryInfo(context: Context, intent: Intent, useFahrenheit: Boolean): String {
        val batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return context.getString(
            if (useFahrenheit) R.string.battery_info_F else R.string.battery_info_C,
            batteryVoltage.toFloat() / 1000f,
            if (useFahrenheit) 32f + batteryTemperature * 1.8f / 10f else batteryTemperature / 10f
        )
    }

    //    @SuppressLint("PrivateApi")
    fun resetBatteryStats(context: Context) {
//        try {
//            // new technique for PureNexus-powered devices
//            val helperClass = Class.forName("com.android.internal.os.BatteryStatsHelper")
//            val constructor = helperClass.getConstructor(Context::class.java,
//                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
//            val instance = constructor.newInstance(context, false, false)
//            val createMethod = helperClass.getMethod("create", Bundle::class.javaPrimitiveType)
//            createMethod.invoke(instance, null)
//            val resetMethod = helperClass.getMethod("resetStatistics")
//            resetMethod.invoke(instance)
//            Toast.makeText(context, R.string.stats_reset_success, Toast.LENGTH_SHORT).show()
//        } catch (e: Exception) {
//            Log.i("New reset method failed", e.message, e)
        // On Exception, fall back to conventional method
        Shell.cmd("dumpsys batterystats --reset").submit {
            if (it.isSuccess) {
                Toast.makeText(context, R.string.stats_reset_success, Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "Statistics reset failed")
            }
        }
//        }
    }

    fun setLimit(limit: Int, settings: SharedPreferences) {
        val max = settings.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
        // calculate new recharge threshold from previous distance
        val min = (limit - (max - settings.getInt(MIN, max - 2))).coerceAtLeast(0)
        settings.edit().putInt(LIMIT, limit).putInt(MIN, min).apply()
    }

    fun handleLimitChange(context: Context, newLimit: Any?) {
        try {
            if (newLimit == null) {
                throw NumberFormatException("null")
            }
            val limit = if (newLimit is Number) {
                newLimit.toInt()
            } else {
                Integer.parseInt(newLimit.toString())
            }
            if (limit == Constants.MAX_ALLOWED_LIMIT_PC) {
                val settings = getSettings(context)
                stopService(context)
                settings.edit().putBoolean(CHARGE_LIMIT_ENABLED, false).apply()
            } else if (limit in Constants.MIN_ALLOWED_LIMIT_PC until Constants.MAX_ALLOWED_LIMIT_PC) {
                val settings = getSettings(context)
                // set the new limit
                setLimit(limit, settings)
                Toast.makeText(
                    context, context.getString(R.string.intent_limit_accepted, limit),
                    Toast.LENGTH_SHORT
                ).show()
                if (!settings.getBoolean(NOTIFICATION_LIVE, false)) {
                    settings.edit().putBoolean(CHARGE_LIMIT_ENABLED, true).apply()
                    startServiceIfLimitEnabled(context)
                }
            } else {
                throw NumberFormatException("Battery limit out of range!")
            }
        } catch (fe: NumberFormatException) {
            Toast.makeText(context, R.string.intent_limit_invalid, Toast.LENGTH_SHORT).show()
        }

    }

    fun startServiceIfLimitEnabled(context: Context) {
        syncDirectBootSettings(context)
        if (!getSettings(context).getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            return
        }
        // An explicit start request re-arms the limit after a temporary dismissal.
        getPrefs(context).edit().putBoolean(PrefsFragment.KEY_SERVICE_DISMISSED, false).apply()
        ensureServiceWatchdog(context)
        if (getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            changeState(context, CHARGE_ON)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            ContextCompat.startForegroundService(context, Intent(context, ForegroundService::class.java))
            // display service enabled Toast message if not disabled in settings
            if (!getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
                Toast.makeText(context, R.string.service_enabled, Toast.LENGTH_SHORT).show()
            }
        }, CHARGING_CHANGE_TOLERANCE_MS)
    }

    fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun getSettings(context: Context): SharedPreferences {
        return context.getSharedPreferences(SETTINGS, 0)
    }

    fun stopService(context: Context, ignoreAutoReset: Boolean = true) {
        // Keep the device-protected mirror in sync while the settings are readable.
        syncDirectBootSettings(context)
        if (!getSettings(context).getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            cancelServiceWatchdog(context)
        }
        val wasServiceRunning = ForegroundService.isRunning
        if (ignoreAutoReset) {
            ForegroundService.ignoreAutoReset()
        }
        context.stopService(Intent(context, ForegroundService::class.java))
        if (!getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            changeState(context, CHARGE_ON)
        }
        // display service disabled Toast message if not disabled in settings
        if (wasServiceRunning && !getPrefs(context).getBoolean("hide_toast_on_service_changes", false)) {
            Toast.makeText(context, R.string.service_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Persisted periodic watchdog that recovers the service after the process is
     * killed in the background. The one-shot plug-in job cannot cover this: it is
     * consumed when charging starts, so a kill afterwards would leave the limit
     * unenforced until the next plug-in. The watchdog runs about every 15 minutes,
     * restarts the service when it should be running and clears the temporary
     * "dismissed" state once the charger is gone.
     */
    fun ensureServiceWatchdog(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            && jobScheduler.getPendingJob(Constants.JOB_SERVICE_WATCHDOG_ID) != null
        ) {
            return
        }
        val jobInfo = JobInfo.Builder(
            Constants.JOB_SERVICE_WATCHDOG_ID,
            ComponentName(context, ChargeStartJobService::class.java)
        )
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        jobScheduler.schedule(jobInfo)
    }

    private fun cancelServiceWatchdog(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(Constants.JOB_SERVICE_WATCHDOG_ID)
    }

    /**
     * Returns the device-protected preferences used during direct boot, i.e.
     * before the user unlocks the device and the regular settings are readable.
     */
    fun getDirectBootPrefs(context: Context): SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(Constants.DIRECT_BOOT_PREFS, Context.MODE_PRIVATE)
    }

    fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true
        }
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return userManager.isUserUnlocked
    }

    /**
     * Mirrors the enforcement settings into device-protected storage so the
     * pre-unlock service can apply the limit after a reboot, before the user
     * unlocks the device and the credential-encrypted settings are readable.
     */
    fun syncDirectBootSettings(context: Context) {
        if (!isUserUnlocked(context)) {
            return
        }
        val settings = getSettings(context)
        val limit = settings.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
        getDirectBootPrefs(context).edit()
            .putBoolean(CHARGE_LIMIT_ENABLED, settings.getBoolean(CHARGE_LIMIT_ENABLED, false))
            .putString(FILE_KEY, getCtrlFileData(context))
            .putString(CHARGE_ON_KEY, getCtrlEnabledData(context))
            .putString(CHARGE_OFF_KEY, getCtrlDisabledData(context))
            .putInt(LIMIT, limit)
            .putInt(MIN, settings.getInt(MIN, limit - 2))
            .apply()
    }

    /**
     * Schedules the persisted job that starts the service when charging begins.
     *
     * Manifest power broadcasts can be dropped for apps that are not running
     * (observed on Android 13 GSIs), so a normal unplug/replug silently left
     * the limit off. The system runs this job when the charging constraint is
     * met, whether or not the app is alive. Called whenever the service stops
     * and at boot; the job has a fixed id, so scheduling again just replaces it.
     */
    fun scheduleChargeStartJob(context: Context) {
        if (!getSettings(context).getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            return
        }
        if (getPrefs(context).getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
            return
        }
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobInfo = JobInfo.Builder(
            Constants.JOB_CHARGE_START_ID,
            ComponentName(context, ChargeStartJobService::class.java)
        )
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        jobScheduler.schedule(jobInfo)
    }

    fun getCtrlFileData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_PATH_DATA, DEFAULT_FILE)!!
        } else {
            // Custom Data Disabled
            settings.getString(FILE_KEY, DEFAULT_FILE)!!
        }
    }

    fun getCtrlEnabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_ENABLED_DATA, DEFAULT_ENABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(CHARGE_ON_KEY, DEFAULT_ENABLED)!!
        }
    }

    fun getCtrlDisabledData(context: Context): String {
        val settings = getSettings(context)
        val preferences = getPrefs(context)

        return if (preferences.getBoolean("custom_ctrl_file_data", false)) {
            // Custom Data Enabled
            settings.getString(Constants.SAVED_DISABLED_DATA, DEFAULT_DISABLED)!!
        } else {
            // Custom Data Disabled
            settings.getString(CHARGE_OFF_KEY, DEFAULT_DISABLED)!!
        }
    }

    fun setTheme(activity: Activity, splashScreen: Boolean = false) {
        val preferences = getPrefs(activity)
        val getTheme = preferences.getString(PrefsFragment.KEY_THEME, Constants.LIGHT)
        var theme = if (splashScreen) R.style.AppTheme_Splash else R.style.AppTheme
        var nightMode = AppCompatDelegate.MODE_NIGHT_NO
        when (getTheme) {
            Constants.LIGHT -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_NO
            }
            Constants.DARK -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_YES
            }
            Constants.BLACK -> {
                nightMode = AppCompatDelegate.MODE_NIGHT_YES
                theme = if (splashScreen) R.style.AppTheme_Splash_Black else R.style.AppTheme_Black
            }
        }
        activity.setTheme(theme)
        AppCompatDelegate.setDefaultNightMode(nightMode)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    /**
     * FUTURE provide customisability if required
     */
    fun getVoltageFile(): String {
        return Constants.DEFAULT_VOLTAGE_FILE
    }

    private var vfInitialized = false

    fun setVoltageThreshold(voltage: String?, onlyIfEnabled: Boolean, context: Context, handler: Handler?) {
        if (onlyIfEnabled && !getSettings(context).getBoolean(Constants.LIMIT_BY_VOLTAGE, false)) {
            return
        }

        val voltageThreshold = voltage ?: getSettings(context).getString(Constants.CUSTOM_VOLTAGE_LIMIT, null)

        if (voltageThreshold == null) {
            Log.e(TAG, "Custom Voltage Threshold not valid")
            return
        }

        val switchCommands: Array<String>
        val voltageFile = getVoltageFile()
        if (vfInitialized) {
            switchCommands = arrayOf("echo $voltageThreshold > $voltageFile")
        } else {
            vfInitialized = true
            switchCommands = arrayOf(
                "mount -o rw,remount $voltageFile", "chmod u+w $voltageFile",
                "echo $voltageThreshold > $voltageFile"
            )
        }

        Shell.cmd(switchCommands.joinToString(separator = " && ")).submit()
        getCurrentVoltageThresholdAsync(context, handler)
    }

    fun getCurrentVoltageThresholdAsync(context: Context, handler: Handler?) {
        val voltageFile = getVoltageFile()
        Shell.cmd("cat $voltageFile").submit {
            if (it.out.size == 0) return@submit
            val voltage = it.out[0]
            val sharedPrefs = getSettings(context)
            if (sharedPrefs.getString(Constants.DEFAULT_VOLTAGE_LIMIT, null) == null) {
                sharedPrefs.edit().putString(Constants.DEFAULT_VOLTAGE_LIMIT, voltage).apply()
            }
            if (handler == null) return@submit
            val msg = handler.obtainMessage(MainActivity.MSG_UPDATE_VOLTAGE_THRESHOLD)
            val bundle = Bundle()
            bundle.putString(MainActivity.VOLTAGE_THRESHOLD, voltage)
            msg.data = bundle
            handler.sendMessage(msg)
        }
    }

    /**
     * We assume the voltage is atleast 4 digits, and the first 4 digits make milli-Volts
     */
    fun isValidVoltageThreshold(newThreshold: String, currentThreshold: String): Boolean {
        if (newThreshold.length == currentThreshold.length && newThreshold.length > 3) {
            Log.i("copy: ", "outer")
            val voltage = newThreshold.substring(0, 4).toInt()
            val minVolThres = Constants.MIN_VOLTAGE_THRESHOLD_MV.toInt()
            val manVolThres = Constants.MAX_VOLTAGE_THRESHOLD_MV.toInt()
            if (voltage in minVolThres..manVolThres) {
                return true
            }
        }
        Log.i(TAG, "$newThreshold not valid. Current threshold: $currentThreshold")
        return false
    }

    // Copied from App Manager
    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    fun applyWindowInsetsAsPaddingNoTop(v: View) {
        doOnApplyWindowInsets(v) { view, insets, initialPadding ->
            if (!ViewCompat.getFitsSystemWindows(view)) {
                // Do not add padding if fitsSystemWindows is false
                return@doOnApplyWindowInsets insets
            }
            val top: Int = initialPadding.top
            val bottom: Int = initialPadding.bottom + insets.systemWindowInsetBottom
            val isRtl = ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL
            val systemWindowInsetLeft: Int = insets.systemWindowInsetLeft
            val systemWindowInsetRight: Int = insets.systemWindowInsetRight
            var start: Int = initialPadding.start
            var end: Int = initialPadding.end
            if (isRtl) {
                start += systemWindowInsetRight
                end += systemWindowInsetLeft
            } else {
                start += systemWindowInsetLeft
                end += systemWindowInsetRight
            }
            ViewCompat.setPaddingRelative(view, start, top, end, bottom)
            insets
        }
    }
}
