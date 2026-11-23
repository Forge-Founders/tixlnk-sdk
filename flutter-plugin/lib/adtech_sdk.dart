import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// API pública do SDK de Atribuição (AdTech) para apps Flutter.
///
/// Esta classe encapsula o bridge nativo via `MethodChannel` e expõe:
/// - inicialização do SDK ([init]);
/// - tracking de eventos S2S ([track]);
/// - stream opcional de deferred deep link ([onDeferredDeepLink]).
///
/// Exemplo básico:
/// ```dart
/// await AdTechSdk.instance.init(
///   apiKey: 'pk_live_123',
///   endpoint: 'http://10.0.2.2:8080',
/// );
///
/// await AdTechSdk.instance.track(
///   'purchase',
///   {'value': '19.90', 'currency': 'BRL'},
/// );
/// ```
class AdTechSdk {
  AdTechSdk._();

  /// Instância singleton recomendada para uso no app.
  static final AdTechSdk instance = AdTechSdk._();

  static const MethodChannel _methodChannel = MethodChannel(
    'com.txlnk.sdk/adtech',
  );
  static const EventChannel _deepLinkChannel = EventChannel(
    'com.txlnk.sdk/adtech/deeplink',
  );
  static const EventChannel _attributionChannel = EventChannel(
    'com.txlnk.sdk/adtech/attribution',
  );

  /// Inicializa o SDK nativo com `apiKey` e endpoint base da API de atribuição.
  ///
  /// [apiKey] deve ser uma chave válida provisionada pelo backend de AdTech.
  /// [endpoint] deve incluir protocolo, por exemplo: `https://collector.exemplo.com`.
  /// [tenantId] e [appId] são scoping opcional enviados no device match.
  ///
  /// Lança [PlatformException] quando a camada nativa retorna erro.
  Future<void> init({
    required String apiKey,
    required String endpoint,
    String? tenantId,
    String? appId,
  }) async {
    assert(apiKey.trim().isNotEmpty, 'apiKey must not be empty');
    assert(endpoint.trim().isNotEmpty, 'endpoint must not be empty');

    await _methodChannel.invokeMethod<void>('init', <String, dynamic>{
      'apiKey': apiKey,
      'endpoint': endpoint,
      'tenantId': tenantId,
      'appId': appId,
    });
  }

  /// Registra o token de match capturado do deferred link (Universal/App Link
  /// no iOS, Play Install Referrer no Android, ou a continuação da mesma conta)
  /// antes do first-open.
  ///
  /// No iOS, quando o Info.plist tem `TixlnkAppGroup`, o plugin também grava
  /// esse token no UserDefaults compartilhado com o App Clip e, no primeiro
  /// match sem token em memória, lê e apaga essa chave. Sem a chave no
  /// Info.plist, esse passo não faz nada. O token não vai para log.
  ///
  /// Com o app já aberto, chame [matchDevice] em seguida. Um token diferente
  /// do último já usado libera exatamente um novo match, mesmo depois de um
  /// match não orgânico, e o deep link e a atribuição dele chegam por
  /// [onDeferredDeepLink] e [onAttributionData]. O mesmo token nunca faz
  /// match duas vezes.
  Future<void> setDeferredLinkToken(String token) async {
    await _methodChannel.invokeMethod<void>(
      'setDeferredLinkToken',
      <String, dynamic>{'token': token},
    );
  }

  /// Resolves a Universal/App Link URL to its one-time match token.
  ///
  /// The native bridge performs a GET without following the redirect and reads
  /// `X-Tixlnk-Match-Token` from the redirect response. Call this before [init]
  /// for a cold start, or call it followed by [matchDevice] for a warm link.
  /// The token remains in the response header and is never copied into the URL.
  Future<void> setDeferredLinkUrl(Uri url) async {
    if (url.scheme != 'http' && url.scheme != 'https') {
      throw ArgumentError.value(url, 'url', 'must be an HTTP(S) link URL');
    }
    await _methodChannel.invokeMethod<void>(
      'setDeferredLinkUrl',
      <String, dynamic>{'url': url.toString()},
    );
  }

  /// Executa explicitamente o match de instalação no nativo.
  ///
  /// Em uso normal, `init` já chama esse fluxo uma vez (first-open). Depois
  /// disso, só faz uma nova chamada de rede quando há um token novo de
  /// [setDeferredLinkToken] ou [setDeferredLinkUrl]; sem ele, mantém o
  /// resultado salvo. Um token novo sem match (`404`) não apaga a atribuição
  /// anterior.
  Future<void> matchDevice() async {
    await _methodChannel.invokeMethod<void>('matchDevice');
  }

