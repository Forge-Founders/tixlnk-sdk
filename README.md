# Tixlnk AdTech SDK (KMP + Flutter)

SDK de atribuição para mobile com:
- `core-kmp/`: lógica de negócio e transporte HTTP em Kotlin Multiplatform.
- `flutter-plugin/`: API pública Dart e bridges nativos (Android/iOS).

O SDK não coleta sinais do aparelho: o match usa só um token opaco de uso único.

## Versões Obrigatórias (Strict Mode)

- Flutter: `3.47.9` (Dart `3.13.7`)
- Kotlin: `2.4.21` (K2 habilitado)
- AGP: `9.5.0`
- Gradle Wrapper: `9.8.2`
- Ktor Client: `3.6.1` (CIO/Darwin)
- Kotlinx Serialization: `1.12.0`
- Multiplatform Settings: `1.3.0`

## Estrutura

```txt
core-kmp/
flutter-plugin/
settings.gradle.kts
README.md
```

## Installation

### 1) Flutter (`pubspec.yaml` do app cliente)

```yaml
dependencies:
  txlnk_adtech_sdk:
    git:
      url: https://github.com/Forge-Founders/tixlnk-sdk.git
      path: flutter-plugin
      ref: v0.2.0 # fixe sempre uma tag publicada
```

Se estiver em desenvolvimento local:

```yaml
dependencies:
  txlnk_adtech_sdk:
    path: /absolute/path/to/link-platform-sdk/flutter-plugin
```

### 2) Android Gradle (projeto host)

Garanta repositórios e toolchain atualizados:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip
```

## Android Setup

Adicione permissões e intent-filter de deep link no `AndroidManifest.xml` do app:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application>
        <activity
            android:name=".MainActivity"
            android:exported="true">

            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />

                <data
                    android:scheme="https"
                    android:host="go.txlnk.com"
                    android:pathPrefix="/dl" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

## iOS Setup

Adicione Associated Domains no `ios/Runner/Info.plist`:

```xml
<key>com.apple.developer.associated-domains</key>
<array>
  <string>applinks:go.txlnk.com</string>
</array>
```

## Usage

Crie a app key na tela **Apps** do dashboard Tixlnk. A chave `pk_live_...` é exibida uma única vez e armazenada como hash no backend; para rotacionar, revogue o app e cadastre-o novamente. O SDK envia a chave nos endpoints de matching e eventos.

### Inicialização

```dart
import 'package:txlnk_adtech_sdk/adtech_sdk.dart';

Future<void> setupAdTech() async {
  await AdTechSdk.instance.init(
    apiKey: 'pk_live_123', // one-time key generated in Tixlnk > Apps
    endpoint: 'https://collector.txlnk.com',
    tenantId: 'tenant-uuid',
    appId: 'app-uuid',
  );
  // init já executa matchDevice uma única vez (first-open).
}
```

### Track de evento

```dart
await AdTechSdk.instance.track(
  'purchase',
  {
    'product_id': 'ticket_gold',
  },
  eventId: 'order-123', // opcional, vai como event_id
  valueCents: 5990, // opcional, vai como value_cents, junto com currency
  currency: 'BRL', // ISO 4217 em maiúsculas
);
```

`eventId` tem no máximo 128 caracteres, sem espaço nas pontas. `valueCents`
e `currency` vão juntos ou não vão. A chamada antiga, `track(event, metadata)`,
continua igual.

> O relatório de receita da campanha conta só `purchase` e `refund` enviados
> pelo servidor do cliente com a chave de servidor (`sk_live_...`). Um
> `purchase` enviado pelo `track` do app fica gravado com `source=client` e
> não entra na receita.

### Callback de Deferred Deep Link

```dart
final sub = AdTechSdk.instance.onDeferredDeepLink.listen((uri) {
  if (uri == null) return;

  // Exemplo: myapp://offer?id=42
  // Faça o roteamento do app aqui.
  debugPrint('Deferred deep link recebido: $uri');
});

