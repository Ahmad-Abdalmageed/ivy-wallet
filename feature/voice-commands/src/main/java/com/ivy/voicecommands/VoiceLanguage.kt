package com.ivy.voicecommands

/**
 * The language voice commands are spoken in, passed to the speech recognizer.
 * [tag] is a BCP 47 language tag, or null for the phone's language.
 */
enum class VoiceLanguage(
    val tag: String?,
    val displayName: String?,
) {
    Device(tag = null, displayName = null),
    English(tag = "en-US", displayName = "English"),
    ArabicEgypt(tag = "ar-EG", displayName = "العربية (مصر)"),
    ArabicSaudiArabia(tag = "ar-SA", displayName = "العربية (السعودية)"),
}
