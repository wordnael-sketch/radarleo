"""Consolidação: coleta todas as fontes, valida, deduplica e grava output/.

Regras de deduplicação:
- dois registros são o mesmo radar quando estão a menos de 30 m E as direções
  diferem em no máximo 45° (ou uma delas é nula = "os dois sentidos");
- sentidos opostos no mesmo ponto continuam sendo dois radares;
- prevalecem os dados da fonte de maior confiabilidade; campos vazios dela são
  completados pelas demais (limite, rodovia, km, tipo e direção).

Fonte de rede que falhar (ou devolver menos da metade do que trouxe na última vez)
é substituída pela última coleta boa, guardada em output/cache/.

Saídas:
- output/radares.json  {versao, gerado_em, total, radares}
- output/relatorio.md

versao = AAAAMMDD-<hash do conteúdo>. Se o conteúdo não mudou desde a última
geração, nada é regravado (assim o workflow só faz commit quando há novidade).

Uso: python consolidar.py [--saida ../output]
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import hashlib
import json
import logging
import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

from fontes import dnit, manual, osm
from modelo import (Invalido, Radar, ResultadoFonte, TipoRadar, relatorio_invalidos,
                    separar_validos)

log = logging.getLogger("radarleo.consolidar")

SAIDA = Path(__file__).resolve().parents[1] / "output"
RAIO_M = 30.0
TOLERANCIA_DIRECAO = 45.0
CELULA_GRAUS = 0.001  # ~110 m: maior que o raio, então basta olhar as 8 células vizinhas
RAIO_TERRA_M = 6_371_008.8
BRASILIA = timezone(timedelta(hours=-3))  # sem horário de verão desde 2019

# Coletores na ordem de execução. Os de rede usam cache quando falham.
COLETORES: dict[str, Callable[[], ResultadoFonte]] = {
    "osm": osm.coletar,
    "dnit": dnit.coletar,
    "manual": manual.coletar,
}
COM_CACHE = {"osm", "dnit"}
PROPORCAO_MINIMA = 0.5
MAX_AVISOS_RELATORIO = 200


# ---------------------------------------------------------------- geometria

def distancia_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distância de Haversine em metros."""
    f1, f2 = math.radians(lat1), math.radians(lat2)
    df, dl = f2 - f1, math.radians(lon2 - lon1)
    a = math.sin(df / 2) ** 2 + math.cos(f1) * math.cos(f2) * math.sin(dl / 2) ** 2
    return 2 * RAIO_TERRA_M * math.asin(math.sqrt(a))


def diferenca_angular(a: float, b: float) -> float:
    d = abs(a - b) % 360
    return min(d, 360 - d)


def direcoes_compativeis(a: float | None, b: float | None) -> bool:
    return a is None or b is None or diferenca_angular(a, b) <= TOLERANCIA_DIRECAO


# ---------------------------------------------------------------- deduplicação

@dataclass
class _Grupo:
    principal: Radar
    direcao: float | None
    membros: list[Radar] = field(default_factory=list)


@dataclass
class Consolidado:
    radar: Radar
    fontes: list[str]  # todas as fontes que confirmaram este radar, principal primeiro

    def para_dict(self) -> dict:
        return {**self.radar.para_dict(), "fontes": self.fontes}


def _celula(lat: float, lon: float) -> tuple[int, int]:
    return math.floor(lat / CELULA_GRAUS), math.floor(lon / CELULA_GRAUS)


def deduplicar(radares: list[Radar]) -> list[Consolidado]:
    """Agrupa registros do mesmo radar. Determinístico para a mesma entrada.

    Percorre do mais confiável para o menos; cada registro entra no grupo compatível
    mais parecido ou abre um grupo novo. Um registro sem direção que caia perto de
    dois grupos de sentidos opostos completa os dois.
    """
    ordenados = sorted(radares, key=lambda r: (-r.confiabilidade, r.fonte.value, r.id))
    grupos: list[_Grupo] = []
    grade: dict[tuple[int, int], list[_Grupo]] = collections.defaultdict(list)

    for r in ordenados:
        cy, cx = _celula(r.latitude, r.longitude)
        candidatos = []
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for g in grade[(cy + dy, cx + dx)]:
                    p = g.principal
                    d = distancia_m(r.latitude, r.longitude, p.latitude, p.longitude)
                    if d < RAIO_M and direcoes_compativeis(g.direcao, r.direcao_graus):
                        candidatos.append((d, g))

        if not candidatos:
            g = _Grupo(principal=r, direcao=r.direcao_graus)
            grupos.append(g)
            grade[(cy, cx)].append(g)
        elif r.direcao_graus is None:
            for _, g in candidatos:
                g.membros.append(r)
        else:
            def preferencia(c):
                d, g = c
                if g.direcao is None:
                    return (1, 0.0, d)
                return (0, diferenca_angular(g.direcao, r.direcao_graus), d)
            _, g = min(candidatos, key=preferencia)
            g.membros.append(r)
            if g.direcao is None:
                g.direcao = r.direcao_graus

    return [_mesclar(g) for g in grupos]


