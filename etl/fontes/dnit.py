"""Fonte DNIT: equipamentos do PNCV (Plano Nacional de Controle de Velocidade).

Os dados vêm do mesmo endpoint público que alimenta a página
https://servicos.dnit.gov.br/multas/informacoes/equipamentos-fiscalizacao
— um GeoJSON (FeatureCollection de Points), sem autenticação nem captcha.

Propriedades de cada feature (conferidas em 30/10/2026):
  uf, id, codigo, tipo (1–4), tipoNome, municipio, km ("714,250"), rodovia ("364"),
  inmetro, serie, guidET.
Não há limite de velocidade, sentido fiscalizado nem situação (ligado/desligado).

Uso: python -m fontes.dnit [--bbox sul,oeste,norte,leste]
"""

from __future__ import annotations

import argparse
import logging
import time
from datetime import date
from typing import Callable

import requests

from modelo import Fonte, Radar, ResultadoFonte, TipoRadar

log = logging.getLogger("radarleo.dnit")

URL_EQUIPAMENTOS = "https://servicos.dnit.gov.br/services-sior/portal-multas/pncv/equipamentos"
USER_AGENT = "RadarLeo-ETL/0.1 (alertas de radar pessoais)"
TENTATIVAS = 3
ESPERA_BASE_S = 15
TIMEOUT_S = (15, 120)

# O PNCV teve radares desligados por falta de orçamento e o GeoJSON não diz quais
# estão ligados. Até haver confirmação, todos entram com 50 (o app trata < 60 como
# "possível radar").
CONFIABILIDADE = 50

# tipo do DNIT → nosso enum. "Redutor eletrônico" é a lombada eletrônica.
# Misto e Composto também fiscalizam velocidade; sem mais detalhes, ficam como FIXO.
TIPOS = {
    1: TipoRadar.LOMBADA,           # Redutor Eletrônico de Velocidade
    2: TipoRadar.FIXO,              # Controlador Eletrônico de Velocidade
    3: TipoRadar.FIXO,              # Controlador Eletrônico Misto
    4: TipoRadar.FIXO,              # Controlador Eletrônico Composto
}


def baixar_geojson(url: str = URL_EQUIPAMENTOS, get: Callable = requests.get) -> dict:
    """GET com retry. Repete em erro de rede, 5xx/429 e resposta que não é GeoJSON."""
    ultimo_erro = None
    for tentativa in range(1, TENTATIVAS + 1):
        try:
            r = get(url, timeout=TIMEOUT_S, headers={"User-Agent": USER_AGENT})
            if r.status_code == 200:
                dados = r.json()
                if dados.get("type") == "FeatureCollection":
                    return dados
                ultimo_erro = f"formato inesperado (type={dados.get('type')!r})"
            else:
                ultimo_erro = f"HTTP {r.status_code}"
        except (requests.RequestException, ValueError) as e:
            ultimo_erro = f"{type(e).__name__}: {e}"
        if tentativa < TENTATIVAS:
            espera = ESPERA_BASE_S * tentativa
            log.warning("DNIT falhou (tentativa %d/%d): %s — nova tentativa em %ds",
                        tentativa, TENTATIVAS, ultimo_erro, espera)
            time.sleep(espera)
    raise RuntimeError(f"DNIT indisponível após {TENTATIVAS} tentativas: {ultimo_erro}")


def ler_km(valor) -> float | None:
    """'714,250' → 714.25"""
    if valor in (None, ""):
        return None
    try:
        return float(str(valor).strip().replace(".", "").replace(",", "."))
    except ValueError:
        return None


def ler_rodovia(valor) -> str | None:
    """'70' / '070' → 'BR-070'"""
    v = str(valor or "").strip()
    return f"BR-{int(v):03d}" if v.isdigit() else (v or None)


def converter(geojson: dict, bbox: tuple[float, float, float, float] | None = None,
              hoje: str | None = None) -> tuple[list[Radar], list[str]]:
    hoje = hoje or date.today().isoformat()
    radares: list[Radar] = []
    avisos: list[str] = []
    for f in geojson.get("features", []):
        p = f.get("properties") or {}
        geom = f.get("geometry") or {}
        if geom.get("type") != "Point" or len(geom.get("coordinates") or []) < 2:
            avisos.append(f"equipamento {p.get('codigo')} sem coordenadas: ignorado")
            continue
        lon, lat = geom["coordinates"][:2]
        if bbox:
            sul, oeste, norte, leste = bbox
            if not (sul <= lat <= norte and oeste <= lon <= leste):
                continue
        tipo = TIPOS.get(p.get("tipo"))
        if tipo is None:
            avisos.append(f"equipamento {p.get('codigo')}: tipo desconhecido "
                          f"{p.get('tipo')!r} ({p.get('tipoNome')!r})")
            tipo = TipoRadar.DESCONHECIDO
        radares.append(Radar(
            latitude=float(lat), longitude=float(lon), tipo=tipo, fonte=Fonte.DNIT,
            limite_kmh=None, direcao_graus=None,
            rodovia=ler_rodovia(p.get("rodovia")), km=ler_km(p.get("km")),
            confiabilidade=CONFIABILIDADE, atualizado_em=hoje,
        ))
    return radares, avisos


def coletar(bbox: tuple[float, float, float, float] | None = None) -> ResultadoFonte:
    """Ponto de entrada do pipeline. bbox=None = Brasil inteiro. Nunca levanta exceção."""
    resultado = ResultadoFonte(Fonte.DNIT)
    try:
        radares, avisos = converter(baixar_geojson(), bbox)
        resultado.radares, resultado.avisos = radares, avisos
        log.info("DNIT: %d equipamentos", len(radares))
    except Exception as e:  # noqa: BLE001 — uma fonte não pode derrubar o pipeline
        msg = f"DNIT falhou: {e}"
        log.error(msg)
        resultado.avisos.append(msg)
        resultado.falhou = True
    return resultado


def _main() -> None:
    import collections
    import json

    p = argparse.ArgumentParser(description="Coleta equipamentos do PNCV/DNIT")
    p.add_argument("--bbox", help="sul,oeste,norte,leste (padrão: Brasil inteiro)")
    args = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    bbox = tuple(float(v) for v in args.bbox.split(",")) if args.bbox else None
    res = coletar(bbox)
    for a in res.avisos:
        print("AVISO:", a)
    print(f"Total: {len(res.radares)} equipamentos")
    print("Por tipo:", dict(collections.Counter(r.tipo.value for r in res.radares)))
    for r in res.radares[:5]:
        print(json.dumps(r.para_dict(), ensure_ascii=False))


if __name__ == "__main__":
    _main()
