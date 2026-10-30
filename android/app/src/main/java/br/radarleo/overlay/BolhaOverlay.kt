package br.radarleo.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.radarleo.R
import br.radarleo.data.TipoRadar
import br.radarleo.ui.MainActivity
import kotlin.math.abs

/**
 * Bolha sobre outros apps (Waze, Maps): ícone do tipo, distância regressiva, placa com o
 * limite e a velocidade atual (amarela acima do limite, vermelha acima da tolerância).
 * Arrastável; um toque abre o RadarLeo. Só deve ser usada na thread principal.
 */
class BolhaOverlay(private val contexto: Context) {

    private val janelas = contexto.getSystemService(WindowManager::class.java)
    private val prefs = contexto.getSharedPreferences("bolha", Context.MODE_PRIVATE)
    private var raiz: LinearLayout? = null
    private lateinit var icone: ImageView
    private lateinit var distancia: TextView
    private lateinit var rotuloPossivel: TextView
    private lateinit var placa: TextView
    private lateinit var velocidade: TextView

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // não rouba o foco nem os toques fora dela: o Waze continua usável
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(POS_X, dp(16))
        y = prefs.getInt(POS_Y, dp(160))
    }

    /** null esconde a bolha (radar ficou para trás ou não há nenhum à frente). */
    fun mostrar(estado: EstadoBolha?) {
        if (estado == null || !Settings.canDrawOverlays(contexto)) {
            remover()
            return
        }
        val v = raiz ?: criar().also {
            raiz = it
            janelas.addView(it, params)
        }
        icone.setImageResource(iconeDo(estado.tipo))
        distancia.text = estado.distanciaTexto
        rotuloPossivel.visibility = if (estado.possivel) View.VISIBLE else View.GONE
        placa.visibility = if (estado.limiteKmh != null) View.VISIBLE else View.GONE
        placa.text = estado.limiteKmh?.toString() ?: ""
        velocidade.text = estado.velocidadeTexto
        velocidade.setTextColor(
            when (estado.nivel) {
                NivelVelocidade.NORMAL -> Color.WHITE
                NivelVelocidade.ACIMA -> AMARELO
                NivelVelocidade.EXCESSO -> VERMELHO
            }
        )
        v.contentDescription = contexto.getString(
            R.string.bolha_descricao, estado.distanciaTexto, estado.limiteKmh?.toString() ?: "–", estado.velocidadeTexto,
        )
    }

    fun remover() {
        raiz?.let { runCatching { janelas.removeView(it) } }
        raiz = null
    }

    private fun criar(): LinearLayout {
        val linha = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(FUNDO)
            }
            elevation = dp(6).toFloat()
        }
        icone = ImageView(contexto).apply { layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)) }
        val coluna = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
        }
        distancia = texto(22f, Color.WHITE, negrito = true)
        rotuloPossivel = texto(11f, AMARELO).apply { setText(R.string.bolha_possivel) }
        coluna.addView(distancia)
        coluna.addView(rotuloPossivel)

        // placa de regulamentação: círculo branco com borda vermelha
        placa = texto(17f, Color.BLACK, negrito = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(5), VERMELHO)
            }
        }
        velocidade = texto(24f, Color.WHITE, negrito = true).apply {
            minWidth = dp(48)
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
        }
        val unidade = texto(11f, CINZA).apply { setText(R.string.km_h); setPadding(dp(4), dp(8), 0, 0) }

        linha.addView(icone)
        linha.addView(coluna)
        linha.addView(placa)
        linha.addView(velocidade)
        linha.addView(unidade)
        arrastavel(linha)
        return linha
    }

    @SuppressLint("ClickableViewAccessibility") // o toque simples chama performClick
    private fun arrastavel(v: View) {
        var inicioX = 0
        var inicioY = 0
        var toqueX = 0f
        var toqueY = 0f
        var arrastou = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    inicioX = params.x; inicioY = params.y
                    toqueX = e.rawX; toqueY = e.rawY
                    arrastou = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - toqueX
                    val dy = e.rawY - toqueY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) arrastou = true
                    if (arrastou) {
                        params.x = inicioX + dx.toInt()
                        params.y = inicioY + dy.toInt()
                        janelas.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (arrastou) {
                        prefs.edit().putInt(POS_X, params.x).putInt(POS_Y, params.y).apply()
                    } else {
                        v.performClick()
                    }
                }
            }
            true
        }
        v.setOnClickListener {
            contexto.startActivity(
                Intent(contexto, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun texto(sp: Float, cor: Int, negrito: Boolean = false) = TextView(contexto).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (negrito) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun iconeDo(tipo: TipoRadar) = when (tipo) {
        TipoRadar.SEMAFORO -> R.drawable.ic_tipo_semaforo
        TipoRadar.LOMBADA -> R.drawable.ic_tipo_lombada
        TipoRadar.VELOCIDADE_MEDIA -> R.drawable.ic_tipo_media
        TipoRadar.FIXO, TipoRadar.DESCONHECIDO -> R.drawable.ic_tipo_fixo
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), contexto.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val POS_X = "x"
        const val POS_Y = "y"
        val FUNDO = Color.argb(235, 32, 33, 36)
        val CINZA = Color.rgb(189, 193, 198)
        val AMARELO = Color.rgb(253, 214, 99)
        val VERMELHO = Color.rgb(234, 67, 53)
    }
}
