"""Fonte manual: lê todos os CSVs de data/manual/ (respostas de LAI, cadastros à mão).

Cabeçalho obrigatório (ordem livre, colunas extras são ignoradas):
  latitude,longitude,tipo,limite_kmh,direcao_graus,rodovia,km,fonte,observacao

- separador "," ou ";" (o Excel em pt-BR salva com ";"); decimal com "." ou ",";
- codificação UTF-8 (com ou sem BOM) ou Latin-1;
- fonte: MANUAL, LAI_DETRAN_DF, LAI_DER_DF (ou outra do enum); vazio = MANUAL;
- tipo: FIXO, SEMAFORO, LOMBADA, VELOCIDADE_MEDIA (aceita "semáforo", "velocidade média"...);
- direcao_graus vazio = fiscaliza os dois sentidos;
- observacao com "desligado"/"inativo"/"desativado" rebaixa a confiabilidade para 50.

Arquivos MODELO*.csv são exemplos e não são lidos.

Uso: python -m fontes.manual [--pasta ../data/manual]
"""

from __future__ import annotations

import argparse
import csv
import io
import logging
import re
import unicodedata
from datetime import date
from pathlib import Path

from modelo import Fonte, Radar, ResultadoFonte, TipoRadar

log = logging.getLogger("radarleo.manual")

PASTA_MANUAL = Path(__file__).resolve().parents[2] / "data" / "manual"
COLUNAS = ["latitude", "longitude", "tipo", "limite_kmh", "direcao_graus",
           "rodovia", "km", "fonte", "observacao"]
CONFIABILIDADE_DESLIGADO = 50
RE_DESLIGADO = re.compile(r"desligad|inativ|desativad|removid", re.IGNORECASE)

# Grafias aceitas na coluna tipo (já sem acento, maiúsculas, espaços → "_").
APELIDOS_TIPO = {
    "SEMAFORO": TipoRadar.SEMAFORO, "AVANCO_DE_SINAL": TipoRadar.SEMAFORO,
    "LOMBADA": TipoRadar.LOMBADA, "LOMBADA_ELETRONICA": TipoRadar.LOMBADA,
    "REDUTOR": TipoRadar.LOMBADA, "BARREIRA": TipoRadar.LOMBADA,
    "FIXO": TipoRadar.FIXO, "RADAR": TipoRadar.FIXO, "RADAR_FIXO": TipoRadar.FIXO,
    "PARDAL": TipoRadar.FIXO, "CONTROLADOR": TipoRadar.FIXO,
    "VELOCIDADE_MEDIA": TipoRadar.VELOCIDADE_MEDIA,
}


def _normalizar(texto: str) -> str:
    sem_acento = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()
    return re.sub(r"[\s\-]+", "_", sem_acento.strip().upper())


def ler_numero(valor: str | None) -> float | None:
    """'-15,7801' → -15.7801; vazio → None. Levanta ValueError se não for número."""
    v = (valor or "").strip()
    if not v:
        return None
    return float(v.replace(",", "."))


def ler_tipo(valor: str | None) -> TipoRadar | None:
    """None quando o texto não corresponde a nenhum tipo conhecido."""
    v = _normalizar(valor or "")
    if not v:
        return TipoRadar.DESCONHECIDO
    if v in TipoRadar.__members__:
        return TipoRadar[v]
    return APELIDOS_TIPO.get(v)


def ler_fonte(valor: str | None) -> Fonte | None:
    v = _normalizar(valor or "")
    if not v:
        return Fonte.MANUAL
    return Fonte.__members__.get(v)


def _decodificar(bruto: bytes) -> str:
    try:
        return bruto.decode("utf-8-sig")
    except UnicodeDecodeError:
        return bruto.decode("latin-1")


