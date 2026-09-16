package com.hai.wifiguard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuditApp(viewModel: AuditViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.runAudit()
    }

    fun runWithPermissions() {
        val missing = permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(permissions)
        } else {
            viewModel.runAudit()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HAI WiFi Guard", fontWeight = FontWeight.Bold)
                        Text(
                            "اختبار أمان الشبكة المتصل بها الجهاز",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Surface(
                        modifier = Modifier.padding(start = 12.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HeroCard(
                    scanning = state.isScanning,
                    result = state.result,
                    onScan = ::runWithPermissions,
                )
            }

            state.error?.let { error ->
                item { NoticeCard(error, warning = true) }
            }

            state.result?.let { result ->
                item { ConnectionCard(result) }
                item { DeviceCapabilitiesCard(result.deviceCapabilities) }
                item { RouterCard(result) }
                item { PasswordCard(ssid = result.ssid) }
                item { RecommendationsCard(result.recommendations) }
                item {
                    NoticeCard(
                        "النتائج مبنية على ما يستطيع Android كشفه فعليًا. التطبيق لا يدّعي دعم Monitor Mode أو Raw 802.11 ما لم تظهر مؤشرات البيئة المطلوبة.",
                        warning = false,
                    )
                }
            } ?: item {
                EmptyState()
            }
        }
    }
}

