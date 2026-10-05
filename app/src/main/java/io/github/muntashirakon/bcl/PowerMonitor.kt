// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.bcl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * A snapshot of the values the kernel exposes for the battery and the charger.
 *
 * All fields are nullable because the power supply nodes differ between devices;
 * a value that is missing or implausible is reported as `null` instead of a
 * made-up number.
 */
data class PowerReading(
    /** Battery power in watts. Positive charges the battery, negative is a load. */
    val batteryWatts: Double? = null,
    val chargerVolts: Double? = null,
    val chargerAmpsMax: Double? = null,
    val chargerWatts: Double? = null,
    val fullMah: Int? = null,
    val designMah: Int? = null,
    val cycles: Int? = null,
    val health: String? = null,
    val timeToFullMinutes: Int? = null,
    val charging: Boolean = false,
) {
    /** Full capacity as a share of the design capacity, when both are credible. */
    val capacityPercent: Int?
        get() {
            val design = designMah ?: return null
            val full = fullMah ?: return null
            if (design <= 0) return null
            return (full * 100 / design).takeIf { it in 30..130 }
        }
}

/**
 * Reads the battery and charger power supply nodes in one root shell round trip
 * and turns them into a [PowerReading].
 *
 * MediaTek kernels are inconsistent about units (volts in µV or mV, currents in
 * µA or mA), so the scale is derived from the magnitude instead of assumed.
 */
object PowerMonitor {
    private val TAG = PowerMonitor::class.java.simpleName
    private const val BATTERY = "/sys/class/power_supply/battery"
    private const val USB = "/sys/class/power_supply/usb"
    private const val MASTER_CHARGER = "/sys/class/power_supply/mtk-master-charger"

    private val BATTERY_NODES = linkedMapOf(
        "battery_current" to "current_now",
        "battery_voltage" to "voltage_now",
        "charge_counter" to "charge_counter",
        "charge_full" to "charge_full",
        "charge_full_design" to "charge_full_design",
        "cycle_count" to "cycle_count",
        "health" to "health",
    )
    private val USB_NODES = linkedMapOf(
        "usb_voltage_now" to "voltage_now",
        "usb_current_now" to "current_now",
        "usb_current_max" to "current_max",
        "usb_input_current_now" to "input_current_now",
        "usb_online" to "online",
    )
    private val MASTER_NODES = linkedMapOf(
        "master_input_current_limit" to "input_current_limit",
        "master_voltage_now" to "voltage_now",
    )
    private val READ_COMMAND: String = buildList {
        for ((key, node) in BATTERY_NODES) add("echo \"$key=$(cat $BATTERY/$node 2>/dev/null)\"")
        for ((key, node) in USB_NODES) add("echo \"$key=$(cat $USB/$node 2>/dev/null)\"")
        for ((key, node) in MASTER_NODES) add("echo \"$key=$(cat $MASTER_CHARGER/$node 2>/dev/null)\"")
    }.joinToString("; ")

    /** Reads the values off the main thread and delivers the result on the main thread. */
    fun readAsync(context: Context, callback: (PowerReading) -> Unit) {
        Shell.cmd(READ_COMMAND).submit { result ->
            val reading = try {
                if (result.isSuccess) toReading(parseKeyValues(result.out)) else PowerReading()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the power supply values", e)
                PowerReading()
            }
            Handler(Looper.getMainLooper()).post { callback(reading) }
        }
    }

    internal fun parseKeyValues(lines: List<String>): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (line in lines) {
            val separator = line.indexOf('=')
            if (separator > 0) {
                values[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
            }
        }
        return values
    }

    internal fun toReading(values: Map<String, String>): PowerReading {
        val batteryVolts = parseVolts(values["battery_voltage"])
        val batteryAmps = parseAmps(values["battery_current"])
        val batteryWatts = if (batteryVolts != null && batteryAmps != null) {
            batteryVolts * batteryAmps
        } else {
            null
        }
        val fullAh = parseMicro(values["charge_full"])
        val counterAh = parseMicro(values["charge_counter"])
        val currentMicroAmps = values["battery_current"]?.trim()?.toLongOrNull()
        val charging = batteryAmps != null && batteryAmps > 0
        val timeToFull = if (charging && fullAh != null && counterAh != null && currentMicroAmps != null) {
            val remainingMicroAh = fullAh - counterAh
            val minutes = (remainingMicroAh * 60 / currentMicroAmps).toInt()
            minutes.takeIf { it in 1..(24 * 60) }
        } else {
            null
        }
        // The USB volts are in mV and the max current in µA on this kernel.
        val chargerVolts = parseVolts(values["usb_voltage_now"]) ?: parseVolts(values["master_voltage_now"])
        val chargerAmpsMax = parseAmps(values["usb_current_max"])
            ?: parseAmps(values["master_input_current_limit"])
        val chargerAmps = parseAmps(values["usb_current_now"])
            ?: parseAmps(values["usb_input_current_now"])
        val designRaw = parseMicro(values["charge_full_design"])
        return PowerReading(
            batteryWatts = batteryWatts,
            chargerVolts = chargerVolts,
            chargerAmpsMax = chargerAmpsMax,
            chargerWatts = if (chargerVolts != null && chargerAmps != null && chargerAmps > 0) {
                chargerVolts * chargerAmps
            } else {
                null
            },
            fullMah = fullAh?.let { (it / 1000).toInt() },
            // A design capacity outside 1000-15000 mAh is not credible on a phone.
            designMah = designRaw?.let { (it / 1000).toInt() }?.takeIf { it in 1000..15000 },
            cycles = values["cycle_count"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            health = values["health"]?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) },
            timeToFullMinutes = timeToFull,
            charging = charging,
        )
    }

    /** Converts a raw voltage to volts: µV and mV are told apart by magnitude. */
    internal fun parseVolts(raw: String?): Double? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        val absolute = kotlin.math.abs(value)
        return when {
            absolute >= 100_000 -> value / 1_000_000.0
            absolute >= 1_000 -> value / 1_000.0
            else -> value
        }
    }

    /** Converts a raw current to amperes: µA and mA are told apart by magnitude. */
    internal fun parseAmps(raw: String?): Double? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        val absolute = kotlin.math.abs(value)
        return when {
            absolute >= 100_000 -> value / 1_000_000.0
            absolute >= 1_000 -> value / 1_000.0
            else -> value
        }
    }

    private fun parseMicro(raw: String?): Long? = raw?.trim()?.toLongOrNull()
}
