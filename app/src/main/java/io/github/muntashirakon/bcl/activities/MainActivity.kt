package io.github.muntashirakon.bcl.activities

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.BuildConfig
import io.github.muntashirakon.bcl.Constants.SETTINGS_VERSION
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.CtrlFileHelper
import io.github.muntashirakon.bcl.settings.PrefsFragment
import io.github.muntashirakon.bcl.settings.SettingsActivity
import java.util.*


class MainActivity : AppCompatActivity() {
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private lateinit var prefs: SharedPreferences
    private var batteryOptimizationDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Utils.getPrefs(this)
        Utils.setTheme(this, true)
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        setTitle(R.string.app_name)

        // Exit immediately if no root support
        if (!Shell.getShell().isRoot) {
            showNoRootDialog()
            return
        }
        updateSettingsVersion()
        checkForControlFiles()
        whitelistIfFirstStart()
        // Load main fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, MainFragment())
            .commit()
    }

    override fun onResume() {
        super.onResume()
        askToDisableBatteryOptimizations()
    }

    /**
     * Battery optimization lets the system kill the foreground service in the
     * background, which stops the charge limit from being enforced. Ask the user
     * to exempt the app whenever it opens, until the app is exempt or the user
     * chooses not to be asked again.
     */
    private fun askToDisableBatteryOptimizations() {
        if (isFinishing || !Shell.getShell().isRoot) return
        if (batteryOptimizationDialog?.isShowing == true) return
        if (prefs.getBoolean(PrefsFragment.KEY_BATTERY_OPTIMIZATION_DONT_ASK, false)) return
        if (Utils.isIgnoringBatteryOptimizations(this)) return
        batteryOptimizationDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_disable) { _, _ ->
                Utils.requestBatteryOptimizationExemption(this)
            }
            .setNeutralButton(R.string.battery_optimization_dont_ask) { _, _ ->
                prefs.edit().putBoolean(PrefsFragment.KEY_BATTERY_OPTIMIZATION_DONT_ASK, true).apply()
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun showNoRootDialog() {
        MaterialAlertDialogBuilder(this@MainActivity)
            .setMessage(R.string.root_denied)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> finish() }.show()
    }

    private fun checkForControlFiles() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        if (!prefs.contains(PrefsFragment.KEY_CONTROL_FILE)) {
            CtrlFileHelper.validateFiles(this) {
                var found = false
                for (cf in Utils.getCtrlFiles(this@MainActivity)) {
                    if (cf.isValid) {
                        Utils.setCtrlFile(this@MainActivity, cf)
                        found = true
                        break
                    }
                }
                if (!found) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage(R.string.device_not_supported)
                        .setCancelable(false)
                        .setPositiveButton(R.string.ok) { _, _ -> finish() }.show()
                }
            }
        }
    }

    private fun updateSettingsVersion() {
        val settingsVersion = prefs.getInt(SETTINGS_VERSION, 0)
        var versionCode = 0L
        try {
            versionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.wtf(TAG, e)
        }

        if (settingsVersion < versionCode) {
            // update the settings version
            prefs.edit().putInt(SETTINGS_VERSION, versionCode.toInt()).apply()
        }
    }

    private fun whitelistIfFirstStart() {
        if (!prefs.getBoolean(getString(R.string.previously_started), false)) {
            // whitelist App for Doze Mode
            Shell.cmd("dumpsys deviceidle whitelist +${BuildConfig.APPLICATION_ID}").submit {
                if (it.isSuccess) {
                    prefs.edit().putBoolean(getString(R.string.previously_started), true).apply()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.about -> displayAboutDialog()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    override fun onDestroy() {
        Utils.getPrefs(baseContext)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        preferenceChangeListener = null
        super.onDestroy()
    }

    private fun displayAboutDialog() {
        val view = View.inflate(this, R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.app_author).movementMethod = LinkMovementMethod.getInstance()
        view.findViewById<TextView>(R.id.credits).movementMethod = LinkMovementMethod.getInstance()
        val versionTV = view.findViewById<TextView>(R.id.app_version)
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val version = packageInfo.versionName
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            versionTV.text = String.format(Locale.ROOT, "%s (%d)", version, versionCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setIcon(R.mipmap.ic_launcher_round)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val MSG_UPDATE_VOLTAGE_THRESHOLD = 1
        const val VOLTAGE_THRESHOLD = "voltageThreshold"
    }
}
