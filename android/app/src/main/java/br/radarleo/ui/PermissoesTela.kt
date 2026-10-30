package br.radarleo.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.radarleo.R
import br.radarleo.location.Etapa

private data class Textos(@StringRes val titulo: Int, @StringRes val explicacao: Int, @StringRes val botao: Int)

private fun textos(e: Etapa) = when (e) {
    Etapa.LOCALIZACAO_PRECISA -> Textos(R.string.perm_precisa_titulo, R.string.perm_precisa_texto, R.string.perm_continuar)
    Etapa.LOCALIZACAO_SEGUNDO_PLANO -> Textos(R.string.perm_fundo_titulo, R.string.perm_fundo_texto, R.string.perm_continuar)
    Etapa.NOTIFICACOES -> Textos(R.string.perm_notif_titulo, R.string.perm_notif_texto, R.string.perm_continuar)
    Etapa.SOBREPOSICAO -> Textos(R.string.perm_sobrepor_titulo, R.string.perm_sobrepor_texto, R.string.perm_abrir_config)
}

/**
 * Explica a permissão ANTES de o Android mostrar o pedido. Só depois do toque em
 * "Continuar" o diálogo do sistema aparece.
 */
@Composable
fun PermissaoTela(
    etapa: Etapa,
    passo: Int,
    totalPassos: Int,
    negadaDeVez: Boolean,
    aoContinuar: () -> Unit,
    aoAbrirConfiguracoes: () -> Unit,
    aoPular: () -> Unit,
) {
    val t = textos(etapa)
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.perm_passo, passo, totalPassos), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(t.titulo), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(t.explicacao), style = MaterialTheme.typography.bodyLarge)
        if (negadaDeVez) {
            Text(stringResource(R.string.perm_negada_de_vez), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = aoAbrirConfiguracoes) { Text(stringResource(R.string.perm_abrir_config)) }
        } else {
            Button(onClick = aoContinuar) { Text(stringResource(t.botao)) }
        }
        if (!etapa.obrigatoria) {
            TextButton(onClick = aoPular) { Text(stringResource(R.string.perm_agora_nao)) }
        }
    }
}
