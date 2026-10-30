package br.radarleo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import br.radarleo.R
import br.radarleo.data.TipoRadar

/**
 * "Reportar radar aqui": a posição e o rumo já vêm do GPS no momento do toque;
 * o usuário só escolhe o tipo (e, se souber, o limite).
 */
@Composable
fun ReportarDialogo(
    rumoGraus: Double?,
    aoConfirmar: (TipoRadar, Int?) -> Unit,
    aoCancelar: () -> Unit,
) {
    var tipo by remember { mutableStateOf(TipoRadar.FIXO) }
    var limite by remember { mutableStateOf("") }
    val limiteValido = limite.isEmpty() || limite.toIntOrNull()?.let { it in 20..130 } == true

    AlertDialog(
        onDismissRequest = aoCancelar,
        title = { Text(stringResource(R.string.reportar_titulo)) },
        text = {
            Column {
                Text(
                    if (rumoGraus != null) stringResource(R.string.reportar_com_rumo, "%.0f".format(rumoGraus))
                    else stringResource(R.string.reportar_sem_rumo),
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOf(TipoRadar.FIXO, TipoRadar.SEMAFORO, TipoRadar.LOMBADA, TipoRadar.VELOCIDADE_MEDIA).forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = tipo == t, onClick = { tipo = t }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tipo == t, onClick = null)
                        Text(stringResource(nomeDoTipo(t)))
                    }
                }
                OutlinedTextField(
                    value = limite,
                    onValueChange = { novo -> limite = novo.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.reportar_limite)) },
                    isError = !limiteValido,
                    supportingText = { if (!limiteValido) Text(stringResource(R.string.reportar_limite_invalido)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { aoConfirmar(tipo, limite.toIntOrNull()) }, enabled = limiteValido) {
                Text(stringResource(R.string.reportar_salvar))
            }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text(stringResource(R.string.cancelar)) } },
    )
}
