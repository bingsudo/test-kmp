package com.example.kmptest.presentation.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.example.kmptest.presentation.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The translations of one language (with English behind it for anything missing). Placeholders are {name}. */
class Strings(private val primary: Map<String, String>, private val fallback: Map<String, String> = emptyMap()) {
    operator fun get(key: String): String = primary[key] ?: fallback[key] ?: key

    companion object {
        val Empty = Strings(emptyMap())
    }
}

fun String.fill(vararg params: Pair<String, Any?>): String {
    var out = this
    for ((k, v) in params) out = out.replace("{$k}", v.toString())
    return out
}

val LocalStrings = compositionLocalOf { Strings.Empty }

@Composable
fun str(key: String): String = LocalStrings.current[key]

/**
 * The supported languages. Codes are the file names of the locale files; each language's own name (shown untranslated
 * in the picker) is the "_name" entry of its file.
 */
object Languages {
    const val AUTO = "auto"

    val codes: List<String> = listOf(
        "cs", "de", "el", "en", "es", "fr", "hi", "hu", "id", "it", "ja", "ko", "ms",
        "nl", "pl", "pt-BR", "pt-PT", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh-CN", "zh-TW",
    )

    private var names: Map<String, String>? = null

    /** Code -> the language's name in that language, in [codes] order. */
    suspend fun nativeNames(): Map<String, String> =
        names ?: codes.associateWith { read(it)[NAME_KEY] ?: it }.also { names = it }

    private const val NAME_KEY = "_name"

    /** Maps a setting ("auto" or a code) and the system's language tag (BCP 47, e.g. "zh-Hant-TW") to a supported code. */
    fun resolve(setting: String, systemTag: String): String {
        if (setting != AUTO && setting in codes) return setting
        val parts = systemTag.replace('_', '-').split('-').map { it.lowercase() }
        val language = parts.firstOrNull().orEmpty()
        val script = parts.firstOrNull { it.length == 4 }
        val region = parts.drop(1).firstOrNull { it.length == 2 }
        return when (language) {
            "zh" -> if (script == "hans" || region in setOf("cn", "sg")) "zh-CN" else "zh-TW"
            // Brazilian Portuguese unless the region says otherwise (Portugal, Angola, Mozambique, ...)
            "pt" -> if (region == null || region == "br") "pt-BR" else "pt-PT"
            "in" -> "id"
            else -> codes.firstOrNull { it.lowercase() == language } ?: "en"
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun read(code: String): Map<String, String> =
        (json.parseToJsonElement(Res.readBytes("files/locales/$code.json").decodeToString()) as JsonObject)
            .mapValues { it.value.jsonPrimitive.content }

    suspend fun load(code: String): Strings = Strings(read(code), if (code == "en") emptyMap() else read("en"))
}
