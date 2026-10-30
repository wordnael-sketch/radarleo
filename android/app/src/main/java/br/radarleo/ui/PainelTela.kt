package br.radarleo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.radarleo.R
import br.radarleo.overlay.EstadoBolha
import br.radarleo.overlay.NivelVelocidade

/** Tela principal: velocímetro grande, próximo radar, iniciar/parar e reportar. */
@Composable
fun PainelTela(
    ativo: Boolean,
    velocidadeKmh: Double?,
    proximo: EstadoBolha?,
    podeReportar: Boolean,
    aoIniciar: () -> Unit,
    aoParar: () -> Unit,
    aoReportar: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Velocimetro(ativo, velocidadeKmh, proximo?.nivel ?: NivelVelocidade.NORMAL)
        CartaoProximo(ativo, proximo)
        Spacer(Modifier.weight(1f))
        if (ativo) {
            OutlinedButton(onClick = aoReportar, enabled = podeReportar, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.reportar_aqui))
            }
            Button(
                onClick = aoParar, modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.parar), style = MaterialTheme.typography.titleMedium) }
        } else {
            Button(onClick = aoIniciar, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(stringResource(R.string.iniciar), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Velocimetro(ativo: Boolean, kmh: Double?, nivel: NivelVelocidade) {
    val texto = if (ativo) kmh?.let { "%.0f".format(it) } ?: "–" else "–"
    val descricao = stringResource(R.string.velocidade_descricao, texto)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = descricao },
    ) {
        Text(
            texto, fontSize = 120.sp, fontWeight = FontWeight.Bold, lineHeight = 120.sp,
            color = corDaVelocidade(nivel, MaterialTheme.colorScheme.onSurface),
        )
        Text(stringResource(R.string.km_h), style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CartaoProximo(ativo: Boolean, p: EstadoBolha?) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p == null) {
                Text(
                    stringResource(if (ativo) R.string.nenhum_radar_a_frente else R.string.inativo_dica),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Row
            }
            Icon(painterResource(iconeDoTipo(p.tipo)), contentDescription = null, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(
                    stringResource(if (p.possivel) R.string.bolha_possivel else nomeDoTipo(p.tipo)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (p.possivel) AMARELO_ALERTA else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(p.distanciaTexto, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            p.limiteKmh?.let { PlacaLimite(it) }
        }
    }
}
