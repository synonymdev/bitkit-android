package to.bitkit.models

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import to.bitkit.R
import java.util.Locale

@Serializable
enum class Language(
    @StringRes val displayNameResId: Int? = null,
    val nativeName: String? = null,
    val languageCode: String,
    val countryCode: String? = null,
    val isSystemDefault: Boolean = false,
) {
    SYSTEM_DEFAULT(
        displayNameResId = R.string.settings__language_system_default,
        languageCode = "system",
        countryCode = null,
        isSystemDefault = true
    ),
    ARABIC(nativeName = "العربية", languageCode = "ar"),
    CATALAN(nativeName = "Català", languageCode = "ca"),
    CZECH(nativeName = "Čeština", languageCode = "cs"),
    DUTCH(nativeName = "Nederlands", languageCode = "nl"),
    ENGLISH(nativeName = "English", languageCode = "en", countryCode = "US"),
    FRENCH(nativeName = "Français", languageCode = "fr", countryCode = "FR"),
    GERMAN(nativeName = "Deutsch", languageCode = "de"),
    GREEK(nativeName = "Ελληνικά", languageCode = "el"),
    ITALIAN(nativeName = "Italiano", languageCode = "it"),
    POLISH(nativeName = "Polski", languageCode = "pl"),
    PORTUGUESE(nativeName = "Português", languageCode = "pt", countryCode = "BR"),
    RUSSIAN(nativeName = "Русский", languageCode = "ru"),
    SPANISH(nativeName = "Español", languageCode = "es", countryCode = "ES"),
    SPANISH_LATIN_AMERICA(nativeName = "Español (Latinoamérica)", languageCode = "es", countryCode = "419");

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
