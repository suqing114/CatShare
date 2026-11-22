package moe.reimu.catshare.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.BuildConfig
import moe.reimu.catshare.IMacAddressService
import moe.reimu.catshare.services.MacAddressService
import rikka.shizuku.Shizuku
import java.net.NetworkInterface
import kotlin.collections.iterator

object ShizukuUtils {
    private const val ANONYMIZED_MAC = "02:00:00:00:00:00"
    private val binderLock = Object()
    private val serviceNotify = Object()
    private var macService: IMacAddressService? = null

    init {
        Shizuku.addBinderReceivedListenerSticky {
            bindService()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                Log.d(ShizukuUtils.TAG, "Got service connection for $name")

                synchronized(binderLock) {
                    macService = IMacAddressService.Stub.asInterface(service)
                }

                synchronized(serviceNotify) {
                    serviceNotify.notifyAll()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(ShizukuUtils.TAG, "Connection lost for $name")
            synchronized(binderLock) {
                macService = null
            }
        }
    }

    fun unsafeBindService() {
        val cn = ComponentName(
            BuildConfig.APPLICATION_ID, MacAddressService::class.java.name
        )
        val args = Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(args, serviceConnection)
    }

    fun bindService() {
        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun unsafeGetMacAddress(name: String): String? {
        synchronized(binderLock) {
            val svc = macService
            if (svc == null) {
                Log.d(TAG, "MAC service is null, trying to bind")
                unsafeBindService()
            } else {
                return svc.getMacAddressByName(name)
            }
        }

        synchronized(serviceNotify) {
            serviceNotify.wait(1000 * 20)
        }

        synchronized(binderLock) {
            return macService?.p2pMacAddress
        }
    }

    fun getMacAddress(context: Context, name: String, l: (String?) -> Unit) {
        if (name == "p2p0") {
            val manualMac = AppSettings(context).manualMacAddress
            if (manualMac.isNotEmpty()) {
                Log.d(TAG, "Using manually configured MAC address for $name: $manualMac")
                l(manualMac)
                return
            }
        }

        if (context.checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Permission granted, using native method")
            val nativeMac = try {
                nativeGetMacAddressByName(name)
            } catch (e: Throwable) {
                Log.w(TAG, "Native MAC lookup for $name failed", e)
                null
            }
            if (nativeMac != null && nativeMac != ANONYMIZED_MAC) {
                l(nativeMac)
                return
            }
            // SELinux forbids every priv_app from reading link addresses over netlink
            // (priv_app_all: neverallow nlmsg_readpriv), so NetworkInterface returns null
            // even with LOCAL_MAC_ADDRESS. The Wi-Fi framework, however, reports the real
            // P2P device address to callers holding LOCAL_MAC_ADDRESS.
            if (name == "p2p0") {
                Log.d(TAG, "Native lookup unavailable, asking WifiP2pManager")
                requestP2pDeviceAddress(context, l)
                return
            }
            l(nativeMac)
            return
        }

        val th = Thread {
            val res = try {
                unsafeGetMacAddress(name)
            } catch (e: Throwable) {
                Log.e(ShizukuUtils.TAG, "Failed to obtain MAC address for $name", e)
                null
            }

            l(res)
        }
        th.start()
    }

    @SuppressLint("MissingPermission")
    private fun requestP2pDeviceAddress(context: Context, l: (String?) -> Unit) {
        val manager = context.getSystemService(WifiP2pManager::class.java)
        if (manager == null) {
            l(null)
            return
        }
        val channel = manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
        if (channel == null) {
            l(null)
            return
        }
        try {
            manager.requestDeviceInfo(channel) { device ->
                val addr = device?.deviceAddress?.takeIf { it.isNotEmpty() && it != ANONYMIZED_MAC }
                Log.d(TAG, "WifiP2pManager device address: ${addr ?: "unavailable"}")
                channel.close()
                l(addr)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestDeviceInfo failed", e)
            channel.close()
            l(null)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun nativeGetMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }

    suspend fun getMacAddress(context: Context, name: String): String? {
        val fut = CompletableDeferred<String?>()
        getMacAddress(context, name) {
            fut.complete(it)
        }
        return fut.await()
    }
}