def ler_texto_csv(texto: str, nome: str, hoje: str | None = None
                  ) -> tuple[list[Radar], list[str]]:
    """Converte o conteúdo de um CSV. Linha com problema vira aviso e é pulada."""
    hoje = hoje or date.today().isoformat()
    radares: list[Radar] = []
    avisos: list[str] = []

    primeira = texto.splitlines()[0] if texto.strip() else ""
    separador = ";" if primeira.count(";") > primeira.count(",") else ","
    leitor = csv.DictReader(io.StringIO(texto), delimiter=separador)
    cabecalho = [(c or "").strip().lower() for c in (leitor.fieldnames or [])]
    faltando = [c for c in COLUNAS if c not in cabecalho]
    if faltando:
        return [], [f"{nome}: cabeçalho sem as colunas {', '.join(faltando)}: arquivo ignorado"]
    leitor.fieldnames = cabecalho

    for n, linha in enumerate(leitor, start=2):  # linha 1 é o cabeçalho
        onde = f"{nome}:{n}"
        if not any((v or "").strip() for v in linha.values() if isinstance(v, str)):
            continue  # linha em branco
        try:
            lat = ler_numero(linha["latitude"])
            lon = ler_numero(linha["longitude"])
            limite = ler_numero(linha["limite_kmh"])
            direcao = ler_numero(linha["direcao_graus"])
            km = ler_numero(linha["km"])
        except ValueError as e:
            avisos.append(f"{onde}: número inválido ({e}): linha ignorada")
            continue
        if lat is None or lon is None:
            avisos.append(f"{onde}: sem latitude/longitude: linha ignorada")
            continue

        tipo = ler_tipo(linha["tipo"])
        if tipo is None:
            avisos.append(f"{onde}: tipo desconhecido {linha['tipo']!r}: gravado como DESCONHECIDO")
            tipo = TipoRadar.DESCONHECIDO
        fonte = ler_fonte(linha["fonte"])
        if fonte is None:
            avisos.append(f"{onde}: fonte desconhecida {linha['fonte']!r}: linha ignorada")
            continue

        observacao = (linha["observacao"] or "").strip()
        confiabilidade = None  # padrão da fonte
        if RE_DESLIGADO.search(observacao):
            confiabilidade = CONFIABILIDADE_DESLIGADO

        radares.append(Radar(
            latitude=lat, longitude=lon, tipo=tipo, fonte=fonte,
            limite_kmh=None if limite is None else round(limite),
            direcao_graus=direcao,
            rodovia=(linha["rodovia"] or "").strip() or None, km=km,
            confiabilidade=confiabilidade, atualizado_em=hoje,
        ))
    return radares, avisos


def coletar(pasta: Path | str = PASTA_MANUAL) -> ResultadoFonte:
    """Ponto de entrada do pipeline. Nunca levanta exceção.

    O ResultadoFonte sai com fonte=MANUAL, mas cada radar guarda a fonte da sua
    própria linha (MANUAL, LAI_DETRAN_DF, LAI_DER_DF...).
    """
    resultado = ResultadoFonte(Fonte.MANUAL)
    pasta = Path(pasta)
    try:
        arquivos = sorted(p for p in pasta.glob("*.csv")
                          if not p.name.upper().startswith("MODELO"))
    except OSError as e:
        resultado.avisos.append(f"Manual falhou ao listar {pasta}: {e}")
        resultado.falhou = True
        return resultado

    for arq in arquivos:
        try:
            radares, avisos = ler_texto_csv(_decodificar(arq.read_bytes()), arq.name)
        except Exception as e:  # noqa: BLE001 — um arquivo ruim não derruba os outros
            radares, avisos = [], [f"{arq.name}: falha na leitura ({e}): arquivo ignorado"]
        resultado.radares.extend(radares)
        resultado.avisos.extend(avisos)
        log.info("Manual: %s → %d radares", arq.name, len(radares))
    for a in resultado.avisos:
        log.warning(a)
    return resultado


def _main() -> None:
    import collections
    import json

    p = argparse.ArgumentParser(description="Lê os CSVs manuais e de LAI")
    p.add_argument("--pasta", default=str(PASTA_MANUAL))
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    res = coletar(args.pasta)
    print(f"Total: {len(res.radares)} radares")
    print("Por fonte:", dict(collections.Counter(r.fonte.value for r in res.radares)))
    for r in res.radares[:5]:
        print(json.dumps(r.para_dict(), ensure_ascii=False))


if __name__ == "__main__":
    _main()