@Composable
private fun HeroCard(
    scanning: Boolean,
    result: WifiAuditResult?,
    onScan: () -> Unit,
) {
    val status = result?.let(::securityStatusText) ?: "جارٍ تجهيز الفحص"
    val statusColor = when (result?.securityLevel) {
        SecurityLevel.STRONG -> Color(0xFF176B45)
        SecurityLevel.GOOD -> Color(0xFF2E6B57)
        SecurityLevel.REVIEW -> Color(0xFF8A641A)
        SecurityLevel.WEAK -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "فحص Wi‑Fi عملي",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "يتعرف على الشبكة والجهاز ويختار أقوى مستوى فحص متاح فعليًا.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                    )
                }
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Surface(
                shape = RoundedCornerShape(100.dp),
                color = Color.White.copy(alpha = 0.72f),
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !scanning,
                onClick = onScan,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("جارٍ اكتشاف الشبكة والقدرات…")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (result == null) "ابدأ الفحص" else "أعد الفحص")
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(result: WifiAuditResult) {
    SectionCard(
        title = "الشبكة الحالية",
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
    ) {
        InfoRow("اسم الشبكة", result.ssid)
        InfoRow("نوع الحماية", result.security)
        InfoRow("BSSID", result.bssid)
        InfoRow(
            "WPS",
            when (result.wps) {
                WpsState.DETECTED -> "ظاهر — يحتاج مراجعة"
                WpsState.NOT_ADVERTISED -> "غير ظاهر"
                WpsState.UNKNOWN -> "تعذر التحقق"
            },
        )
        if (result.permissionLimited) {
            Spacer(Modifier.height(8.dp))
            Text(
                "بعض بيانات Wi‑Fi محجوبة حتى تمنح صلاحية الشبكات القريبة/الموقع المطلوبة من Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceCapabilitiesCard(capabilities: DeviceWifiCapabilities) {
    SectionCard(
        title = "قدرات الاختبار على هذا الجهاز",
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
    ) {
        InfoRow("الجهاز", capabilities.device)
        InfoRow("النظام", capabilities.androidVersion)
        InfoRow(
            "واجهة Wi‑Fi",
            capabilities.wifiInterfaces.ifEmpty { listOf("غير ظاهرة") }.joinToString("، "),
        )
        InfoRow("Root", if (capabilities.rootIndicators) "توجد مؤشرات" else "غير ظاهر")
        InfoRow("iw", if (capabilities.iwPresent) "موجود" else "غير موجود")
        InfoRow("tcpdump", if (capabilities.tcpdumpPresent) "موجود" else "غير موجود")
        InfoRow("Monitor Mode", monitorModeText(capabilities.monitorMode))
        InfoRow("Packet Capture", packetCaptureText(capabilities.packetCapture))

        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("أفضل ملف فحص متاح", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(capabilities.selectedAuditProfile)
            }
        }

        if (capabilities.notes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            capabilities.notes.take(4).forEach { note ->
                BulletLine(note)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun RouterCard(result: WifiAuditResult) {
    SectionCard(
        title = "الراوتر والاتصال المحلي",
        icon = { Icon(Icons.Outlined.Router, contentDescription = null) },
    ) {
        InfoRow("البوابة", result.gateway)
        InfoRow("IP الجهاز", result.localIp)
        InfoRow("DNS", result.dnsServers.ifEmpty { listOf("غير متاح") }.joinToString(" · "))
        InfoRow(
            "منافذ الإدارة",
            if (result.openAdminPorts.isEmpty()) {
                "لم تظهر على المنافذ الشائعة"
            } else {
                result.openAdminPorts.joinToString("، ")
            },
        )
        Text(
            "يختبر التطبيق البوابة المحلية فقط على المنافذ الشائعة 80 / 443 / 8080 / 8443.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordCard(ssid: String) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var assessment by remember { mutableStateOf<PasswordAssessment?>(null) }

    SectionCard(
        title = "قوة كلمة مرور شبكتك",
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
    ) {
        Text(
            "اختياري: أدخل كلمة مرور شبكتك لتقييمها محليًا. لا تُحفظ ولا تُرسل.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = password,
            onValueChange = {
                password = it
                assessment = null
            },
            singleLine = true,
            label = { Text("كلمة مرور Wi‑Fi") },
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "إخفاء" else "إظهار",
                    )
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { assessment = PasswordEvaluator.evaluate(password, ssid) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("قيّم القوة داخل الجهاز")
        }

        val value = assessment
        if (value != null) {
            Column(
                modifier = Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(value.label, fontWeight = FontWeight.Bold)
                    Text("${value.score}/100", fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { value.score / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                value.notes.forEach { note -> BulletLine(note) }
            }
        }
    }
}

@Composable
private fun RecommendationsCard(recommendations: List<String>) {
    SectionCard(
        title = "النتيجة والتوصيات",
        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
    ) {
        recommendations.forEachIndexed { index, text ->
            BulletLine(text)
            if (index != recommendations.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text("اتصل بشبكة Wi‑Fi التي تملكها ثم شغّل الفحص.")
        }
    }
}

@Composable
private fun NoticeCard(text: String, warning: Boolean) {
    val container = if (warning) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val tint = if (warning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (warning) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = tint,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(modifier = Modifier.padding(8.dp)) { icon() }
                }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(text, modifier = Modifier.weight(1f))
    }
}

private fun monitorModeText(state: MonitorModeState): String = when (state) {
    MonitorModeState.PRIVILEGED_STACK_DETECTED -> "مؤشرات بيئة متقدمة موجودة"
    MonitorModeState.NOT_EXPOSED_BY_ANDROID -> "غير مكشوف لتطبيق Android"
    MonitorModeState.UNKNOWN -> "غير مؤكد"
}

private fun packetCaptureText(state: PacketCaptureState): String = when (state) {
    PacketCaptureState.PRIVILEGED_CAPTURE_TOOL_DETECTED -> "أداة التقاط متقدمة موجودة"
    PacketCaptureState.ANDROID_TRAFFIC_ONLY -> "ضمن حدود Android"
    PacketCaptureState.UNKNOWN -> "غير مؤكد"
}

private fun securityStatusText(result: WifiAuditResult): String {
    if (!result.connected) return "غير متصل بـ Wi‑Fi"
    return when (result.securityLevel) {
        SecurityLevel.STRONG -> "الحماية الأساسية قوية"
        SecurityLevel.GOOD -> "الحماية جيدة — راجع التوصيات"
        SecurityLevel.REVIEW -> "تحتاج مراجعة"
        SecurityLevel.WEAK -> "الحماية تحتاج تعديل"
        SecurityLevel.UNKNOWN -> "تعذر تحديد مستوى الحماية"
    }
}
