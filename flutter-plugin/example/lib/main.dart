import 'dart:async';
import 'dart:io' show Platform;

import 'package:app_links/app_links.dart';
// package:material_ui replaces this import from Flutter 3.47 on, but the
// example also builds on the minimum supported Flutter (3.24), which lacks it.
// ignore: deprecated_member_use
import 'package:flutter/material.dart';
import 'package:txlnk_adtech_sdk/adtech_sdk.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ExampleApp());
}

class ExampleApp extends StatefulWidget {
  const ExampleApp({super.key});

  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  final TextEditingController _apiKeyInput = TextEditingController();
  final TextEditingController _endpointInput = TextEditingController();
  final TextEditingController _deferredTokenInput = TextEditingController();
  final TextEditingController _nonDeferredInput = TextEditingController(
    text: 'txlnk://events/detail?id=evt_42&source=push',
  );

  late final AppLinks _appLinks;
  late final Future<Uri?> _initialLink;
  StreamSubscription<Uri>? _incomingLinkSub;
  StreamSubscription<Uri?>? _deferredSub;
  StreamSubscription<Map<String, dynamic>>? _attributionSub;

  String _status = 'Idle';
  bool _sdkInitialized = false;
  Uri? _lastIncomingLink;
  Uri? _pendingIncomingLink;
  final List<String> _timeline = <String>[];

  String get _localEndpoint {
    if (Platform.isAndroid) return 'http://10.0.2.2:58080';
    return 'http://127.0.0.1:58080';
  }

  @override
  void initState() {
    super.initState();
    _endpointInput.text = _localEndpoint;
    _appLinks = AppLinks();
    _initialLink = _loadInitialLink();
    _incomingLinkSub = _appLinks.uriLinkStream.listen(
      _handleIncomingLink,
      onError: (Object error) =>
          _append('Incoming link listener failed: $error'),
    );

    _deferredSub = AdTechSdk.instance.onDeferredDeepLink.listen((uri) {
      if (!mounted) return;
      if (uri == null) {
        _append('Deferred deep link recebido: null');
        return;
      }
      _routeDeepLink(uri, deferred: true);
    });

    _attributionSub = AdTechSdk.instance.onAttributionData.listen((data) {
      if (!mounted) return;
      _append('Attribution data: $data');
    });
  }

  @override
  void dispose() {
    _incomingLinkSub?.cancel();
    _deferredSub?.cancel();
    _attributionSub?.cancel();
    _apiKeyInput.dispose();
    _endpointInput.dispose();
    _deferredTokenInput.dispose();
    _nonDeferredInput.dispose();
    super.dispose();
  }

  Future<Uri?> _loadInitialLink() async {
    try {
      return await _appLinks.getInitialLink();
    } catch (error) {
      _append('Initial link lookup failed: $error');
      return null;
    }
  }

  void _append(String message) {
    final String line = '${DateTime.now().toIso8601String()} | $message';
    setState(() {
      _status = message;
      _timeline.insert(0, line);
    });
  }

  void _routeDeepLink(Uri uri, {required bool deferred}) {
    final String kind = deferred ? 'DEFERRED' : 'NON-DEFERRED';
    final String route = uri.path.isEmpty ? '/' : uri.path;
    final String id = uri.queryParameters['id'] ?? '-';
    _append('$kind link -> route=$route, id=$id, raw=$uri');
  }

  bool _isHttpLink(Uri? uri) =>
      uri != null && (uri.scheme == 'http' || uri.scheme == 'https');

  void _handleIncomingLink(Uri uri) {
    if (!mounted || uri == _lastIncomingLink) return;
    _lastIncomingLink = uri;
    if (!_isHttpLink(uri)) {
      _routeDeepLink(uri, deferred: false);
      return;
    }

    _pendingIncomingLink = uri;
    _deferredTokenInput.text = uri.toString();
    _append('Universal/App Link received');
    if (_sdkInitialized) {
      _pendingIncomingLink = null;
      unawaited(_resolveAndMatchLink(uri));
    }
  }

  Future<void> _resolveAndMatchLink(Uri uri) async {
    try {
      await AdTechSdk.instance.setDeferredLinkUrl(uri);
      await AdTechSdk.instance.matchDevice();
      _append('Incoming link resolved and matched');
    } catch (error) {
      _append('Incoming link match failed: $error');
    }
  }