def _mesclar(g: _Grupo) -> Consolidado:
    p = g.principal
    r = dataclasses.replace(p, direcao_graus=g.direcao)
    for m in g.membros:
        for campo in ("limite_kmh", "rodovia", "km"):
            if getattr(r, campo) is None and getattr(m, campo) is not None:
                setattr(r, campo, getattr(m, campo))
        if r.tipo is TipoRadar.DESCONHECIDO and m.tipo is not TipoRadar.DESCONHECIDO:
            r.tipo = m.tipo
    r.recalcular_id()
    fontes = [p.fonte.value]
    for m in g.membros:
        if m.fonte.value not in fontes:
            fontes.append(m.fonte.value)
    return Consolidado(r, fontes)


def _ids_unicos(consolidados: list[Consolidado]) -> list[str]:
    """Garante ids únicos (colisão só ocorre em casos-limite de arredondamento)."""
    avisos, vistos = [], collections.Counter()
    for c in consolidados:
        base = c.radar.id
        vistos[base] += 1
        if vistos[base] > 1:
            c.radar.id = f"{base}-{vistos[base]}"
            avisos.append(f"id repetido {base}: renomeado para {c.radar.id}")
    return avisos


# ---------------------------------------------------------------- coleta e cache

@dataclass
class InfoFonte:
    nome: str
    coletados: int = 0
    do_cache: bool = False
    avisos: list[str] = field(default_factory=list)


def _ler_cache(pasta: Path, nome: str) -> list[Radar] | None:
    arq = pasta / f"{nome}.json"
    if not arq.exists():
        return None
    return [Radar(**d) for d in json.loads(arq.read_text(encoding="utf-8"))]


def _gravar_cache(pasta: Path, nome: str, radares: list[Radar]) -> None:
    """Só regrava quando o conteúdo mudou, para não gerar commit semanal à toa."""
    arq = pasta / f"{nome}.json"
    dicts = [r.para_dict() for r in radares]
    if arq.exists():
        try:
            if hash_conteudo(json.loads(arq.read_text(encoding="utf-8"))) == hash_conteudo(dicts):
                return
        except ValueError:
            pass
    pasta.mkdir(parents=True, exist_ok=True)
    arq.write_text(json.dumps(dicts, ensure_ascii=False), encoding="utf-8", newline="\n")


def coletar_todas(coletores: dict[str, Callable[[], ResultadoFonte]],
                  pasta_cache: Path) -> tuple[list[Radar], list[InfoFonte]]:
    todos: list[Radar] = []
    infos: list[InfoFonte] = []
    for nome, coletar in coletores.items():
        info = InfoFonte(nome)
        try:
            res = coletar()
        except Exception as e:  # noqa: BLE001 — coletores não deveriam levantar, mas garantimos
            res = ResultadoFonte(fonte=None, avisos=[f"{nome} falhou: {e}"], falhou=True)
        info.avisos.extend(res.avisos)
        radares = res.radares

        if nome in COM_CACHE:
            try:
                anterior = _ler_cache(pasta_cache, nome)
            except Exception as e:  # noqa: BLE001
                anterior = None
                info.avisos.append(f"cache de {nome} ilegível: {e}")
            suspeito = (anterior and not res.falhou
                        and len(radares) < PROPORCAO_MINIMA * len(anterior))
            if res.falhou or suspeito:
                motivo = "falhou" if res.falhou else (
                    f"trouxe {len(radares)} registros contra {len(anterior)} da última coleta")
                if anterior:
                    info.avisos.append(f"{nome} {motivo}: usando a última coleta boa "
                                       f"({len(anterior)} registros)")
                    radares, info.do_cache = anterior, True
                else:
                    info.avisos.append(f"{nome} {motivo} e não há cache: fonte fora desta versão")
            else:
                _gravar_cache(pasta_cache, nome, radares)

        info.coletados = len(radares)
        todos.extend(radares)
        infos.append(info)
        for a in info.avisos:
            log.warning("[%s] %s", nome, a)
    return todos, infos


# ---------------------------------------------------------------- saída