  /// Envia um evento para o endpoint S2S (`/v1/s2s/events`).
  ///
  /// [event] define o nome canônico do evento (ex.: `signup`, `purchase`).
  /// [metadata] contém pares chave/valor serializáveis para segmentação e análise.
  /// [eventId] é opcional e vai como `event_id`: id idempotente do evento (por
  /// exemplo, o id do pedido), até 128 caracteres, sem espaço nas pontas.
  /// [valueCents] e [currency] são opcionais e vão juntos como `value_cents` e
  /// `currency`: valor não negativo em centavos e moeda ISO 4217 em maiúsculas
  /// (ex.: `BRL`).
  ///
  /// Os relatórios de receita contam só `purchase`/`refund` enviados pelo
  /// servidor do cliente com a chave de servidor (`sk_live_`). Um `purchase`
  /// enviado pelo app fica registrado com `source=client`.
  ///
  /// Fluxo:
  /// 1. lê `click_id` salvo no nativo;
  /// 2. se existir, enriquece o payload;
  /// 3. envia evento para o bridge nativo efetivar POST S2S.
  ///
  /// Lança [ArgumentError] quando [eventId], [valueCents] ou [currency] são
  /// inválidos, e [PlatformException] para falhas no bridge nativo.
  Future<void> track(
    String event,
    Map<String, dynamic> metadata, {
    String? eventId,
    int? valueCents,
    String? currency,
  }) async {
    assert(event.trim().isNotEmpty, 'event must not be empty');
    validateTrackFields(
      eventId: eventId,
      valueCents: valueCents,
      currency: currency,
    );

    final String? clickId = await _methodChannel.invokeMethod<String>(
      'getClickId',
    );

    final Map<String, dynamic> payload = <String, dynamic>{
      'event': event,
      'metadata': metadata,
      if (clickId != null && clickId.isNotEmpty) 'click_id': clickId,
      if (eventId != null) 'event_id': eventId,
      if (valueCents != null) 'value_cents': valueCents,
      if (currency != null) 'currency': currency,
    };

    await _methodChannel.invokeMethod<void>('track', payload);
  }

  static final RegExp _currencyCode = RegExp(r'^[A-Z]{3}$');

  /// Valida os campos opcionais de [track] com as mesmas regras da API.
  @visibleForTesting
  static void validateTrackFields({
    String? eventId,
    int? valueCents,
    String? currency,
  }) {
    if (eventId != null) {
      if (eventId.isEmpty) {
        throw ArgumentError.value(eventId, 'eventId', 'must not be empty');
      }
      if (eventId.trim() != eventId) {
        throw ArgumentError.value(
          eventId,
          'eventId',
          'must not have surrounding whitespace',
        );
      }
      if (utf8.encode(eventId).length > 128) {
        throw ArgumentError.value(
          eventId,
          'eventId',
          'must have at most 128 characters',
        );
      }
    }
    if ((valueCents == null) != (currency == null)) {
      throw ArgumentError('valueCents and currency go together');
    }
    if (valueCents != null && valueCents < 0) {
      throw ArgumentError.value(
        valueCents,
        'valueCents',
        'must not be negative',
      );
    }
    if (currency != null && !_currencyCode.hasMatch(currency)) {
      throw ArgumentError.value(
        currency,
        'currency',
        'must be an uppercase ISO 4217 code such as BRL',
      );
    }
  }

  /// Stream de deferred deep link recebido após resolução de atribuição.
  ///
  /// Cada item é uma [Uri] parseada a partir do payload nativo. Quando o payload
  /// vier nulo ou vazio, o stream publica `null`.
  Stream<Uri?> get onDeferredDeepLink {
    return _deepLinkChannel
        .receiveBroadcastStream()
        .map(parseDeferredDeepLinkPayload)
        .handleError((Object error, StackTrace stackTrace) {
      debugPrint('AdTechSdk.onDeferredDeepLink error: $error');
    });
  }

  /// Callback reativo com dados de atribuição resolvidos pelo match de instalação.
  ///
  /// Equivale ao contrato `onAttributionData(data)` no wrapper Flutter.
  /// O payload recebido é o JSON de `attribution_data` persistido no nativo.
  Stream<Map<String, dynamic>> get onAttributionData {
    return _attributionChannel.receiveBroadcastStream().map(
          parseAttributionPayload,
        );
  }

  @visibleForTesting
  static Uri? parseDeferredDeepLinkPayload(dynamic payload) {
    if (payload == null) return null;
    final String value = payload.toString().trim();
    if (value.isEmpty) return null;
    return Uri.tryParse(value);
  }

  @visibleForTesting
  static Map<String, dynamic> parseAttributionPayload(dynamic payload) {
    if (payload is Map) {
      return payload.cast<String, dynamic>();
    }
    if (payload is String && payload.isNotEmpty) {
      try {
        final dynamic decoded = jsonDecode(payload);
        if (decoded is Map<String, dynamic>) {
          return decoded;
        }
      } catch (_) {
        return const <String, dynamic>{};
      }
    }
    return const <String, dynamic>{};
  }
}
