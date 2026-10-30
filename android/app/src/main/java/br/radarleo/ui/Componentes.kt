package br.radarleo.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.radarleo.R
import br.radarleo.data.Fonte
import br.radarleo.data.TipoRadar
import br.radarleo.overlay.NivelVelocidade

/** Uma cor por fonte, igual no mapa e na legenda. */
object CoresFonte {
    val OSM = Color(0xFF1A73E8)
    val DNIT = Color(0xFFF29900)
    val ANTT = Color(0xFF8D6E63)
    val LAI = Color(0xFF188038)
    val MANUAL = Color(0xFF9334E6)
    val USUARIO = Color(0xFFD93025)

    fun de(fonte: String): Color = when (Fonte.entries.firstOrNull { it.name == fonte }) {
        Fonte.OSM -> OSM
        Fonte.DNIT -> DNIT
        Fonte.ANTT -> ANTT
        Fonte.LAI_DETRAN_DF, Fonte.LAI_DER_DF -> LAI
        Fonte.MANUAL -> MANUAL
        Fonte.USUARIO -> USUARIO
        null -> Color.Gray
    }

    /** Na ordem da legenda. */
    val legenda: List<Pair<Int, Color>> = listOf(
        R.string.fonte_osm to OSM, R.string.fonte_dnit to DNIT, R.string.fonte_lai to LAI,
        R.string.fonte_manual to MANUAL, R.string.fonte_usuario to USUARIO, R.string.fonte_antt to ANTT,
    )
}

val AMARELO_ALERTA = Color(0xFFF9AB00)
val VERMELHO_ALERTA = Color(0xFFD93025)

fun corDaVelocidade(nivel: NivelVelocidade, normal: Color): Color = when (nivel) {
    NivelVelocidade.NORMAL -> normal
    NivelVelocidade.ACIMA -> AMARELO_ALERTA
    NivelVelocidade.EXCESSO -> VERMELHO_ALERTA
}

@DrawableRes
fun iconeDoTipo(tipo: TipoRadar): Int = when (tipo) {
    TipoRadar.SEMAFORO -> R.drawable.ic_tipo_semaforo
    TipoRadar.LOMBADA -> R.drawable.ic_tipo_lombada
    TipoRadar.VELOCIDADE_MEDIA -> R.drawable.ic_tipo_media
    TipoRadar.FIXO, TipoRadar.DESCONHECIDO -> R.drawable.ic_tipo_fixo
}

@StringRes
fun nomeDoTipo(tipo: TipoRadar): Int = when (tipo) {
    TipoRadar.FIXO -> R.string.tipo_fixo
    TipoRadar.SEMAFORO -> R.string.tipo_semaforo
    TipoRadar.LOMBADA -> R.string.tipo_lombada
    TipoRadar.VELOCIDADE_MEDIA -> R.string.tipo_media
    TipoRadar.DESCONHECIDO -> R.string.tipo_desconhecido
}

/** Placa de regulamentação de velocidade: círculo branco, borda vermelha. */
@Composable
fun PlacaLimite(limite: Int, tamanho: Dp = 56.dp) {
    Box(
        Modifier.size(tamanho).background(Color.White, CircleShape)
            .border(tamanho / 9, VERMELHO_ALERTA, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            limite.toString(), color = Color.Black, fontWeight = FontWeight.Bold,
            fontSize = (tamanho.value * 0.36f).sp,
        )
    }
}
