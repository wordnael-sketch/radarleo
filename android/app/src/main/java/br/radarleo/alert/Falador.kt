package br.radarleo.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import br.radarleo.RadarLeoApp
import java.util.Locale

/**
 * Voz (TextToSpeech em pt-BR) e bipe. Usa o canal de "orientação de navegação",
 * o mesmo do Waze e do Maps: o Android abaixa a música enquanto fala.
 */
class Falador(contexto: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(contexto.applicationContext, this)
    @Volatile private var pronto = false
    @Volatile private var pendente: String? = null // frase pedida antes de o TTS ficar pronto

    /** 0..1, multiplica o volume da voz (ajuste da tela de configurações). */
    @Volatile var volume = 1f

    /** Desligada: o aviso de distância vira um toque curto e o excesso, só o bipe. */
    @Volatile var vozLigada = true
    private val bipe = ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(RadarLeoApp.TAG, "TextToSpeech não iniciou (status $status): alertas só com bipe")
            return
        }
        val idioma = tts.setLanguage(Locale("pt", "BR"))
        if (idioma == TextToSpeech.LANG_MISSING_DATA || idioma == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(RadarLeoApp.TAG, "Voz pt-BR não instalada; usando a voz padrão do aparelho")
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        pronto = true
        pendente?.let { falar(it, imediato = true, volume = 1f) }
        pendente = null
    }

    fun tocar(e: Evento) {
        val frase = Frases.de(e)
        Log.i(RadarLeoApp.TAG, "Alerta: $frase (radar ${e.radar.id}, ${"%.0f".format(e.distanciaM)} m)")
        val relativo = if (e.discreto) VOLUME_DISCRETO else 1f
        when (e) {
            // excesso: bipe e a frase passa na frente de qualquer outra que estiver na fila
            is Excesso -> {
                bipe.startTone(ToneGenerator.TONE_PROP_BEEP2, DURACAO_BIPE_MS)
                if (vozLigada) falar(frase, imediato = true, volume = relativo)
            }
            is Aviso ->
                if (vozLigada) falar(frase, imediato = false, volume = relativo)
                else bipe.startTone(ToneGenerator.TONE_PROP_ACK, DURACAO_TOQUE_MS)
        }
    }

    /** Botão "Testar voz" das configurações. */
    fun testar(frase: String) {
        if (pronto) falar(frase, imediato = true, volume = 1f) else pendente = frase
    }

    private fun falar(frase: String, imediato: Boolean, volume: Float) {
        if (!pronto) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (volume * this@Falador.volume).coerceIn(0f, 1f))
        }
        val fila = if (imediato) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(frase, fila, params, frase)
    }

    fun encerrar() {
        tts.stop()
        tts.shutdown()
        bipe.release()
    }

    private companion object {
        const val VOLUME_DISCRETO = 0.5f
        const val DURACAO_BIPE_MS = 400
        const val DURACAO_TOQUE_MS = 150
    }
}