// no dispose:
// await sub.cancel();
```

### Resolver um link recebido pelo app antes do match

O match usa um token opaco de uso único, não fingerprint. Em um Universal Link
com o app instalado, o iOS entrega a URL ao app, mas não os headers da resposta
HTTP. Passe a URL recebida ao SDK antes do first-open; o SDK faz GET sem seguir
o redirect e extrai `X-Tixlnk-Match-Token` da resposta. O token não é colocado
na URL:

```dart
// Chamado quando o app é aberto via Universal Link (iOS) / App Link (Android):
Future<void> onIncomingLink(Uri incomingLink) async {
  await AdTechSdk.instance.setDeferredLinkUrl(incomingLink);
}
```

- **iOS/Flutter**: o exemplo usa `app_links` para capturar initial e warm links e
  passa a URL ao `setDeferredLinkUrl`. No app cliente, use `app_links` ou o
  router do app; resolva a URL antes de `init` no cold start, ou chame
  `setDeferredLinkUrl` seguido de `matchDevice` para um warm link.
- **Android (deferred)**: no `init` e no primeiro `matchDevice`, o plugin lê
  `com.android.installreferrer` e usa o parâmetro `tixlnk_token` quando a Play
  o devolve. Não é preciso colar o token. A leitura espera no máximo 2,5 s e,
  sem referrer, o match segue. Um token já definido por link instalado não é
  substituído.
- **Android App Links**: o app instalado pode passar a URL recebida usando
  `setDeferredLinkUrl`.

- **iOS (App Clip)**: se o Info.plist do app e do App Clip tiver `TixlnkAppGroup`
  com o mesmo App Group, o plugin grava o token em `tixlnk.pending_match_token`
  e o app completo lê essa chave uma vez no primeiro match. Sem a chave, nada
  é gravado. Fechar o card e instalar pela ficha não leva o destino.
- **iOS (mesma conta)**: o servidor do cliente chama `POST /v1/device/continuations`
  com `click_id` (recebido no fallback como `tixlnk_click_id`) ou `link_id`,
  guarda o `token` no próprio usuário e só chama `setDeferredLinkToken` depois
  do mesmo login. A autenticação é a chave de servidor `sk_live_...` do app;
  depois que o app tem uma, a continuação exige essa chave. Ela fica só no
  servidor, nunca no app. O token não entra na URL pública. A TixLnk não recebe
  o id do usuário.

> Se nenhum token for capturado, o match é enviado sem token e o primeiro acesso
> é orgânico. No iOS, a ficha da App Store não devolve o clique: depois de uma
> instalação pela loja, os caminhos são o App Clip aberto ou a mesma conta. Sem
> um deles, o app abre na tela inicial. Não prometa deferred silencioso no iOS.

### Callback de Attribution Data

```dart
final attributionSub = AdTechSdk.instance.onAttributionData.listen((data) {
  debugPrint('Atribuição resolvida: $data');
});
```

## Example Apps

### Flutter plugin example

Arquivo: `flutter-plugin/example/lib/main.dart`

Cobre os dois fluxos de deep link:
- Non-deferred (app instalado): simulação e roteamento de link local
- Deferred (pós-instalação): callback via `onDeferredDeepLink`

Teste local por plataforma:
- Android Emulator: `http://10.0.2.2:58080`
- iOS Simulator: `http://127.0.0.1:58080`

### KMP Android example app

Módulo: `core-kmp-example-android/`

Demonstra consumo direto do `AdTechClient` com:
- `init(apiKey, endpoint)`
- `matchDevice()` para fluxo deferred
- `track()` para eventos de conversão
- credenciais configuráveis na tela; use a app key de **Apps**. `matchDevice` lê o Play Install Referrer e não exige colar o token. Um token digitado continua opcional e tem prioridade.
- endpoint local padrão `http://10.0.2.2:58080` (Android Emulator apontando para um backend na máquina host).

Build:
- `mise x -- gradle :core-kmp-example-android:assembleDebug`

## Outras Tecnologias (React Native e Expo)

Atualmente este repositório entrega SDK oficial para:
- Kotlin Multiplatform (`core-kmp`)
- Flutter (`flutter-plugin`)

