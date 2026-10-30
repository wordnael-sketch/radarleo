#!/usr/bin/env python3
"""Roteiro de teste do RadarLeo em casa (Passo 16a).

Uma rota em L no Plano Piloto (3 km para leste, depois 2,2 km para o norte), a mais de
1,7 km de qualquer radar real, com 5 radares de teste:

  r1  mesmo sentido, abaixo do limite ........ 500/300/100 m, sem "Reduza"
  r2  pista contrária ........................ NENHUM alerta
  r3  semáforo, os dois sentidos ............. 500/300/100 m
  r4  lombada 40 km/h, carro a 60 km/h ....... 500/300/100 m + "Reduza! Limite 40"
  r5  possível radar (confiabilidade 50) ..... um único "Possível radar a 300 metros"

Comandos (só biblioteca padrão do Python; precisa do adb e do APK de debug instalado):

  python rota_teste.py gerar      gera rota_teste.gpx e radares_teste.csv
  python rota_teste.py emulador   tudo automático no emulador: põe os radares de teste,
                                  liga o RadarLeo, dirige a rota a 1 Hz, confere os alertas
  python rota_teste.py preparar   só põe os radares de teste (celular por USB, com local fictício)
  python rota_teste.py avaliar    confere os alertas no logcat depois de rodar o GPX
  python rota_teste.py limpar     tira os radares de teste do app
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent
SAIDA = AQUI / "saida"
PACOTE = "br.radarleo"
RAIO_TERRA_M = 6_371_008.8

INICIO = (-15.7800, -47.8740)
# (rumo, metros, km/h): leste a 54 km/h, depois norte a 60 km/h
TRECHOS = [(90.0, 3000, 54), (0.0, 2200, 60)]

AVISOS_PADRAO = (500, 300, 100)

# lado: metros à direita (+) ou à esquerda (-) de quem percorre a rota
RADARES = [
    dict(id="teste-r1", trecho=0, pos=800, lado=6, direcao=90.0, tipo="FIXO", limite=60, conf=90,
         descricao="mesmo sentido, abaixo do limite",
         avisos=[f"Radar a {d} metros, limite 60" for d in AVISOS_PADRAO], excesso=False),
    dict(id="teste-r2", trecho=0, pos=1600, lado=-12, direcao=270.0, tipo="FIXO", limite=60, conf=90,
         descricao="pista contrária (NÃO pode alertar)", avisos=[], excesso=False),
    dict(id="teste-r3", trecho=0, pos=2400, lado=6, direcao=None, tipo="SEMAFORO", limite=None, conf=90,
         descricao="semáforo, fiscaliza os dois sentidos",
         avisos=[f"Radar de semáforo a {d} metros" for d in AVISOS_PADRAO], excesso=False),
    dict(id="teste-r4", trecho=1, pos=800, lado=6, direcao=0.0, tipo="LOMBADA", limite=40, conf=90,
         descricao="lombada 40 km/h com o carro a 60 km/h",
         avisos=[f"Lombada eletrônica a {d} metros, limite 40" for d in AVISOS_PADRAO], excesso=True),
    dict(id="teste-r5", trecho=1, pos=1600, lado=6, direcao=10.0, tipo="FIXO", limite=None, conf=50,
         descricao="possível radar (confiabilidade 50)",
         avisos=["Possível radar a 300 metros"], excesso=False),
]


# ---------------------------------------------------------------- geometria

def destino(lat: float, lon: float, rumo: float, d: float) -> tuple[float, float]:
    f1, l1, b, dr = math.radians(lat), math.radians(lon), math.radians(rumo), d / RAIO_TERRA_M
    f2 = math.asin(math.sin(f1) * math.cos(dr) + math.cos(f1) * math.sin(dr) * math.cos(b))
    l2 = l1 + math.atan2(math.sin(b) * math.sin(dr) * math.cos(f1), math.cos(dr) - math.sin(f1) * math.sin(f2))
    return math.degrees(f2), math.degrees(l2)


def distancia(a: float, b: float, c: float, d: float) -> float:
    f1, f2 = math.radians(a), math.radians(c)
    h = math.sin((f2 - f1) / 2) ** 2 + math.cos(f1) * math.cos(f2) * math.sin(math.radians(d - b) / 2) ** 2
    return 2 * RAIO_TERRA_M * math.asin(math.sqrt(h))


def inicios_dos_trechos() -> list[tuple[float, float]]:
    pontos, p = [], INICIO
    for rumo, metros, _ in TRECHOS:
        pontos.append(p)
        p = destino(*p, rumo, metros)
    return pontos


def rota() -> list[dict]:
    """Uma posição por segundo, na velocidade de cada trecho."""
    pts = []
    for (rumo, metros, kmh), origem in zip(TRECHOS, inicios_dos_trechos()):
        passo = kmh / 3.6
        n = int(metros / passo)
        for k in range(n):
            lat, lon = destino(*origem, rumo, k * passo)
            pts.append(dict(lat=lat, lon=lon, rumo=rumo, kmh=kmh))
    return pts


def posicao_radar(r: dict) -> tuple[float, float]:
    rumo = TRECHOS[r["trecho"]][0]
    origem = inicios_dos_trechos()[r["trecho"]]
    na_via = destino(*origem, rumo, r["pos"])
    return destino(*na_via, (rumo + 90) % 360, r["lado"]) if r["lado"] >= 0 else \
        destino(*na_via, (rumo - 90) % 360, -r["lado"])


# ---------------------------------------------------------------- arquivos

def gerar() -> None:
    pts = rota()
    t0 = datetime(2026, 11, 1, 9, 0, tzinfo=timezone.utc)
    linhas = ['<?xml version="1.0" encoding="UTF-8"?>',
              '<gpx version="1.1" creator="RadarLeo rota_teste.py" xmlns="http://www.topografix.com/GPX/1/1">',
              '  <metadata><name>RadarLeo - rota de teste (5 radares)</name></metadata>']
    for r in RADARES:
        lat, lon = posicao_radar(r)
        linhas.append(f'  <wpt lat="{lat:.7f}" lon="{lon:.7f}"><name>{r["id"]}</name><desc>{r["descricao"]}</desc></wpt>')
    linhas.append('  <trk><name>rota de teste</name><trkseg>')
    for i, p in enumerate(pts):
        hora = (t0 + timedelta(seconds=i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        # GPX 1.1 puro (sem <speed>/<course>): os apps de local fictício tiram a velocidade dos horários
        linhas.append(f'    <trkpt lat="{p["lat"]:.7f}" lon="{p["lon"]:.7f}"><ele>1100</ele><time>{hora}</time></trkpt>')
    linhas += ['  </trkseg></trk>', '</gpx>', '']
    (AQUI / "rota_teste.gpx").write_text("\n".join(linhas), encoding="utf-8", newline="\n")

    csv = ["id,latitude,longitude,tipo,limite_kmh,direcao_graus,confiabilidade,esperado"]
    for r in RADARES:
        lat, lon = posicao_radar(r)
        esperado = " / ".join(r["avisos"]) or "nenhum alerta"
        if r["excesso"]:
            esperado += " + Reduza"
        csv.append(f'{r["id"]},{lat:.7f},{lon:.7f},{r["tipo"]},{r["limite"] or ""},'
                   f'{"" if r["direcao"] is None else r["direcao"]},{r["conf"]},"{esperado}"')
    (AQUI / "radares_teste.csv").write_text("\n".join(csv) + "\n", encoding="utf-8", newline="\n")

    folga = folga_dos_radares_reais(pts)
    print(f"rota_teste.gpx: {len(pts)} pontos ({len(pts) // 60} min {len(pts) % 60} s a 1 Hz)")
    print("radares_teste.csv: 5 radares")
    if folga is not None:
        print(f"radar real mais próximo da rota: {folga:.0f} m")


def folga_dos_radares_reais(pts: list[dict]) -> float | None:
    """Garante que só os radares de teste podem alertar: nenhum real a menos de 800 m."""
    base = RAIZ / "output" / "radares.json"
    if not base.exists():
        return None
    reais = [(r["latitude"], r["longitude"]) for r in json.loads(base.read_text(encoding="utf-8"))["radares"]]
    perto = [(a, b) for a, b in reais if abs(a - INICIO[0]) < 0.2 and abs(b - INICIO[1]) < 0.2]
    folga = min((distancia(p["lat"], p["lon"], a, b) for p in pts[::5] for a, b in perto), default=math.inf)
    if folga < 800:
        sys.exit(f"ERRO: há radar real a {folga:.0f} m da rota; escolha outro INICIO")
    return folga


# ---------------------------------------------------------------- adb

def adb_caminho() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    exe = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(exe) if exe.exists() else "adb"


ADB = adb_caminho()


def adb(*args: str, saida: Path | None = None) -> str:
    if saida:
        with open(saida, "wb") as f:
            subprocess.run([ADB, *args], stdout=f, check=False)
        return ""
    r = subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout


def sql(comando: str) -> str:
    return adb("shell", f'run-as {PACOTE} sqlite3 databases/radarleo.db "{comando}"')


def preparar() -> None:
    if PACOTE not in adb("shell", "pm", "list", "packages", PACOTE):
        sys.exit("ERRO: RadarLeo (debug) não está instalado no aparelho conectado")
    valores = []
    for r in RADARES:
        lat, lon = posicao_radar(r)
        valores.append(
            f"('{r['id']}',{lat:.7f},{lon:.7f},'{r['tipo']}','USUARIO',"
            f"{r['limite'] if r['limite'] else 'NULL'},{'NULL' if r['direcao'] is None else r['direcao']},"
            f"NULL,NULL,{r['conf']},'2026-11-01','TESTE')"
        )
    # fonte USUARIO: a sincronização da base não apaga os radares de teste no meio do roteiro
    sql("DELETE FROM radares WHERE id LIKE 'teste-%'; INSERT INTO radares "
        "(id,latitude,longitude,tipo,fonte,limite_kmh,direcao_graus,rodovia,km,confiabilidade,atualizado_em,fontes) "
        "VALUES " + ",".join(valores) + ";")
    n = sql("SELECT COUNT(*) FROM radares WHERE id LIKE 'teste-%';").strip()
    print(f"radares de teste no app: {n}")


def limpar() -> None:
    sql("DELETE FROM radares WHERE id LIKE 'teste-%';")
    print("radares de teste removidos")


def tocar_texto(texto: str) -> bool:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    m = re.search(rf'text="{re.escape(texto)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


# ---------------------------------------------------------------- avaliação

RE_ALERTA = re.compile(r"Alerta: (.+) \(radar ([\w-]+), (\d+) m\)")


def avaliar() -> bool:
    log = adb("logcat", "-d", "-s", "RadarLeo:I")
    por_radar: dict[str, list[str]] = {}
    for frase, rid, _ in RE_ALERTA.findall(log):
        por_radar.setdefault(rid, []).append(frase)

    tudo_ok = True
    print(f"\n{'radar':9} {'cenário':42} resultado")
    for r in RADARES:
        frases = por_radar.pop(r["id"], [])
        avisos = [f for f in frases if not f.startswith(("Reduza", "Atenção"))]
        excessos = len(frases) - len(avisos)
        ok = avisos == r["avisos"] and (excessos > 0) == r["excesso"]
        tudo_ok &= ok
        detalhe = " | ".join(avisos) or "nenhum alerta"
        if excessos:
            detalhe += f" | Reduza x{excessos}"
        print(f"{r['id']:9} {r['descricao']:42} {'PASSOU' if ok else 'FALHOU'}: {detalhe}")
        if not ok:
            print(f"{'':52} esperado: {' | '.join(r['avisos']) or 'nenhum alerta'}"
                  f"{' | Reduza' if r['excesso'] else ''}")
    if por_radar:
        tudo_ok = False
        print(f"alertas de radares fora do teste: {por_radar}")
    print(f"\nRESULTADO: {'PASSOU' if tudo_ok else 'FALHOU'}")
    return tudo_ok


def emulador(manter: bool) -> bool:
    if "emulator-" not in adb("devices"):
        sys.exit("ERRO: nenhum emulador conectado (adb devices)")
    SAIDA.mkdir(exist_ok=True)
    preparar()
    pts = rota()
    adb("emu", "geo", "fix", f"{pts[0]['lon']:.7f}", f"{pts[0]['lat']:.7f}", "1100")
    adb("shell", "am", "start", "-W", "-n", f"{PACOTE}/.ui.MainActivity")
    time.sleep(3)
    if not tocar_texto("Iniciar RadarLeo") and not tocar_texto("Parar"):
        sys.exit("ERRO: não achei o botão Iniciar (as permissões já foram concedidas?)")
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")  # RadarLeo em segundo plano: a bolha aparece
    adb("logcat", "-c")

    r4 = next(r for r in RADARES if r["id"] == "teste-r4")
    foto_em = sum(1 for p in pts if p["rumo"] == 90.0) + int((r4["pos"] - 250) / (TRECHOS[1][2] / 3.6))
    print(f"dirigindo {len(pts)} pontos a 1 Hz ({len(pts) // 60} min {len(pts) % 60} s)…")
    inicio = time.monotonic()
    for i, p in enumerate(pts):
        adb("emu", "geo", "fix", f"{p['lon']:.7f}", f"{p['lat']:.7f}", "1100")
        if i == foto_em:
            adb("exec-out", "screencap", "-p", saida=SAIDA / "bolha_r4.png")
        if i % 60 == 0:
            print(f"  {i // 60} min")
        time.sleep(max(0.0, inicio + i + 1 - time.monotonic()))  # 1 Hz sem acumular atraso
    time.sleep(3)
    adb("logcat", "-d", "-s", "RadarLeo:I", saida=SAIDA / "logcat.txt")
    ok = avaliar()
    if not manter:
        limpar()
    print(f"log e foto da bolha em {SAIDA}")
    return ok


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("comando", choices=["gerar", "emulador", "preparar", "avaliar", "limpar"])
    p.add_argument("--manter", action="store_true", help="no emulador, não remove os radares de teste no fim")
    a = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    if a.comando == "gerar":
        gerar()
    elif a.comando == "emulador":
        sys.exit(0 if emulador(a.manter) else 1)
    elif a.comando == "preparar":
        preparar()
    elif a.comando == "avaliar":
        sys.exit(0 if avaliar() else 1)
    else:
        limpar()


if __name__ == "__main__":
    main()
