package com.hai.wifiguard

import android.os.Build
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

internal enum class MonitorModeState {
    NOT_EXPOSED_BY_ANDROID,
    PRIVILEGED_STACK_DETECTED,
    UNKNOWN,
}

internal enum class PacketCaptureState {
    ANDROID_TRAFFIC_ONLY,
    PRIVILEGED_CAPTURE_TOOL_DETECTED,
    UNKNOWN,
}

internal data class DeviceWifiCapabilities(
    val device: String,
    val androidVersion: String,
    val wifiInterfaces: List<String>,
    val rootIndicators: Boolean,
    val iwPresent: Boolean,
    val tcpdumpPresent: Boolean,
    val wirelessKernelInterfacePresent: Boolean,
    val monitorMode: MonitorModeState,
    val packetCapture: PacketCaptureState,
    val selectedAuditProfile: String,
    val notes: List<String>,
)

/**
 * Performs a read-only capability inspection.
 *
 * This detector does not enable monitor mode, inject frames, disconnect clients,
 * capture credentials, or execute privileged commands. It only checks whether
 * Android/kernel/tooling indicators exist so the app can choose an honest audit
 * profile instead of showing an unsupported "attack" button.
 */
internal object DeviceCapabilityDetector {

    fun detect(): DeviceWifiCapabilities {
        val interfaces = detectWifiInterfaces()
        val rootIndicators = hasRootIndicators()
        val iwPresent = binaryExists(
            "/system/bin/iw",
            "/system/xbin/iw",
            "/vendor/bin/iw",
            "/product/bin/iw",
        )
        val tcpdumpPresent = binaryExists(
            "/system/bin/tcpdump",
            "/system/xbin/tcpdump",
            "/vendor/bin/tcpdump",
            "/product/bin/tcpdump",
            "/data/local/tmp/tcpdump",
        )
        val wirelessKernelInterfacePresent = hasWirelessKernelInterface(interfaces)

        val monitorMode = when {
            rootIndicators && iwPresent && wirelessKernelInterfacePresent ->
                MonitorModeState.PRIVILEGED_STACK_DETECTED
            wirelessKernelInterfacePresent ->
                MonitorModeState.NOT_EXPOSED_BY_ANDROID
            else -> MonitorModeState.UNKNOWN
        }

        val packetCapture = when {
            rootIndicators && tcpdumpPresent ->
                PacketCaptureState.PRIVILEGED_CAPTURE_TOOL_DETECTED
            wirelessKernelInterfacePresent ->
                PacketCaptureState.ANDROID_TRAFFIC_ONLY
            else -> PacketCaptureState.UNKNOWN
        }

        val profile = when {
            monitorMode == MonitorModeState.PRIVILEGED_STACK_DETECTED ->
                "فحص متقدم متاح من ناحية البيئة — يحتاج تحقق فعلي من تعريف Wi‑Fi"
            wirelessKernelInterfacePresent ->
                "فحص Android + الشبكة المحلية"
            else ->
                "فحص Android الأساسي"
        }

        val notes = buildList {
            if (interfaces.isEmpty()) {
                add("لم يتم اكتشاف اسم واجهة Wi‑Fi من مساحة التطبيق.")
            } else {
                add("واجهة Wi‑Fi المكتشفة: ${interfaces.joinToString("، ")}")
            }

            if (rootIndicators) {
                add("توجد مؤشرات Root/بيئة معدلة، لكن هذا لا يثبت أن تعريف Wi‑Fi يدعم Monitor Mode.")
            } else {
                add("لا توجد مؤشرات Root واضحة؛ تطبيق Android العادي لا يملك وصولًا خامًا لإطارات 802.11.")
            }

            if (iwPresent) {
                add("تم العثور على أداة iw في النظام؛ وجودها وحده لا يضمن Monitor Mode.")
            }

            if (tcpdumpPresent) {
                add("تم العثور على tcpdump؛ قد يلتقط حركة الشبكة المتاحة للنظام، وليس بالضرورة إطارات Wi‑Fi الخام.")
            }

            when (monitorMode) {
                MonitorModeState.PRIVILEGED_STACK_DETECTED ->
                    add("البيئة تحمل مؤشرات تسمح باختبار دعم Monitor Mode لاحقًا، دون افتراض أنه يعمل.")
                MonitorModeState.NOT_EXPOSED_BY_ANDROID ->
                    add("Monitor Mode غير مكشوف لتطبيق Android العادي على هذا الجهاز.")
                MonitorModeState.UNKNOWN ->
                    add("تعذر إثبات دعم Monitor Mode من داخل مساحة التطبيق.")
            }
        }

        return DeviceWifiCapabilities(
            device = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android device" },
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            wifiInterfaces = interfaces,
            rootIndicators = rootIndicators,
            iwPresent = iwPresent,
            tcpdumpPresent = tcpdumpPresent,
            wirelessKernelInterfacePresent = wirelessKernelInterfacePresent,
            monitorMode = monitorMode,
            packetCapture = packetCapture,
            selectedAuditProfile = profile,
            notes = notes,
        )
    }

    private fun detectWifiInterfaces(): List<String> = runCatching {
        val enumeration = NetworkInterface.getNetworkInterfaces()
        val allInterfaces = if (enumeration != null) Collections.list(enumeration) else emptyList()
        allInterfaces
            .map { it.name.orEmpty() }
            .filter { name ->
                name.startsWith("wlan", ignoreCase = true) ||
                    name.startsWith("wifi", ignoreCase = true) ||
                    name.startsWith("p2p", ignoreCase = true)
            }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun hasRootIndicators(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/app/Superuser.apk",
        )
        return Build.TAGS?.contains("test-keys") == true || suPaths.any { File(it).exists() }
    }

    private fun binaryExists(vararg paths: String): Boolean =
        paths.any { path -> File(path).let { it.exists() && it.isFile } }

    private fun hasWirelessKernelInterface(interfaces: List<String>): Boolean {
        val ieee80211 = runCatching {
            File("/sys/class/ieee80211").listFiles()?.isNotEmpty() == true
        }.getOrDefault(false)

        val procWireless = runCatching {
            val file = File("/proc/net/wireless")
            file.canRead() && file.readText().lineSequence().any { line ->
                interfaces.any { iface -> line.contains("$iface:") }
            }
        }.getOrDefault(false)

        return ieee80211 || procWireless || interfaces.isNotEmpty()
    }
}
