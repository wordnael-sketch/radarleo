"""Testes do conversor DNIT com features no formato real do endpoint do PNCV."""

import requests

from fontes import dnit
from modelo import Fonte, TipoRadar


def feature(lon, lat, **props):
    base = {"uf": "DF", "id": 8598, "codigo": "DFB26010036", "tipo": 1,
            "tipoNome": "Redutor Eletrônico de Velocidade", "municipio": "BRASILIA",
            "km": "6,700", "rodovia": "070", "inmetro": "35222",
            "serie": "FLICD0126A01010", "guidET": "abc"}
    base.update(props)
    return {"type": "Feature", "geometry": {"type": "Point", "coordinates": [lon, lat]},
            "properties": base}


def colecao(*fs):
    return {"type": "FeatureCollection", "features": list(fs)}


def test_conversao_basica():
    (r,), avisos = dnit.converter(colecao(feature(-48.11146, -15.789087)), hoje="2026-10-30")
    assert avisos == []
    assert (r.latitude, r.longitude) == (-15.789087, -48.11146)  # GeoJSON é [lon, lat]
    assert r.tipo is TipoRadar.LOMBADA and r.fonte is Fonte.DNIT
    assert r.rodovia == "BR-070" and r.km == 6.7
    assert r.limite_kmh is None and r.direcao_graus is None
    assert r.confiabilidade == 50


def test_mapeamento_de_tipos():
    fs = [feature(-48.1, -15.7, tipo=t, codigo=str(t)) for t in (1, 2, 3, 4, 9)]
    radares, avisos = dnit.converter(colecao(*fs))
    assert [r.tipo for r in radares] == [TipoRadar.LOMBADA, TipoRadar.FIXO, TipoRadar.FIXO,
                                         TipoRadar.FIXO, TipoRadar.DESCONHECIDO]
    assert "tipo desconhecido" in avisos[0]


def test_km_e_rodovia():
    assert dnit.ler_km("714,250") == 714.25
    assert dnit.ler_km("1.014,5") == 1014.5
    assert dnit.ler_km("") is None
    assert dnit.ler_rodovia("70") == "BR-070"
    assert dnit.ler_rodovia("364") == "BR-364"


def test_filtro_bbox():
    fs = [feature(-48.11, -15.79), feature(-63.88, -8.77)]  # Brasília, Porto Velho
    radares, _ = dnit.converter(colecao(*fs), bbox=(-16.05, -48.29, -15.50, -47.31))
    assert len(radares) == 1


def test_sem_geometria_gera_aviso():
    f = feature(0, 0)
    f["geometry"] = None
    radares, avisos = dnit.converter(colecao(f))
    assert radares == [] and "sem coordenadas" in avisos[0]


class _Resp:
    def __init__(self, status, dados=None, erro=None):
        self.status_code, self._dados, self._erro = status, dados, erro

    def json(self):
        if self._erro:
            raise ValueError(self._erro)
        return self._dados


def test_retry(monkeypatch):
    monkeypatch.setattr(dnit.time, "sleep", lambda s: None)
    respostas = iter([_Resp(502), _Resp(200, erro="html"), _Resp(200, colecao())])
    assert dnit.baixar_geojson(get=lambda *a, **k: next(respostas)) == colecao()


def test_coletar_nao_quebra(monkeypatch):
    monkeypatch.setattr(dnit.time, "sleep", lambda s: None)

    def falha(*a, **k):
        raise requests.ConnectionError("sem rede")

    monkeypatch.setattr(dnit.baixar_geojson, "__defaults__", (dnit.URL_EQUIPAMENTOS, falha))
    res = dnit.coletar()
    assert res.radares == [] and "DNIT falhou" in res.avisos[0]