Para React Native, o caminho recomendado é criar um wrapper JS sobre um módulo nativo (Android/iOS) que exponha o mesmo contrato do SDK:
- `init(apiKey, endpoint)`
- `matchDevice()`
- `track(event, metadata)`
- stream/evento de deferred deep link
- stream/evento de attribution data

### React Native (sem Expo / Bare workflow)

1. Android:
- integrar `:core-kmp` (ou um módulo Android que encapsule `AdTechClient`);
- expor métodos via `NativeModule` (TurboModule se preferir).

2. iOS:
- expor bridge Swift/ObjC com os mesmos métodos;
- emitir eventos via `RCTEventEmitter` para deferred deep link e attribution data.

3. JavaScript:
- criar API única `AdTechSdk` chamando o módulo nativo;
- enriquecer `track` com `click_id` persistido, como no plugin Flutter.

Exemplo de interface JS:

```ts
export interface AdTechSdkContract {
  init(apiKey: string, endpoint: string): Promise<void>;
  matchDevice(): Promise<void>;
  track(event: string, metadata: Record<string, unknown>): Promise<void>;
  onDeferredDeepLink(cb: (url: string | null) => void): () => void;
  onAttributionData(cb: (data: Record<string, unknown>) => void): () => void;
}
```

### React Native com Expo

#### Expo Go

`Expo Go` não suporta código nativo customizado arbitrário. Para usar este SDK você precisa:
- migrar para **Expo Dev Client** (ou Bare);
- criar um módulo Expo (`expo-modules`) que faça bridge para Android/iOS.

#### Expo Dev Client / Prebuild

Fluxo recomendado:
1. criar módulo nativo com `expo-modules`;
2. no Android, chamar `AdTechClient` do `core-kmp`;
3. no iOS, implementar bridge equivalente em Swift;
4. expor eventos para JS (`EventEmitter`) para deferred deep link e attribution data.

### Deep Link (deferred e non-deferred) em RN/Expo

- Non-deferred:
  - usar `Linking.getInitialURL()` + listener `Linking.addEventListener("url", ...)`;
  - rotear URL imediatamente quando app já está instalado.
- Deferred:
  - disparar `matchDevice()` no first-open (ou no `init`);
  - ouvir evento nativo de deferred deep link e aplicar roteamento quando chegar.

### Checklist mínimo de paridade

- mesmo endpoint/base URL por plataforma;
- mesma persistência de `click_id`, `attribution_data`, `is_organic`, `match_done`;
- `matchDevice()` idempotente (executa rede uma vez por instalação);
- `track()` enriquecido com `click_id` quando existir;
- callbacks de deferred deep link e attribution data documentados no wrapper JS.

## API KMP

O `AdTechClient` no módulo `core-kmp` expõe:
- `init(apiKey, endpoint)`
- `setDeferredLinkToken(token)`
- `setDeferredLinkUrl(url)` — mesmo contrato do plugin Flutter: GET no Universal Link ou App Link sem seguir o redirect, lê `X-Tixlnk-Match-Token` da resposta 3xx e chama `setDeferredLinkToken`. Só `http`/`https`. Sem 3xx ou sem header, lança `IllegalStateException`.
- `matchDevice()` -> POST `/v1/device/match`
- `track(event)` -> POST `/v1/s2s/events`. `AdTechEvent` aceita `eventId`, `valueCents` e `currency` opcionais (`event_id`, `value_cents`, `currency`), com as mesmas regras do Flutter.
- Android: `PlayInstallReferrer.readToken(context)` antes do primeiro `matchDevice`. Um token já definido não é substituído. Não peça para colar o token.
- iOS (App Clip): `client.setAppGroupMatchTokenStore(IosAppGroup.matchTokenStore())` no app e no clip. Lê `TixlnkAppGroup` do Info.plist e usa `NSUserDefaults(suiteName:)` na chave `tixlnk.pending_match_token`: o token definido é gravado ali, e o primeiro `matchDevice` sem token em memória lê e apaga o pendente, uma vez. Compila para iOS a partir do Linux; não foi rodado num simulador nem num iPhone.

Com KDoc em todos os métodos públicos para DX em IDE e geração de docs.
