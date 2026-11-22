package moe.reimu.catshare

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import moe.reimu.catshare.services.GattServerService

/**
 * Hooks that make CatShare behave like a built-in system feature when it is installed as a
 * privileged system app (MikuUI liuqin): the share-sheet "nearby" slot and receive-at-boot.
 */
object SystemIntegration {
    private const val TAG = "CatShareSystem"

    // Settings.Secure.NEARBY_SHARING_COMPONENT (hidden API); IntentResolver reads it and falls
    // back to config_defaultNearbySharingComponent when it is empty.
    private const val NEARBY_SHARING_COMPONENT = "nearby_sharing_component"
    private const val PREF_PREVIOUS_COMPONENT = "previousNearbyComponent"

    fun ownShareComponent(context: Context): String =
        ComponentName(context, ShareActivity::class.java).flattenToString()

    private fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Takes (or gives back) the system share sheet's nearby-sharing button. */
    fun applyShareSheetSlot(context: Context, enabled: Boolean) {
        if (!canWriteSecureSettings(context)) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; leaving the share sheet alone")
            return
        }
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, NEARBY_SHARING_COMPONENT)
        val own = ownShareComponent(context)
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        try {
            if (enabled) {
                if (current != own) {
                    if (!current.isNullOrEmpty()) {
                        prefs.edit { putString(PREF_PREVIOUS_COMPONENT, current) }
                    }
                    Settings.Secure.putString(resolver, NEARBY_SHARING_COMPONENT, own)
                    Log.i(TAG, "Share sheet nearby slot: $current -> $own")
                }
            } else if (current == own) {
                val previous = prefs.getString(PREF_PREVIOUS_COMPONENT, "") ?: ""
                Settings.Secure.putString(resolver, NEARBY_SHARING_COMPONENT, previous)
                Log.i(TAG, "Share sheet nearby slot given back to '$previous'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update the share sheet slot", e)
        }
    }

    /** Starts the BLE receiver (advertising + GATT server) if Bluetooth is on. */
    fun startReceiverIfPossible(context: Context) {
        val needed = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        if (needed.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            Log.i(TAG, "Bluetooth permissions missing; not starting the receiver")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.i(TAG, "Bluetooth is off; not starting the receiver")
            return
        }
        GattServerService.start(context)
    }
}
