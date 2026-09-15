package com.hai.wifiguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal data class WifiAuditResult(
    val connected: Boolean,
    val ssid: String,
    val bssid: String,
    val security: String,
    val securityLevel: SecurityLevel,
    val capabilities: String,
    val wps: WpsState,
    val gateway: String,
    val localIp: String,
    val dnsServers: List<String>,
    val openAdminPorts: List<Int>,
    val permissionLimited: Boolean,
    val deviceCapabilities: DeviceWifiCapabilities,
    val recommendations: List<String>,
)

internal enum class SecurityLevel { STRONG, GOOD, REVIEW, WEAK, UNKNOWN }
internal enum class WpsState { DETECTED, NOT_ADVERTISED, UNKNOWN }

internal class AuditRepository(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun audit(): WifiAuditResult = withContext(Dispatchers.IO) {
        val deviceCapabilities = DeviceCapabilityDetector.detect()

        val network = connectivityManager.activeNetwork
            ?: return@withContext disconnectedResult(deviceCapabilities)
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return@withContext disconnectedResult(deviceCapabilities)

        if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return@withContext disconnectedResult(deviceCapabilities)
        }

        val linkProperties = connectivityManager.getLinkProperties(network)
        val permissionLimited = !hasWifiIdentityPermission()
        val wifiInfo = currentWifiInfo(networkCapabilities)

        val ssid = cleanSsid(wifiInfo?.ssid)
        val bssid = wifiInfo?.bssid
            ?.takeUnless { it == "02:00:00:00:00:00" }
            ?: "غير متاح"

        val matchingScan = runCatching {
            wifiManager.scanResults.firstOrNull { scan ->
                val currentBssid = wifiInfo?.bssid
                currentBssid != null && currentBssid != "02:00:00:00:00:00" &&
                    scan.BSSID.equals(currentBssid, ignoreCase = true)
            }
        }.getOrNull()

        val rawCapabilities = matchingScan?.capabilities.orEmpty()
        val security = securityFromWifiInfo(wifiInfo)
            ?: securityFromCapabilities(rawCapabilities)
        val securityLevel = levelForSecurity(security)

        val wps = when {
            rawCapabilities.contains("WPS", ignoreCase = true) -> WpsState.DETECTED
            rawCapabilities.isNotBlank() -> WpsState.NOT_ADVERTISED
            else -> WpsState.UNKNOWN
        }

        val gatewayAddress = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
        val gateway = gatewayAddress?.hostAddress ?: "غير متاح"

        val localIp = linkProperties?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: "غير متاح"

        val dnsServers = linkProperties?.dnsServers
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

        val openPorts = if (gatewayAddress != null && isLocalAddress(gatewayAddress)) {
            listOf(80, 443, 8080, 8443).filter { isTcpPortOpen(gatewayAddress, it) }
        } else {
            emptyList()
        }

        val recommendations = buildRecommendations(
            security = security,
            wps = wps,
            openPorts = openPorts,
            permissionLimited = permissionLimited,
            deviceCapabilities = deviceCapabilities,
        )

        WifiAuditResult(
            connected = true,
            ssid = ssid,
            bssid = bssid,
            security = security,
            securityLevel = securityLevel,
            capabilities = rawCapabilities.ifBlank { "غير متاحة من Android" },
            wps = wps,
            gateway = gateway,
            localIp = localIp,
            dnsServers = dnsServers,
            openAdminPorts = openPorts,
            permissionLimited = permissionLimited,
            deviceCapabilities = deviceCapabilities,
            recommendations = recommendations,
        )
    }

    private fun currentWifiInfo(capabilities: NetworkCapabilities): WifiInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.transportInfo as? WifiInfo
        } else {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo
        }
    }

    private fun securityFromWifiInfo(info: WifiInfo?): String? {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return when (info.currentSecurityType) {
            WifiInfo.SECURITY_TYPE_OPEN -> "Open"
            WifiInfo.SECURITY_TYPE_WEP -> "WEP"
            WifiInfo.SECURITY_TYPE_PSK -> "WPA2-PSK / PSK"
            WifiInfo.SECURITY_TYPE_EAP -> "WPA2-Enterprise / EAP"
            WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192-bit"
            WifiInfo.SECURITY_TYPE_OWE -> "OWE"
            WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
            WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
            else -> null
        }
    }

    private fun securityFromCapabilities(capabilities: String): String {
        val value = capabilities.uppercase()
        return when {
            value.contains("SAE") || value.contains("WPA3") -> "WPA3-SAE"
            value.contains("WPA2") || value.contains("RSN-PSK") -> "WPA2-PSK"
            value.contains("WPA-") || value.contains("WPA_PSK") -> "WPA-PSK"
            value.contains("WEP") -> "WEP"
            value.contains("OWE") -> "OWE"
            value.isBlank() -> "غير معروف"
            else -> "Open / غير معروف"
        }
    }

    private fun levelForSecurity(security: String): SecurityLevel = when {
        security.startsWith("WPA3") -> SecurityLevel.STRONG
        security.startsWith("WPA2") -> SecurityLevel.GOOD
        security == "OWE" -> SecurityLevel.REVIEW
        security.startsWith("WPA-") -> SecurityLevel.WEAK
        security == "WEP" || security.startsWith("Open") -> SecurityLevel.WEAK
        else -> SecurityLevel.UNKNOWN
    }

    private fun buildRecommendations(
        security: String,
        wps: WpsState,
        openPorts: List<Int>,
        permissionLimited: Boolean,
        deviceCapabilities: DeviceWifiCapabilities,
    ): List<String> {
        val items = mutableListOf<String>()

        items += "ملف الاختبار المختار تلقائيًا: ${deviceCapabilities.selectedAuditProfile}."
        items += when (deviceCapabilities.monitorMode) {
            MonitorModeState.PRIVILEGED_STACK_DETECTED ->
                "الجهاز يحمل مؤشرات Root + iw + واجهة Wi‑Fi. هذا يعني أن بيئة الفحص المتقدم قد تكون ممكنة، لكن دعم Monitor Mode نفسه لا يُفترض قبل التحقق من تعريف Wi‑Fi."
            MonitorModeState.NOT_EXPOSED_BY_ANDROID ->
                "Monitor Mode غير مكشوف لتطبيق Android العادي على هذا الجهاز؛ سيستخدم التطبيق أقوى فحص متاح عبر Android والشبكة المحلية."
            MonitorModeState.UNKNOWN ->
                "تعذر إثبات دعم Monitor Mode من مساحة التطبيق؛ لن يعرض التطبيق اختبارًا غير مدعوم على أنه حقيقي."
        }

        items += when (deviceCapabilities.packetCapture) {
            PacketCaptureState.PRIVILEGED_CAPTURE_TOOL_DETECTED ->
                "تم العثور على مؤشرات لأداة Packet Capture بصلاحيات مرتفعة؛ وجود الأداة لا يثبت إمكانية التقاط إطارات 802.11 الخام."
            PacketCaptureState.ANDROID_TRAFFIC_ONLY ->
                "المتاح حاليًا هو فحص حركة/خصائص الشبكة التي يسمح بها Android، وليس Raw 802.11 Packet Capture."
            PacketCaptureState.UNKNOWN ->
                "تعذر إثبات قدرة Packet Capture منخفضة المستوى على هذا الجهاز."
        }

        when {
            security.startsWith("WPA3") ->
                items += "التشفير الحالي قوي. استخدم WPA3 فقط إذا كانت جميع أجهزتك تدعمه."
            security.startsWith("WPA2") ->
                items += "WPA2 مناسب عند استخدام AES/CCMP، لكن WPA3 أفضل إذا كان الراوتر والأجهزة يدعمونه."
            security == "WEP" || security.startsWith("WPA-") || security.startsWith("Open") ->
                items += "غيّر وضع الحماية فورًا إلى WPA2-AES أو WPA3؛ الوضع الحالي قديم أو غير آمن."
            else ->
                items += "تعذر تحديد نوع التشفير بدقة؛ راجعه من لوحة إعدادات الراوتر."
        }

        when (wps) {
            WpsState.DETECTED -> items += "WPS ظاهر في قدرات نقطة الوصول. عطّله من إعدادات الراوتر إذا لم تكن تحتاجه."
            WpsState.NOT_ADVERTISED -> items += "لم يظهر WPS في بيانات المسح، لكن Android لا يضمن أن هذا يعني أنه معطّل؛ تحقق من لوحة الراوتر."
            WpsState.UNKNOWN -> items += "تعذر التحقق من WPS من Android؛ افحص خيار WPS يدويًا داخل لوحة الراوتر."
        }

        if (80 in openPorts && 443 !in openPorts) {
            items += "واجهة إدارة الراوتر تستجيب عبر HTTP محليًا. استخدم HTTPS إن كان الراوتر يوفّره، ولا تفعّل الإدارة من الإنترنت."
        } else if (443 in openPorts || 8443 in openPorts) {
            items += "تم العثور على واجهة إدارة HTTPS محلية؛ أبقِ الإدارة عن بُعد من الإنترنت معطّلة."
        }

        if (permissionLimited) {
            items += "صلاحية Wi‑Fi غير مكتملة؛ منحها يسمح بعرض اسم الشبكة وبيانات الحماية المتاحة بدقة أكبر."
        }

        items += deviceCapabilities.notes
        items += "حدّث Firmware الراوتر، وغيّر كلمة مرور لوحة الإدارة عن كلمة مرور Wi‑Fi، واستخدم كلمة Wi‑Fi طويلة وفريدة."
        return items.distinct()
    }

    private fun hasWifiIdentityPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun cleanSsid(value: String?): String {
        if (value.isNullOrBlank() || value == WifiManager.UNKNOWN_SSID) return "غير متاح"
        return value.removePrefix("\"").removeSuffix("\"")
    }

    private fun isLocalAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress

    private fun isTcpPortOpen(address: InetAddress, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), 450)
            true
        }
    }.getOrDefault(false)

    private fun disconnectedResult(deviceCapabilities: DeviceWifiCapabilities) = WifiAuditResult(
        connected = false,
        ssid = "غير متصل",
        bssid = "غير متاح",
        security = "غير متاح",
        securityLevel = SecurityLevel.UNKNOWN,
        capabilities = "غير متاح",
        wps = WpsState.UNKNOWN,
        gateway = "غير متاح",
        localIp = "غير متاح",
        dnsServers = emptyList(),
        openAdminPorts = emptyList(),
        permissionLimited = false,
        deviceCapabilities = deviceCapabilities,
        recommendations = buildList {
            add("اتصل بشبكة Wi‑Fi التي تملكها ثم أعد الفحص.")
            add("ملف الاختبار المتاح على هذا الجهاز: ${deviceCapabilities.selectedAuditProfile}.")
            addAll(deviceCapabilities.notes)
        },
    )
}