  Future<void> _initSdk() async {
    try {
      final Uri? initialLink = await _initialLink;
      final String input = _deferredTokenInput.text.trim();
      final Uri? enteredUri = Uri.tryParse(input);
      final Uri? linkUrl = _isHttpLink(enteredUri)
          ? enteredUri
          : (input.isEmpty && _isHttpLink(initialLink) ? initialLink : null);

      if (linkUrl != null) {
        _lastIncomingLink = linkUrl;
        _pendingIncomingLink = null;
        await AdTechSdk.instance.setDeferredLinkUrl(linkUrl);
      } else if (input.isNotEmpty) {
        await AdTechSdk.instance.setDeferredLinkToken(input);
      }
      await AdTechSdk.instance.init(
        apiKey: _apiKeyInput.text.trim(),
        endpoint: _endpointInput.text.trim(),
      );
      _sdkInitialized = true;
      final Uri? pendingLink = _pendingIncomingLink;
      if (pendingLink != null) {
        _pendingIncomingLink = null;
        unawaited(_resolveAndMatchLink(pendingLink));
      }
      _append(
        linkUrl == null && input.isEmpty
            ? 'SDK initialized on ${_endpointInput.text.trim()}. Android reads the Play install referrer before the first match; an empty result stays organic.'
            : 'SDK initialized on ${_endpointInput.text.trim()} with deferred link context',
      );
    } catch (e) {
      _append('Init failed: $e');
    }
  }

  Future<void> _triggerDeferredMatch() async {
    final String input = _deferredTokenInput.text.trim();
    final Uri? uri = Uri.tryParse(input);
    try {
      if (uri != null && _isHttpLink(uri)) {
        await AdTechSdk.instance.setDeferredLinkUrl(uri);
      } else if (input.isNotEmpty) {
        await AdTechSdk.instance.setDeferredLinkToken(input);
      }
      await AdTechSdk.instance.matchDevice();
      _append(
        input.isEmpty
            ? 'matchDevice requested from the Play referrer or an already stored token'
            : 'matchDevice requested (wait for deferred callback stream)',
      );
    } catch (e) {
      _append('matchDevice failed: $e');
    }
  }

  Future<void> _trackPurchase() async {
    try {
      await AdTechSdk.instance.track('purchase', {
        'value': '29.90',
        'currency': 'BRL',
        'sku': 'ticket_vip',
      });
      _append('Track purchase sent');
    } catch (e) {
      _append('Track failed: $e');
    }
  }

  void _simulateNonDeferred() {
    final String raw = _nonDeferredInput.text.trim();
    final Uri? parsed = Uri.tryParse(raw);
    if (parsed == null) {
      _append('Invalid non-deferred link: $raw');
      return;
    }
    _routeDeepLink(parsed, deferred: false);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Tixlnk AdTech SDK Example')),
        body: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text(
                  'This demo shows:\n'
                  '1) an installed app opening the short link\n'
                  '2) Android reading the Play install referrer before the first match.\n'
                  'Use an app key from TixLnk > Apps. Pasting a token is optional.',
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _apiKeyInput,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    labelText: 'App API key (pk_live_...)',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _endpointInput,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    labelText: 'API endpoint',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _deferredTokenInput,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    labelText: 'Optional link URL or token',
                    helperText:
                        'Leave empty unless you already have a URL or token. Android reads the Play referrer. iOS reads TixlnkAppGroup when that Info.plist key is set.',
                  ),
                ),
                const SizedBox(height: 8),
                ElevatedButton(
                  onPressed: _initSdk,
                  child: const Text('Init SDK'),
                ),
                const SizedBox(height: 8),
                ElevatedButton(
                  onPressed: _triggerDeferredMatch,
                  child: const Text('Trigger Deferred Match (matchDevice)'),
                ),
                const SizedBox(height: 8),
                ElevatedButton(
                  onPressed: _trackPurchase,
                  child: const Text('Track Purchase'),
                ),
                const Divider(height: 24),
                const Text('Non-deferred deep link simulation'),
                const SizedBox(height: 8),
                TextField(
                  controller: _nonDeferredInput,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    hintText: 'txlnk://events/detail?id=evt_42&source=push',
                  ),
                ),
                const SizedBox(height: 8),
                ElevatedButton(
                  onPressed: _simulateNonDeferred,
                  child: const Text('Handle Non-deferred Link'),
                ),
                const SizedBox(height: 16),
                Text('Status: $_status'),
                const SizedBox(height: 8),
                const Text('Timeline (newest first)'),
                const SizedBox(height: 8),
                SizedBox(
                  height: 240,
                  child: Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.black26),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: ListView.builder(
                      itemCount: _timeline.length,
                      itemBuilder: (context, index) => Text(_timeline[index]),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
