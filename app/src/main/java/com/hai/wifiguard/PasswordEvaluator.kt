package com.hai.wifiguard

import kotlin.math.max
import kotlin.math.min

internal data class PasswordAssessment(
    val score: Int,
    val label: String,
    val notes: List<String>,
)

internal object PasswordEvaluator {
    private val veryCommon = setOf(
        "12345678", "123456789", "1234567890", "password", "password1",
        "qwerty123", "qwertyuiop", "admin123", "abcdefgh", "11111111",
        "00000000", "87654321", "letmein123", "wifi12345",
    )

    fun evaluate(password: String, ssid: String? = null): PasswordAssessment {
        if (password.isEmpty()) {
            return PasswordAssessment(0, "أدخل كلمة المرور", listOf("الاختبار محلي ولا يتم حفظ النص أو إرساله."))
        }

        var score = 0
        val notes = mutableListOf<String>()
        val length = password.length

        score += when {
            length >= 24 -> 52
            length >= 20 -> 46
            length >= 16 -> 38
            length >= 12 -> 28
            length >= 10 -> 20
            length >= 8 -> 12
            else -> 2
        }

        val classes = listOf(
            password.any(Char::isLowerCase),
            password.any(Char::isUpperCase),
            password.any(Char::isDigit),
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        score += classes * 8

        if (password.toSet().size >= min(12, max(6, length / 2))) score += 8

        val normalized = password.lowercase().trim()
        if (normalized in veryCommon) {
            score -= 60
            notes += "هذه من كلمات المرور الشائعة جدًا ويجب تغييرها."
        }

        if (Regex("(.)\\1{3,}").containsMatchIn(password)) {
            score -= 18
            notes += "يوجد تكرار طويل للحرف أو الرقم نفسه."
        }

        if (listOf("1234", "2345", "3456", "abcd", "qwer", "asdf").any { normalized.contains(it) }) {
            score -= 14
            notes += "تحتوي على تسلسل متوقع يسهل تخمينه."
        }

        val cleanSsid = ssid
            ?.lowercase()
            ?.replace(" ", "")
            ?.takeIf { it.length >= 4 && it != "غيرمتاح" }
        if (cleanSsid != null && normalized.replace(" ", "").contains(cleanSsid)) {
            score -= 18
            notes += "لا تستخدم اسم الشبكة داخل كلمة المرور."
        }

        if (length < 16) notes += "لشبكة منزلية، اجعلها 16 حرفًا على الأقل؛ والأفضل 20+ إذا أمكن."
        if (classes < 3 && length < 20) notes += "زد التنوع بين الحروف والأرقام والرموز أو استخدم عبارة مرور طويلة جدًا."
        if (notes.isEmpty()) notes += "الطول والتنوع جيدان، بشرط ألا تكون الكلمة مستخدمة في خدمة أخرى."
        notes += "لا تُشارك كلمة المرور في لقطات شاشة أو رسائل؛ هذا التقييم يتم داخل الجهاز فقط."

        val finalScore = score.coerceIn(0, 100)
        val label = when {
            finalScore >= 85 -> "قوية جدًا"
            finalScore >= 70 -> "قوية"
            finalScore >= 50 -> "متوسطة"
            else -> "ضعيفة"
        }

        return PasswordAssessment(finalScore, label, notes.distinct())
    }
}
