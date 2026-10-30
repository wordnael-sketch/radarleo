# Testes do RadarLeo em casa (Passo 16a)

Rota em L no Plano Piloto (3 km para leste a 54 km/h, depois 2,2 km para o norte a 60 km/h),
a mais de 1,7 km de qualquer radar real. Só os 5 radares de teste podem alertar.

| Radar | Cenário | Esperado |
|---|---|---|
| r1 | fixo 60 km/h, mesmo sentido, carro a 54 | "Radar a 500/300/100 metros, limite 60", **sem** "Reduza" |
| r2 | fixo, **pista contrária** (fiscaliza 270°, carro a 90°) | **nenhum alerta** |
| r3 | semáforo sem direção (os dois sentidos) | "Radar de semáforo a 500/300/100 metros" |
| r4 | lombada 40 km/h, carro a 60 | "Lombada eletrônica a 500/300/100 metros, limite 40" + "Reduza! Limite 40" com bipe |
| r5 | possível radar (confiabilidade 50) | um único "Possível radar a 300 metros", com metade do volume |

Arquivos:
- [`rota_teste.py`](rota_teste.py) gera a rota, põe e tira os radares de teste do app e confere os alertas.
- [`rota_teste.gpx`](rota_teste.gpx) é a rota, com uma posição por segundo e os 5 radares como waypoints.
- [`radares_teste.csv`](radares_teste.csv) traz as coordenadas e o resultado esperado de cada radar.

O script usa só a biblioteca padrão do Python e o `adb` do Android SDK. Os radares de teste entram
no banco do app com ids `teste-r1` a `teste-r5`, por isso **precisa do APK de debug**: o `run-as` não funciona no release.

## A) Automático no emulador (recomendado)

Com o emulador ligado, o APK de debug instalado e as permissões concedidas:

```bash
python testes/rota_teste.py emulador
```

O script:
1. põe os 5 radares de teste;
2. liga o RadarLeo e o manda para segundo plano (assim a bolha aparece);
3. dirige a rota a 1 Hz pelo `adb emu geo fix`;
4. lê o logcat;
5. mostra PASSOU ou FALHOU por radar;
6. tira os radares de teste.

O log e uma foto da bolha chegando no r4 ficam em `testes/saida/`. Leva uns 6 minutos.

## B) Emulador, pela tela (para ver e ouvir)

1. `python testes/rota_teste.py preparar`
2. Abra o RadarLeo, toque em **Iniciar** e volte para a tela inicial.
3. No emulador: **⋯ (Extended controls) → Location → Routes → Load GPX/KML** e escolha `testes/rota_teste.gpx`.
4. Deixe a velocidade de reprodução em **1x** e aperte **Play**.
5. No fim: `python testes/rota_teste.py avaliar` e depois `python testes/rota_teste.py limpar`.

## C) Celular de verdade, com local fictício

1. Instale o `app-debug.apk` e passe pelas 4 permissões.
2. Ative as **Opções do desenvolvedor**: Configurações → Sobre o telefone → toque 7 vezes em "Número da versão".
3. Instale um app de local fictício que **reproduza arquivos GPX** (o Lockito, por exemplo). Confira na descrição do app que ele importa GPX.
4. Em Opções do desenvolvedor → **Selecionar app de local fictício**, escolha esse app.
5. Ligue a **Depuração USB**, conecte o celular no PC e rode `python testes/rota_teste.py preparar`.
6. Copie `rota_teste.gpx` para o celular e importe no app de local fictício. Se ele pedir velocidade, use 55 km/h.
7. Abra o RadarLeo e toque em **Iniciar**. Abra o Waze ou o Maps, para ver a bolha por cima, e comece a reprodução da rota.
8. No fim, com o celular ainda no USB: `python testes/rota_teste.py avaliar` e `python testes/rota_teste.py limpar`.

Aqui dá para **ouvir** a voz em pt-BR, o bipe e a música abaixando, coisa que o emulador não permite.

> Se o `avaliar` mostrar alertas de "radares fora do teste", a rota passou perto de um radar
> real (o local fictício pode ter deslocado o trajeto). Confira no Mapa do app.
