import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:txlnk_adtech_sdk/adtech_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel channel = MethodChannel('com.txlnk.sdk/adtech');

  group('AdTechSdk.track', () {
    test('includes click_id when native storage has it', () async {
      MethodCall? trackCall;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            if (methodCall.method == 'getClickId') {
              return 'clk_123';
            }
            if (methodCall.method == 'track') {
              trackCall = methodCall;
              return null;
            }
            return null;
          });

      await AdTechSdk.instance.track('purchase', {'value': '99.90'});

      expect(trackCall, isNotNull);
      final args = trackCall!.arguments as Map<dynamic, dynamic>;
      expect(args['event'], 'purchase');
      expect(args['click_id'], 'clk_123');
      expect((args['metadata'] as Map<dynamic, dynamic>)['value'], '99.90');
    });

    test('omits click_id when native storage is organic', () async {
      MethodCall? trackCall;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            if (methodCall.method == 'getClickId') {
              return null;
            }
            if (methodCall.method == 'track') {
              trackCall = methodCall;
              return null;
            }
            return null;
          });

      await AdTechSdk.instance.track('signup', {'method': 'email'});

      expect(trackCall, isNotNull);
      final args = trackCall!.arguments as Map<dynamic, dynamic>;
      expect(args['event'], 'signup');
      expect(args.containsKey('click_id'), isFalse);
      expect((args['metadata'] as Map<dynamic, dynamic>)['method'], 'email');
    });
  });

  group('AdTechSdk.track optional fields', () {
    Future<Map<dynamic, dynamic>> captureTrack(
      Future<void> Function() send,
    ) async {
      MethodCall? trackCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            if (methodCall.method == 'track') {
              trackCall = methodCall;
            }
            return null;
          });
      await send();
      expect(trackCall, isNotNull);
      return trackCall!.arguments as Map<dynamic, dynamic>;
    }

    test('sends event_id, value_cents and currency when provided', () async {
      final args = await captureTrack(
        () => AdTechSdk.instance.track(
          'purchase',
          {'sku': 'ticket_vip'},
          eventId: 'order-42',
          valueCents: 12990,
          currency: 'BRL',
        ),
      );

      expect(args['event_id'], 'order-42');
      expect(args['value_cents'], 12990);
      expect(args['currency'], 'BRL');
    });

    test('omits the optional keys when they are not provided', () async {
      final args = await captureTrack(
        () => AdTechSdk.instance.track('signup', {'method': 'email'}),
      );

      expect(args.containsKey('event_id'), isFalse);
      expect(args.containsKey('value_cents'), isFalse);
      expect(args.containsKey('currency'), isFalse);
    });

    test('rejects invalid values before calling native', () async {
      var nativeCalls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            nativeCalls += 1;
            return null;
          });

      final List<Future<void> Function()> invalid = <Future<void> Function()>[
        () => AdTechSdk.instance.track('purchase', {}, eventId: ''),
        () => AdTechSdk.instance.track('purchase', {}, eventId: ' order-1'),
        () => AdTechSdk.instance.track('purchase', {}, eventId: 'a' * 129),
        () => AdTechSdk.instance.track('purchase', {}, valueCents: 100),
        () => AdTechSdk.instance.track('purchase', {}, currency: 'BRL'),
        () => AdTechSdk.instance.track(
          'purchase',
          {},
          valueCents: -1,
          currency: 'BRL',
        ),
        () => AdTechSdk.instance.track(
          'purchase',
          {},
          valueCents: 100,
          currency: 'brl',
        ),
      ];
      for (final send in invalid) {
        await expectLater(send(), throwsArgumentError);
      }
      expect(nativeCalls, 0);
    });
  });

  group('AdTechSdk.setDeferredLinkToken', () {
    test('invokes native method with the token', () async {
      MethodCall? call;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            if (methodCall.method == 'setDeferredLinkToken') {
              call = methodCall;
            }
            return null;
          });

      await AdTechSdk.instance.setDeferredLinkToken('tok-abc-123');

      expect(call, isNotNull);
      expect(call!.method, 'setDeferredLinkToken');
      final args = call!.arguments as Map<dynamic, dynamic>;
      expect(args['token'], 'tok-abc-123');
    });
  });

  group('AdTechSdk.setDeferredLinkUrl', () {
    test('forwards the HTTP(S) URL to the native resolver', () async {
      MethodCall? call;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
            if (methodCall.method == 'setDeferredLinkUrl') {
              call = methodCall;
            }
            return null;
          });

      final url = Uri.parse('https://go.example.test/summer-sale');
      await AdTechSdk.instance.setDeferredLinkUrl(url);

      expect(call, isNotNull);
      expect(call!.method, 'setDeferredLinkUrl');
      expect((call!.arguments as Map<dynamic, dynamic>)['url'], url.toString());
    });

    test('rejects non-HTTP(S) URLs', () async {
      await expectLater(
        AdTechSdk.instance.setDeferredLinkUrl(Uri.parse('txlnk://offer')),
        throwsArgumentError,
      );
    });
  });

  group('AdTechSdk payload parsing', () {
    test('parseDeferredDeepLinkPayload handles empty and invalid values', () {
      expect(AdTechSdk.parseDeferredDeepLinkPayload(null), isNull);
      expect(AdTechSdk.parseDeferredDeepLinkPayload('   '), isNull);

      final uri = AdTechSdk.parseDeferredDeepLinkPayload(
        'txlnk://events/detail?id=42',
      );
      expect(uri, isNotNull);
      expect(uri!.scheme, 'txlnk');
      expect(uri.queryParameters['id'], '42');
    });

    test('parseAttributionPayload handles map json and malformed json', () {
      expect(
        AdTechSdk.parseAttributionPayload({'campaign': 'summer'})['campaign'],
        'summer',
      );

      final parsed = AdTechSdk.parseAttributionPayload(
        '{"campaign":"spring","source":"ads"}',
      );
      expect(parsed['campaign'], 'spring');
      expect(parsed['source'], 'ads');

      expect(AdTechSdk.parseAttributionPayload('{bad json}'), isEmpty);
      expect(AdTechSdk.parseAttributionPayload(123), isEmpty);
    });
  });
}
