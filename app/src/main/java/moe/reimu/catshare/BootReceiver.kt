package moe.reimu.catshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        Log.i("CatShareSystem", "Applying system integration after ${intent.action}")
        val settings = AppSettings(context)
        SystemIntegration.applyShareSheetSlot(context, settings.shareSheetSlot)
        if (settings.receiveOnBoot) {
            SystemIntegration.startReceiverIfPossible(context)
        }
    }
}