def hash_conteudo(dicts: list[dict]) -> str:
    """Hash dos radares sem a data de coleta (que muda toda semana mesmo sem novidade)."""
    sem_data = [{k: v for k, v in d.items() if k != "atualizado_em"} for d in dicts]
    bruto = json.dumps(sem_data, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(bruto).hexdigest()[:10]


def _json_um_por_linha(cabecalho: dict, radares: list[dict]) -> str:
    """JSON válido com um radar por linha, para o diff do git ficar legível."""
    topo = json.dumps(cabecalho, ensure_ascii=False)[:-1]  # tira o "}"
    linhas = [json.dumps(r, ensure_ascii=False) for r in radares]
    return topo + ', "radares": [\n' + ",\n".join(linhas) + "\n]}\n"


def montar_relatorio(versao: str, gerado_em: str, infos: list[InfoFonte], total_validos: int,
                     invalidos: list[Invalido], consolidados: list[Consolidado],
                     avisos_extras: list[str]) -> str:
    rs = [c.radar for c in consolidados]
    n = len(rs) or 1
    multi = sum(1 for c in consolidados if len(c.fontes) > 1)
    l = [f"# Relatório da base de radares", "",
         f"- **Versão:** `{versao}`", f"- **Gerado em:** {gerado_em}",
         f"- **Total final:** {len(rs)} radares", "",
         "## Fontes", "", "| Fonte | Registros | Origem |", "|---|---|---|"]
    for i in infos:
        l.append(f"| {i.nome} | {i.coletados} | {'cache (coleta falhou)' if i.do_cache else 'coleta de hoje'} |")
    l += ["", "## Consolidação", "",
          f"- Registros válidos: {total_validos}",
          f"- Registros inválidos descartados: {len(invalidos)}",
          f"- Duplicatas absorvidas: {total_validos - len(rs)}",
          f"- Radares confirmados por mais de uma fonte: {multi}",
          f"- Com direção: {sum(r.direcao_graus is not None for r in rs)} "
          f"({100 * sum(r.direcao_graus is not None for r in rs) / n:.0f}%)",
          f"- Com limite de velocidade: {sum(r.limite_kmh is not None for r in rs)} "
          f"({100 * sum(r.limite_kmh is not None for r in rs) / n:.0f}%)",
          "", "| Tipo | Quantidade |", "|---|---|"]
    for tipo, q in collections.Counter(r.tipo.value for r in rs).most_common():
        l.append(f"| {tipo} | {q} |")
    l += ["", "| Fonte principal | Quantidade |", "|---|---|"]
    for fonte, q in collections.Counter(r.fonte.value for r in rs).most_common():
        l.append(f"| {fonte} | {q} |")
    l += ["", "## Avisos", ""]
    avisos = [f"[{i.nome}] {a}" for i in infos for a in i.avisos] + avisos_extras
    if not avisos:
        l.append("Nenhum aviso.")
    for a in avisos[:MAX_AVISOS_RELATORIO]:
        l.append(f"- {a}")
    if len(avisos) > MAX_AVISOS_RELATORIO:
        l.append(f"- … e mais {len(avisos) - MAX_AVISOS_RELATORIO} avisos")
    l += ["", "## Registros inválidos", "", relatorio_invalidos(invalidos)]
    return "\n".join(l)


def gerar(saida: Path = SAIDA, coletores: dict | None = None,
          agora: datetime | None = None) -> dict:
    """Roda o pipeline inteiro. Devolve um resumo; nunca levanta por causa de uma fonte."""
    saida = Path(saida)
    agora = (agora or datetime.now(BRASILIA)).astimezone(BRASILIA)
    radares, infos = coletar_todas(coletores or COLETORES, saida / "cache")
    validos, invalidos = separar_validos(radares)
    consolidados = deduplicar(validos)
    avisos_extras = _ids_unicos(consolidados)
    dicts = sorted((c.para_dict() for c in consolidados), key=lambda d: d["id"])
    h = hash_conteudo(dicts)

    arq_json = saida / "radares.json"
    if arq_json.exists():
        try:
            anterior = json.loads(arq_json.read_text(encoding="utf-8")).get("versao", "")
        except ValueError:
            anterior = ""
        if anterior.endswith(f"-{h}"):
            log.info("Conteúdo igual ao da versão %s: nada regravado", anterior)
            return {"versao": anterior, "total": len(dicts), "mudou": False}

    versao = f"{agora:%Y%m%d}-{h}"
    gerado_em = agora.isoformat(timespec="seconds")
    saida.mkdir(parents=True, exist_ok=True)
    cab = {"versao": versao, "gerado_em": gerado_em, "total": len(dicts)}
    # newline="\n": sem isso o Windows grava CRLF e o diff com o CI (Linux) vira o arquivo todo
    arq_json.write_text(_json_um_por_linha(cab, dicts), encoding="utf-8", newline="\n")
    (saida / "relatorio.md").write_text(
        montar_relatorio(versao, gerado_em, infos, len(validos), invalidos, consolidados,
                         avisos_extras), encoding="utf-8", newline="\n")
    log.info("Versão %s gravada: %d radares", versao, len(dicts))
    return {"versao": versao, "total": len(dicts), "mudou": True}


def _main() -> None:
    p = argparse.ArgumentParser(description="Consolida todas as fontes em output/radares.json")
    p.add_argument("--saida", default=str(SAIDA))
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    print(gerar(Path(args.saida)))


if __name__ == "__main__":
    _main()
