package to.bitkit.models

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class Language(
    val displayName: String,
    val languageCode: String,
    val countryCode: String? = null,
    val isSystemDefault: Boolean = false,
) {
    SYSTEM_DEFAULT(
        displayName = "System Settings",
        languageCode = "system",
        countryCode = null,
        isSystemDefault = true
    ),
    ARABIC("العربية", "ar"),
    CATALAN("Català", "ca"),
    CZECH("Čeština", "cs"),
    DUTCH("Nederlands", "nl"),
    ENGLISH("English", "en", "US"),
    FRENCH("Français", "fr", "FR"),
    GERMAN("Deutsch", "de"),
    GREEK("Ελληνικά", "el"),
    ITALIAN("Italiano", "it"),
    POLISH("Polski", "pl"),
    PORTUGUESE("Português", "pt", "BR"),
    RUSSIAN("Русский", "ru"),
    SPANISH("Español", "es", "ES"),
    SPANISH_LATIN_AMERICA("Español (Latinoamérica)", "es", "419");

    companion object {
        fun fromLanguageCode(languageCode: String, countryCode: String? = null): Language? {
            return entries.find { language ->
                language.languageCode == languageCode &&
                    (countryCode == null || language.countryCode == countryCode)
            }
        }

        fun fromLocale(locale: Locale): Language? {
            return fromLanguageCode(locale.language, locale.country.ifEmpty { null })
        }
    }
}

fun Language.getLanguageTag(): String {
    return if (isSystemDefault) {
        ""
    } else {
        if (countryCode != null) {
            "$languageCode-$countryCode"
        } else {
            languageCode
        }
    }
}

fun Locale.toLanguage(): Language? {
    return Language.fromLocale(this)
}
