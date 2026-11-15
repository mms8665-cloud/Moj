package ir.moj.radio.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

data class LocalHotspotCredentials(
    val ssid: String,
    val password: String
)

class WifiHotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun startLocalHotspot(
        onStarted: (LocalHotspotCredentials) -> Unit,
        onFailed: (String) -> Unit
    ) {
        stopLocalHotspot()
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    if (reservation == null) {
                        onFailed("هات‌اسپات محلی راه‌اندازی نشد")
                        return
                    }
                    hotspotReservation = reservation
                    val ssid: String
                    val pass: String
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        ssid = config.ssid ?: ""
                        pass = config.passphrase ?: ""
                    } else {
                        val config = reservation.wifiConfiguration
                        ssid = config?.SSID ?: ""
                        pass = config?.preSharedKey ?: ""
                    }
                    if (ssid.isNotBlank() && pass.isNotBlank()) {
                        onStarted(LocalHotspotCredentials(ssid, pass))
                    } else {
                        onFailed("اطلاعات هات‌اسپات دریافت نشد")
                    }
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    onFailed("امکان روشن کردن هات‌اسپات محلی وجود ندارد (کد $reason)")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onFailed(e.message ?: "خطا در دسترسی هات‌اسپات")
        }
    }

    fun stopLocalHotspot() {
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
    }

    fun connectToWifi(
        ssid: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openWifiPanel()
            onFailed("در اندروید ۸ و ۹ لطفاً به شبکه $ssid با رمز $password وصل شوید")
            return
        }

        disconnectWifi()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                Handler(Looper.getMainLooper()).post { onConnected() }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Handler(Looper.getMainLooper()).post {
                    onFailed("اتصال به وای‌فای $ssid لغو شد یا برقرار نشد")
                }
            }
        }

        activeNetworkCallback = callback
        runCatching {
            connectivityManager.requestNetwork(request, callback)
        }.onFailure {
            onFailed("خطا در درخواست اتصال وای‌فای")
        }
    }

    fun disconnectWifi() {
        activeNetworkCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        activeNetworkCallback = null
        runCatching { connectivityManager.bindProcessToNetwork(null) }
    }

    fun release() {
        stopLocalHotspot()
        disconnectWifi()
    }
}
