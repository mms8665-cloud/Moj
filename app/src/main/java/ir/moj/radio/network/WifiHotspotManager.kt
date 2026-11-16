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
import java.net.Inet4Address

data class LocalHotspotCredentials(
    val ssid: String,
    val password: String
)

class WifiHotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // پیدا کردن IP واقعی گوشی مرکزی (Gateway) پس از اتصال وای‌فای
    @Suppress("DEPRECATION")
    fun getWifiGatewayIp(): String? {
        runCatching {
            val net = boundNetwork ?: connectivityManager.activeNetwork
            val props = connectivityManager.getLinkProperties(net)
            val routeGateway = props?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            if (!routeGateway.isNullOrBlank() && routeGateway != "0.0.0.0") {
                return routeGateway
            }
        }
        return runCatching {
            val gw = wifiManager.dhcpInfo?.gateway ?: 0
            if (gw == 0) null
            else "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        }.getOrNull()
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
                    val rawSsid: String
                    val rawPass: String
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val config = reservation.softApConfiguration
                        rawSsid = config.ssid ?: ""
                        rawPass = config.passphrase ?: ""
                    } else {
                        val config = reservation.wifiConfiguration
                        rawSsid = config?.SSID ?: ""
                        rawPass = config?.preSharedKey ?: ""
                    }
                    val ssid = rawSsid.removeSurrounding("\"").trim()
                    val pass = rawPass.removeSurrounding("\"").trim()
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

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connectToWifi(
        ssid: String,
        password: String,
        onConnected: (gatewayIp: String?) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val cleanSsid = ssid.removeSurrounding("\"").trim()
        val cleanPass = password.removeSurrounding("\"").trim()

        if (!wifiManager.isWifiEnabled) {
            openWifiPanel()
            onFailed("ابتدا وای‌فای را روشن کنید و دوباره QR را اسکن کنید")
            return
        }

        val currentSsid = runCatching {
            wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")?.trim()
        }.getOrNull()
        if (currentSsid == cleanSsid) {
            onConnected(getWifiGatewayIp())
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            openWifiPanel()
            onFailed("دستی به وای‌فای $cleanSsid با رمز $cleanPass وصل شوید")
            return
        }

        disconnectWifi()

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(cleanSsid)
                .setWpa2Passphrase(cleanPass)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    boundNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    Handler(Looper.getMainLooper()).postDelayed({
                        onConnected(getWifiGatewayIp())
                    }, 900L)
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Handler(Looper.getMainLooper()).post {
                        onFailed("اتصال خودکار وای‌فای انجام نشد؛ دستی به $cleanSsid وصل شوید")
                    }
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback, 25000)
        } catch (e: Exception) {
            onFailed("خطا در اتصال وای‌فای: ${e.message ?: "نامشخص"}")
        }
    }

    fun disconnectWifi() {
        activeNetworkCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        activeNetworkCallback = null
        boundNetwork = null
        runCatching { connectivityManager.bindProcessToNetwork(null) }
    }

    fun release() {
        stopLocalHotspot()
        disconnectWifi()
    }
}
