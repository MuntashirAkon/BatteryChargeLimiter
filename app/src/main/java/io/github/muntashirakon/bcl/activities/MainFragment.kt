package io.github.muntashirakon.bcl.activities

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.muntashirakon.bcl.*
import io.github.muntashirakon.bcl.settings.PrefsFragment
import java.lang.ref.WeakReference
import java.util.Locale

class MainFragment: Fragment() {
    private val settings by lazy(LazyThreadSafetyMode.NONE) { activity?.getSharedPreferences(Constants.SETTINGS, 0) }
    private val statusText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.status) }
    private val batteryLevelText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_level) }
    private val batteryInfo by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_info) }
    private val batteryHealth by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.battery_health) }
    private val monitorPower by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_power) }
    private val monitorCapacity by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_capacity) }
    private val monitorCycles by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_cycles) }
    private val monitorTemperature by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_temperature) }
    private val monitorTimeToFull by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_time_to_full) }
    private val monitorCharger by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.monitor_charger) }
    private val powerChart by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<PowerChartView>(R.id.power_chart) }
    private val limitSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.limit_slider) }
    private val limitValue by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.limit_value) }
    private val rechargeSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.recharge_slider) }
    private val rechargeValue by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.recharge_value) }
    private val dischargeSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.discharge_switch) }
    private val dischargeSlider by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<Slider>(R.id.discharge_slider) }
    private val dischargeValue by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.discharge_value) }
    private val dischargeText by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.discharge_text) }
    private val enableSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.enable_switch) }
    private val disableChargeSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.disable_charge_switch) }
    private val limitByVoltageSwitch by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<SwitchMaterial>(R.id.limit_by_voltage) }
    private val customThresholdEditView by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<EditText>(R.id.voltage_threshold) }
    private val currentThresholdTextView by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.current_voltage_threshold) }
    private val defaultThresholdTextView by lazy(LazyThreadSafetyMode.NONE) { view?.findViewById<TextView>(R.id.default_voltage_threshold) }
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private lateinit var currentThreshold: String
    private val mHandler = MainHandler(this)
    private var prefs: SharedPreferences? = null
    private var settingsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Guards the UI controls while [updateUi] changes them programmatically. */
    private var suppressListeners = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Utils.startServiceIfLimitEnabled(requireContext())
        } else requireActivity().finishAndRemoveTask()
    }

    /** Polls the kernel values while the screen is visible. */
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val monitorTick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            PowerMonitor.readAsync(requireContext()) { reading -> applyReading(reading) }
            monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    private class MainHandler(fragment: MainFragment) : Handler(Looper.getMainLooper()) {
        private val mFragment by lazy(LazyThreadSafetyMode.NONE) { WeakReference(fragment) }
        override fun handleMessage(msg: Message) {
            val fragment = mFragment.get()
            if (fragment != null) {
                when (msg.what) {
                    MainActivity.MSG_UPDATE_VOLTAGE_THRESHOLD -> {
                        val voltage = msg.data.getString(MainActivity.VOLTAGE_THRESHOLD)
                        fragment.currentThreshold = voltage!!
                        fragment.currentThresholdTextView?.text = voltage
                        if (fragment.settings?.getString(Constants.DEFAULT_VOLTAGE_LIMIT, null) == null) {
                            fragment.settings?.edit()?.putString(Constants.DEFAULT_VOLTAGE_LIMIT, voltage)?.apply()
                            fragment.defaultThresholdTextView?.text = voltage
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.applyWindowInsetsAsPaddingNoTop(view)
        prefs = Utils.getPrefs(requireContext())
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                PrefsFragment.KEY_TEMP_FAHRENHEIT -> {
                    val intent = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    intent?.let {
                        updateBatteryInfo(it)
                        updateTemperature(it)
                    }
                }
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        customThresholdEditView?.setOnEditorActionListener { _, actionId, _ ->
            var handled = false
            if (actionId == EditorInfo.IME_ACTION_GO) {
                hideKeybord()
                customThresholdEditView!!.clearFocus()
                handled = true
            }
            handled
        }

        Utils.getCurrentVoltageThresholdAsync(requireContext(), mHandler)

        currentThreshold = settings?.getString(Constants.DEFAULT_VOLTAGE_LIMIT, "4300")!!

        customThresholdEditView?.setText(settings?.getString(Constants.CUSTOM_VOLTAGE_LIMIT, ""))
        defaultThresholdTextView?.text = settings?.getString(Constants.DEFAULT_VOLTAGE_LIMIT, "")

        customThresholdEditView?.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val newThreshold = customThresholdEditView?.text.toString()
                if (Utils.isValidVoltageThreshold(newThreshold, currentThreshold)) {
                    settings?.edit()?.putString(Constants.CUSTOM_VOLTAGE_LIMIT, newThreshold)?.apply()
                    Utils.setVoltageThreshold(null, true, v.context, mHandler)
                }
            }
        }

        val resetBatteryStatsButton = view.findViewById<Button>(R.id.reset_battery_stats)

        enableSwitch?.setOnCheckedChangeListener(switchListener)
        disableChargeSwitch?.setOnCheckedChangeListener(switchListener)
        limitByVoltageSwitch?.setOnCheckedChangeListener(switchListener)

        limitSlider?.valueFrom = Constants.MIN_ALLOWED_LIMIT_PC.toFloat()
        limitSlider?.valueTo = Constants.MAX_ALLOWED_LIMIT_PC.toFloat()
        limitSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val max = value.toInt()
            Utils.setLimit(max, settings!!)
            Utils.syncDirectBootSettings(requireContext())
            limitValue?.text = getString(R.string.percent, max)
            updateSliderRanges(max)
            if (!ForegroundService.isRunning) {
                Utils.startServiceIfLimitEnabled(requireContext())
            }
        }

        rechargeSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val min = value.toInt()
            settings?.edit()?.putInt(Constants.MIN, min)?.apply()
            Utils.syncDirectBootSettings(requireContext())
            rechargeValue?.text = getString(R.string.percent, min)
        }

        dischargeSlider?.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val target = value.toInt()
            settings?.edit()?.putInt(Constants.DISCHARGE_TARGET, target)?.apply()
            dischargeValue?.text = getString(R.string.percent, target)
            updateDischargeText(target)
        }

        dischargeSwitch?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            // The stored target can be stale (e.g. above a lowered limit) while the
            // slider shows the clamped value, so store what the user actually sees.
            dischargeSlider?.let { slider ->
                settings?.edit()?.putInt(Constants.DISCHARGE_TARGET, slider.value.toInt())?.apply()
            }
            settings?.edit()?.putBoolean(Constants.DISCHARGE_ACTIVE, isChecked)?.apply()
            dischargeSlider?.isEnabled = !isChecked
        }

        settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // The discharge session ends by itself when the target is reached.
            if (key == Constants.DISCHARGE_ACTIVE) updateUi()
        }
        settings?.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        resetBatteryStatsButton.setOnClickListener { Utils.resetBatteryStats(requireContext()) }

        setStatusCTRLFileData()

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        setStatusCTRLFileData()
    }

    override fun onStart() {
        super.onStart()
        context?.registerReceiver(charging, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // the limits could have been changed by an Intent, so update the UI here
        updateUi()
        monitorHandler.removeCallbacks(monitorTick)
        monitorTick.run()
    }

    override fun onStop() {
        context?.unregisterReceiver(charging)
        monitorHandler.removeCallbacks(monitorTick)
        super.onStop()
    }

    override fun onDestroy() {
        prefs?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        settings?.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        super.onDestroy()
    }

    //OnCheckedChangeListener for Switch elements
    private val switchListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
        when (buttonView.id) {
            R.id.enable_switch -> {
                settings?.edit()?.putBoolean(Constants.CHARGE_LIMIT_ENABLED, isChecked)?.apply()
                if (isChecked) {
                    Utils.startServiceIfLimitEnabled(requireContext())
                    disableSwitches(listOf(disableChargeSwitch, limitByVoltageSwitch))
                } else {
                    Utils.stopService(requireContext())
                    enableSwitches(listOf(disableChargeSwitch, limitByVoltageSwitch))
                }
                EnableWidget.updateWidget(requireContext(), isChecked)
            }
            R.id.disable_charge_switch -> {
                if (isChecked) {
                    Utils.changeState(requireContext(), Utils.CHARGE_OFF)
                    settings?.edit()?.putBoolean(Constants.DISABLE_CHARGE_NOW, true)?.apply()
                    disableSwitches(listOf(enableSwitch, limitByVoltageSwitch))
                } else {
                    Utils.changeState(requireContext(), Utils.CHARGE_ON)
                    settings?.edit()?.putBoolean(Constants.DISABLE_CHARGE_NOW, false)?.apply()
                    enableSwitches(listOf(enableSwitch, limitByVoltageSwitch))
                }
            }
            R.id.limit_by_voltage -> {
                if (isChecked) {
                    Utils.setVoltageThreshold(
                        settings?.getString(Constants.CUSTOM_VOLTAGE_LIMIT, Constants.DEFAULT_VOLTAGE_THRESHOLD_MV),
                        false, requireContext(), mHandler
                    )
                    settings?.edit()?.putBoolean(Constants.LIMIT_BY_VOLTAGE, true)?.apply()
                    disableSwitches(listOf(enableSwitch, disableChargeSwitch))
                } else {
                    Utils.setVoltageThreshold(
                        settings?.getString(Constants.DEFAULT_VOLTAGE_LIMIT, "4300"),
                        false, requireContext(), mHandler
                    )
                    settings?.edit()?.putBoolean(Constants.LIMIT_BY_VOLTAGE, false)?.apply()
                    enableSwitches(listOf(enableSwitch, disableChargeSwitch))
                }
            }
        }
    }

    //to update battery status on UI
    private val charging = object : BroadcastReceiver() {
        private var previousStatus = BatteryManager.BATTERY_STATUS_UNKNOWN

        override fun onReceive(context: Context, intent: Intent) {
            val currentStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            if (currentStatus != previousStatus) {
                previousStatus = currentStatus
                when (currentStatus) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> {
                        statusText?.setText(R.string.charging)
                        statusText?.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
                    }
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> {
                        statusText?.setText(R.string.discharging)
                        statusText?.setTextColor(themeColor(com.google.android.material.R.attr.colorTertiary))
                    }
                    BatteryManager.BATTERY_STATUS_FULL -> {
                        statusText?.setText(R.string.full)
                        statusText?.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
                    }
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> {
                        statusText?.setText(R.string.not_charging)
                        statusText?.setTextColor(themeColor(com.google.android.material.R.attr.colorTertiary))
                    }
                    else -> {
                        statusText?.setText(R.string.unknown)
                        statusText?.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }
                }
            }
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            if (level >= 0) {
                batteryLevelText?.text = getString(R.string.percent, level)
            }
            updateBatteryInfo(intent)
            updateTemperature(intent)
        }
    }

    private fun themeColor(attr: Int): Int {
        return MaterialColors.getColor(requireView(), attr, Color.GRAY)
    }

    private fun updateBatteryInfo(intent: Intent) {
        val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        batteryInfo?.text = if (millivolts > 0) {
            getString(R.string.battery_voltage_value, millivolts / 1000f)
        } else {
            ""
        }
    }

    private fun updateTemperature(intent: Intent) {
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) {
            monitorTemperature?.setText(R.string.monitor_unknown)
            return
        }
        val fahrenheit = prefs?.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false) == true
        val degrees = if (fahrenheit) 32f + tenths * 1.8f / 10f else tenths / 10f
        monitorTemperature?.text = String.format(
            Locale.ROOT,
            getString(if (fahrenheit) R.string.temperature_fahrenheit else R.string.temperature_celsius),
            degrees
        )
    }

    private fun applyReading(reading: PowerReading) {
        val unknown = getString(R.string.monitor_unknown)
        val power = reading.batteryWatts
        if (power == null) {
            monitorPower?.text = unknown
        } else {
            monitorPower?.text = if (power >= 0) {
                getString(R.string.monitor_power_charging, power)
            } else {
                getString(R.string.monitor_power_load, -power)
            }
            powerChart?.addSample(power.toFloat())
        }
        monitorCapacity?.text = when {
            reading.fullMah == null -> unknown
            reading.capacityPercent != null ->
                getString(R.string.monitor_capacity_value, reading.fullMah, reading.capacityPercent)
            else -> getString(R.string.monitor_capacity_mah, reading.fullMah)
        }
        monitorCycles?.text = reading.cycles?.toString() ?: unknown
        monitorTimeToFull?.text = when {
            reading.timeToFullMinutes == null -> unknown
            reading.timeToFullMinutes >= 60 ->
                getString(
                    R.string.monitor_time_hours_minutes,
                    reading.timeToFullMinutes / 60,
                    reading.timeToFullMinutes % 60
                )
            else -> getString(R.string.monitor_time_minutes, reading.timeToFullMinutes)
        }
        monitorCharger?.text = when {
            reading.chargerVolts == null -> unknown
            reading.chargerAmpsMax != null ->
                getString(R.string.monitor_charger_value, reading.chargerVolts, reading.chargerAmpsMax)
            else -> getString(R.string.monitor_charger_volts, reading.chargerVolts)
        }
        batteryHealth?.text = reading.health?.let { getString(R.string.monitor_health, it) } ?: unknown
    }

    private fun hideKeybord() {
        val inputManager = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (inputManager.isAcceptingText) {
            inputManager.hideSoftInputFromWindow(activity?.currentFocus?.windowToken, 0)
        }
    }

    private fun disableSwitches(switches: List<SwitchMaterial?>) {
        for (switch in switches) {
            switch?.isEnabled = false
        }
    }

    private fun enableSwitches(switches: List<SwitchMaterial?>) {
        for (switch in switches) {
            switch?.isEnabled = true
        }
    }

    private fun setStatusCTRLFileData() {
        val statusCTRLData = view?.findViewById<TextView>(R.id.status_ctrl_data)
        statusCTRLData?.text = String.format(
            "%s, %s, %s",
            Utils.getCtrlFileData(requireContext()),
            Utils.getCtrlEnabledData(requireContext()),
            Utils.getCtrlDisabledData(requireContext())
        )
    }

    private fun updateUi() {
        suppressListeners = true
        try {
            enableSwitch?.isChecked = settings?.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false) == true
            disableChargeSwitch?.isChecked = settings?.getBoolean(Constants.DISABLE_CHARGE_NOW, false) == true
            limitByVoltageSwitch?.isChecked = settings?.getBoolean(Constants.LIMIT_BY_VOLTAGE, false) == true
            val max = settings?.getInt(Constants.LIMIT, 80) ?: 80
            limitSlider?.value = max.toFloat()
            limitValue?.text = getString(R.string.percent, max)
            updateSliderRanges(max)
            val dischargeActive = settings?.getBoolean(Constants.DISCHARGE_ACTIVE, false) == true
            dischargeSwitch?.isChecked = dischargeActive
            dischargeSlider?.isEnabled = !dischargeActive
            updateDischargeText(settings?.getInt(Constants.DISCHARGE_TARGET, Constants.MIN_DISCHARGE_TARGET_PC))
        } finally {
            suppressListeners = false
        }
    }

    /**
     * Keeps the recharge and discharge ranges inside the limit. The sliders are
     * moved out of the way before the range shrinks so their value stays valid.
     */
    private fun updateSliderRanges(limit: Int) {
        val maxLimit = limit.coerceIn(Constants.MIN_ALLOWED_LIMIT_PC, Constants.MAX_ALLOWED_LIMIT_PC)
        val rechargeMax = (maxLimit - 2).coerceAtLeast(0)
        val recharge = (settings?.getInt(Constants.MIN, maxLimit - 2) ?: (maxLimit - 2))
            .coerceIn(0, rechargeMax)
        rechargeSlider?.value = recharge.toFloat().coerceAtMost(rechargeMax.toFloat())
        rechargeSlider?.valueTo = rechargeMax.toFloat()
        rechargeSlider?.value = recharge.toFloat()
        rechargeValue?.text = getString(R.string.percent, recharge)

        val targetMax = maxLimit.coerceAtLeast(Constants.MIN_DISCHARGE_TARGET_PC)
        val target = (settings?.getInt(Constants.DISCHARGE_TARGET, Constants.MIN_DISCHARGE_TARGET_PC)
            ?: Constants.MIN_DISCHARGE_TARGET_PC)
            .coerceIn(Constants.MIN_DISCHARGE_TARGET_PC, targetMax)
        dischargeSlider?.value = Constants.MIN_DISCHARGE_TARGET_PC.toFloat()
        dischargeSlider?.valueTo = targetMax.toFloat()
        dischargeSlider?.value = target.toFloat()
        dischargeValue?.text = getString(R.string.percent, target)
    }

    private fun updateDischargeText(target: Int?) {
        dischargeText?.text = getString(R.string.discharging_until_x, target ?: Constants.MIN_DISCHARGE_TARGET_PC)
    }

    private companion object {
        const val MONITOR_INTERVAL_MS = 3000L
    }
}
