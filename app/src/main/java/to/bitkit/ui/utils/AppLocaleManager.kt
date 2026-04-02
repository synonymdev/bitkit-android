package to.bitkit.ui.utils

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.models.Language
import to.bitkit.models.getLanguageTag
import to.bitkit.models.toLanguage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLocaleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun changeLanguage(language: Language) {
        val languageTag = language.getLanguageTag()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (languageTag.isEmpty()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(languageTag)
                }
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (languageTag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(languageTag)
                }
            )
        }
    }

    fun getCurrentLanguage(): Language {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeList = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            localeList?.get(0).takeIf { localeList != null && !localeList.isEmpty }
        } else {
            val localeListCompat = AppCompatDelegate.getApplicationLocales()
            localeListCompat.get(0).takeIf { !localeListCompat.isEmpty }
        }

        return locale?.toLanguage() ?: Language.SYSTEM_DEFAULT
    }

    fun getSupportedLanguages(): List<Language> {
        return Language.entries.sortedWith(
            compareBy<Language> { !it.isSystemDefault }.thenBy { it.nativeName }
        )
    }
}
