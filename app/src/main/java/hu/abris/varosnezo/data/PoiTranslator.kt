package hu.abris.varosnezo.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import hu.abris.varosnezo.Poi
import kotlinx.coroutines.tasks.await

/** Ingyenes, a telefonon futó fordítás (ML Kit). Első használatkor letölti a nyelvi modellt (~30 MB). */
object PoiTranslator {
    suspend fun translate(pois: List<Poi>, from: String, to: String): List<Poi> {
        if (from == to || pois.isEmpty()) return pois
        val src = TranslateLanguage.fromLanguageTag(from) ?: error("Nem támogatott nyelv: $from")
        val dst = TranslateLanguage.fromLanguageTag(to) ?: error("Nem támogatott nyelv: $to")
        val t = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
        try {
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return pois.map { it.copy(title = t.translate(it.title).await(), text = t.translate(it.text).await()) }
        } finally {
            t.close()
        }
    }
}
