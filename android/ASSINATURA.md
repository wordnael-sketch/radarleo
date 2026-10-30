# Build e assinatura do RadarLeo

## APK de debug (para testar)

```bash
cd android
./gradlew assembleDebug
```

Sai em `android/app/build/outputs/apk/debug/app-debug.apk`, assinado com a chave de debug do Android SDK.
Instala em qualquer celular com "Instalar apps desconhecidos" liberado, mas não serve para distribuir.

## APK de release assinado

### 1. Criar a sua chave (uma vez só)

```bash
keytool -genkeypair -v -keystore C:/Users/Wordn/chaves/radarleo.jks -alias radarleo -keyalg RSA -keysize 4096 -validity 10000
```

O `keytool` vem com o JDK (`C:\Program Files\Eclipse Adoptium\jdk-17...\bin`). Ele pergunta as senhas e o seu nome.

- **Guarde a chave fora do projeto** (o exemplo usa `C:/Users/Wordn/chaves/`). O `.gitignore` já barra `*.jks` e `*.keystore`, mas fora da pasta é mais seguro ainda.
- **Faça backup da chave e anote as senhas num gerenciador de senhas.** Sem a mesma chave, o Android não aceita atualizar o app: seria preciso desinstalar, e os radares que você reportou iriam junto (exporte o CSV antes).

### 2. Dizer ao Gradle onde está a chave, sem senha no git

O `app/build.gradle.kts` lê quatro valores, primeiro das **variáveis de ambiente** e, se não achar, do **`android/local.properties`** (que nunca vai para o git):

| Nome | O que é |
|---|---|
| `RADARLEO_KEYSTORE` | caminho do `.jks` |
| `RADARLEO_KEYSTORE_SENHA` | senha do arquivo |
| `RADARLEO_CHAVE_ALIAS` | alias usado no `keytool` (`radarleo`) |
| `RADARLEO_CHAVE_SENHA` | senha da chave |

**Opção A: `android/local.properties`** (mais simples no seu PC). Acrescente:

```properties
RADARLEO_KEYSTORE=C\:/Users/Wordn/chaves/radarleo.jks
RADARLEO_KEYSTORE_SENHA=sua-senha
RADARLEO_CHAVE_ALIAS=radarleo
RADARLEO_CHAVE_SENHA=sua-senha
```

**Opção B: variáveis de ambiente** (nada gravado em arquivo; bom para CI):

```powershell
$env:RADARLEO_KEYSTORE = "C:/Users/Wordn/chaves/radarleo.jks"
$env:RADARLEO_KEYSTORE_SENHA = "sua-senha"
$env:RADARLEO_CHAVE_ALIAS = "radarleo"
$env:RADARLEO_CHAVE_SENHA = "sua-senha"
```

### 3. Gerar

```bash
cd android
./gradlew assembleRelease
```

- Com a chave configurada: `android/app/build/outputs/apk/release/app-release.apk`, já assinado.
- Sem a chave: o build não quebra, sai `app-release-unsigned.apk` (que o Android não instala).

Para conferir a assinatura:

```bash
"C:/Users/Wordn/AppData/Local/Android/Sdk/build-tools/34.0.0/apksigner.bat" verify --print-certs android/app/build/outputs/apk/release/app-release.apk
```

### 4. Antes de cada nova versão

Aumente `versionCode` (e `versionName`) em `app/build.gradle.kts`. O Android só instala por cima se o `versionCode` for maior.

## Diferenças entre debug e release

| | debug | release |
|---|---|---|
| Assinatura | chave de debug do SDK | a sua chave |
| HTTP sem TLS para `10.0.2.2` (testes no emulador) | liberado | bloqueado, só HTTPS |
| Base embutida | `output/radares.json` do momento do build | idem |

Se um dia o app for para a Play Store, o formato pedido é o AAB: `./gradlew bundleRelease`, com a mesma configuração de chave.
