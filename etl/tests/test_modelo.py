import math

from modelo import (
    Fonte,
    Radar,
    TipoRadar,
    gerar_id,
    relatorio_invalidos,
    separar_validos,
    validar,
)

# Ponto qualquer no Eixo Monumental, Brasília
LAT, LON = -15.79425, -47.88250


def radar(**kw):
    base = dict(latitude=LAT, longitude=LON, tipo=TipoRadar.FIXO, fonte=Fonte.OSM)
    base.update(kw)
    return Radar(**base)


def test_id_estavel_entre_execucoes():
    assert radar().id == radar().id
    assert len(radar().id) == 16


def test_id_ignora_ruido_abaixo_da_quinta_casa():
    assert radar(latitude=LAT + 1e-7).id == radar().id


def test_id_muda_com_tipo_e_posicao():
    assert radar(tipo=TipoRadar.SEMAFORO).id != radar().id
    assert radar(latitude=LAT + 0.0001).id != radar().id


def test_id_distingue_sentidos_opostos_no_mesmo_ponto():
    ida = radar(direcao_graus=90.0)
    volta = radar(direcao_graus=270.0)
    assert ida.id != volta.id
    assert radar(direcao_graus=None).id not in (ida.id, volta.id)


def test_direcao_360_vira_0():
    r = radar(direcao_graus=360.0)
    assert r.direcao_graus == 0.0
    assert r.id == radar(direcao_graus=0.0).id


def test_confiabilidade_padrao_por_fonte():
    assert radar(fonte=Fonte.OSM).confiabilidade == 60
    assert radar(fonte=Fonte.ANTT).confiabilidade == 85
    assert radar(fonte=Fonte.LAI_DETRAN_DF).confiabilidade == 95
    assert radar(fonte=Fonte.MANUAL).confiabilidade == 90
    assert radar(fonte=Fonte.DNIT, confiabilidade=50).confiabilidade == 50


def test_aceita_strings_nos_enums():
    r = radar(tipo="LOMBADA", fonte="ANTT")
    assert r.tipo is TipoRadar.LOMBADA and r.fonte is Fonte.ANTT


def test_radar_valido():
    assert validar(radar(limite_kmh=60, direcao_graus=180.0)) == []
    assert validar(radar(limite_kmh=None, direcao_graus=None)) == []


def test_coordenadas_fora_do_brasil():
    assert validar(radar(latitude=40.4, longitude=-3.7))  # Madri
    assert validar(radar(latitude=LON, longitude=LAT))  # lat/lon trocados
    assert validar(radar(latitude=math.nan))


def test_limites_de_velocidade():
    assert validar(radar(limite_kmh=20)) == []
    assert validar(radar(limite_kmh=130)) == []
    assert validar(radar(limite_kmh=19))
    assert validar(radar(limite_kmh=131))


def test_direcao_fora_da_faixa():
    assert validar(radar(direcao_graus=-1.0))
    assert validar(radar(direcao_graus=361.0))


def test_separar_validos_e_relatorio():
    bons = [radar(), radar(limite_kmh=80)]
    ruins = [radar(limite_kmh=200), radar(latitude=0.0, longitude=0.0)]
    validos, invalidos = separar_validos(bons + ruins)
    assert validos == bons
    assert [i.radar for i in invalidos] == ruins
    md = relatorio_invalidos(invalidos)
    assert "limite fora da faixa" in md and "fora do Brasil" in md


def test_para_dict_serializavel():
    d = radar(limite_kmh=60).para_dict()
    assert list(d)[0] == "id"
    assert d["tipo"] == "FIXO" and d["fonte"] == "OSM"
