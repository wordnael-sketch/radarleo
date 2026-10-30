package br.radarleo.ui

import br.radarleo.data.BaseInfo

/** "Base atualizada em 30/10 — 1889 radares" a partir de gerado_em ("2026-10-30T12:02:57-03:00"). */
fun rotuloBase(info: BaseInfo?, total: Int): String? {
    if (info == null) return null
    val data = info.geradoEm.take(10).split("-") // [AAAA, MM, DD]
    val ddmm = if (data.size == 3) "${data[2]}/${data[1]}" else info.geradoEm
    return "Base atualizada em $ddmm — $total radares"
}
