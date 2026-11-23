import Flutter
import UIKit

public class TxlnkAdtechSdkPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  private var deepLinkSink: FlutterEventSink?
  private var attributionSink: FlutterEventSink?
  private var apiKey: String?
  private var endpoint: String?
  private var tenantId: String?
  private var appId: String?
  private var deferredToken: String?
  private let defaults = UserDefaults.standard

  private static let clickIdKey = "adtech.click_id"
  private static let deepLinkKey = "adtech.deep_link"
  private static let attributionDataKey = "adtech.attribution_data"
  private static let isOrganicKey = "adtech.is_organic"
  private static let matchDoneKey = "adtech.match_done"
  private static let consumedTokenKey = "adtech.consumed_match_token"
  private static let appGroupInfoKey = "TixlnkAppGroup"
  private static let pendingMatchTokenKey = "tixlnk.pending_match_token"

  public static func register(with registrar: FlutterPluginRegistrar) {
    let methodChannel = FlutterMethodChannel(name: "com.txlnk.sdk/adtech", binaryMessenger: registrar.messenger())
    let instance = TxlnkAdtechSdkPlugin()
    registrar.addMethodCallDelegate(instance, channel: methodChannel)

    let deepLinkChannel = FlutterEventChannel(name: "com.txlnk.sdk/adtech/deeplink", binaryMessenger: registrar.messenger())
    deepLinkChannel.setStreamHandler(instance)

    let attributionChannel = FlutterEventChannel(name: "com.txlnk.sdk/adtech/attribution", binaryMessenger: registrar.messenger())
    attributionChannel.setStreamHandler(AttributionStreamHandler(plugin: instance))
  }

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    deepLinkSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    deepLinkSink = nil
    return nil
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "init":
      guard let args = call.arguments as? [String: Any],
            let apiKey = args["apiKey"] as? String,
            let endpoint = args["endpoint"] as? String,
            !apiKey.isEmpty,
            !endpoint.isEmpty else {
        result(FlutterError(code: "invalid_args", message: "apiKey and endpoint are required", details: nil))
        return
      }
      self.apiKey = apiKey
      self.endpoint = endpoint
      self.tenantId = args["tenantId"] as? String
      self.appId = args["appId"] as? String
      matchDeviceIfNeeded(result: result)

    case "setDeferredLinkToken":
      if let token = (call.arguments as? [String: Any])?["token"] as? String,
         !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        storeDeferredToken(token)
      }
      result(nil)

    case "setDeferredLinkUrl":
      guard let rawUrl = (call.arguments as? [String: Any])?["url"] as? String,
            let url = URL(string: rawUrl),
            ["http", "https"].contains(url.scheme?.lowercased() ?? "") else {
        result(FlutterError(code: "invalid_link_url", message: "An HTTP(S) Universal/App Link URL is required", details: nil))
        return
      }
      DeferredMatchTokenResolver().resolve(url: url) { [weak self] resolution in
        guard let self else {
          result(FlutterError(code: "deallocated", message: "Plugin deallocated", details: nil))
          return
        }
        switch resolution {
        case .success(let token):
          self.storeDeferredToken(token)
          result(nil)
        case .failure(let error):
          result(FlutterError(code: "link_token_resolve_failed", message: error.localizedDescription, details: nil))
        }
      }

    case "matchDevice":
      matchDeviceIfNeeded(result: result)

    case "getClickId":
      let clickId = defaults.string(forKey: Self.clickIdKey)
      result(clickId)

    case "track":
      guard apiKey != nil, endpoint != nil else {
        result(FlutterError(code: "not_initialized", message: "Call init() first", details: nil))
        return
      }
      guard let args = call.arguments as? [String: Any],
            let event = args["event"] as? String,
            !event.isEmpty else {
        result(FlutterError(code: "invalid_args", message: "event is required", details: nil))
        return
      }

      let metadata = args["metadata"] as? [String: Any] ?? [:]
      let clickId = (args["click_id"] as? String) ?? defaults.string(forKey: Self.clickIdKey)
      var payload: [String: Any] = [
        "name": event,
        "properties": metadata,
        "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
      ]
      if let clickId, !clickId.isEmpty {
        payload["click_id"] = clickId
      }
      if let eventId = args["event_id"] as? String {
        payload["event_id"] = eventId
      }
      if let valueCents = args["value_cents"] as? NSNumber {
        payload["value_cents"] = valueCents.int64Value
      }
      if let currency = args["currency"] as? String {
        payload["currency"] = currency
      }

      sendTrack(payload: payload, result: result)

    case "simulateDeferredDeepLink":
      if let args = call.arguments as? [String: Any], let deepLink = args["deepLink"] as? String {
        deepLinkSink?(deepLink)
      }
      result(nil)

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func matchDeviceIfNeeded(result: @escaping FlutterResult) {
    guard let endpoint, let apiKey else {
      result(FlutterError(code: "not_initialized", message: "Call init() first", details: nil))
      return
    }

    // Same rules as MatchState on Android: the first open matches once; then
    // only a token different from the last one used allows one new match.
    let previousMatchDone = defaults.bool(forKey: Self.matchDoneKey)
    if previousMatchDone && !hasUnconsumedToken() {
      if let cached = defaults.string(forKey: Self.attributionDataKey), !cached.isEmpty {
        attributionSink?(cached)
      }
      result(nil)
      return
    }

    guard let url = URL(string: "\(endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v1/device/match") else {
      result(FlutterError(code: "invalid_endpoint", message: "Invalid endpoint URL", details: nil))
      return
    }

    if !previousMatchDone {
      consumeAppGroupTokenIfNeeded()
    }
    let sentToken = deferredToken.flatMap { $0.isEmpty ? nil : $0 }

    // Only the opaque click token and ids: no device signals are collected
    // (no fingerprinting; the match is token based).
    var payload: [String: Any] = [:]
    if let sentToken {
      payload["token"] = sentToken
    }
    if let tenantId, !tenantId.isEmpty {
      payload["tenant_id"] = tenantId
    }
    if let appId, !appId.isEmpty {
      payload["app_id"] = appId
    }

    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 15
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")

    do {
      request.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [])
    } catch {
      result(FlutterError(code: "serialization_error", message: "Failed to serialize match payload", details: error.localizedDescription))
      return
    }

    URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
      DispatchQueue.main.async {
        guard let self else {
          result(FlutterError(code: "deallocated", message: "Plugin deallocated", details: nil))
          return
        }

        if let error {
          result(FlutterError(code: "match_failed", message: error.localizedDescription, details: nil))
          return
        }

        guard let httpResponse = response as? HTTPURLResponse else {
          result(FlutterError(code: "match_failed", message: "Invalid response", details: nil))
          return
        }

        switch httpResponse.statusCode {
        case 200:
          guard let data,
                let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                let clickId = json["click_id"] as? String,
                !clickId.isEmpty else {
            result(FlutterError(code: "match_failed", message: "Invalid 200 payload", details: nil))
            return
          }

          let deepLink = json["deep_link"] as? String ?? ""
          let attributionRaw = json["data"]
          var attributionDataString: String?
          if let attributionRaw,
             JSONSerialization.isValidJSONObject(attributionRaw),
             let encoded = try? JSONSerialization.data(withJSONObject: attributionRaw, options: []),
             let text = String(data: encoded, encoding: .utf8) {
            attributionDataString = text
          }

          self.defaults.set(true, forKey: Self.matchDoneKey)
          self.defaults.set(false, forKey: Self.isOrganicKey)
          self.defaults.set(clickId, forKey: Self.clickIdKey)
          self.defaults.set(deepLink, forKey: Self.deepLinkKey)
          self.defaults.set(attributionDataString, forKey: Self.attributionDataKey)
          self.rememberConsumedToken(sentToken)
          if !deepLink.isEmpty {
            self.deepLinkSink?(deepLink)
          }
          if let attributionDataString, !attributionDataString.isEmpty {
            self.attributionSink?(attributionDataString)
          }
          result(nil)

        case 404:
          self.rememberConsumedToken(sentToken)
          if previousMatchDone {
            // A new token that did not match keeps the earlier result.
            result(nil)
            return
          }
          self.defaults.set(true, forKey: Self.matchDoneKey)
          self.defaults.set(true, forKey: Self.isOrganicKey)
          self.defaults.removeObject(forKey: Self.clickIdKey)
          self.defaults.removeObject(forKey: Self.deepLinkKey)
          self.defaults.removeObject(forKey: Self.attributionDataKey)
          result(nil)

        default:
          result(FlutterError(code: "match_failed", message: "HTTP \(httpResponse.statusCode)", details: nil))
        }
      }
    }.resume()
  }

  private func sendTrack(payload: [String: Any], result: @escaping FlutterResult) {
    guard let endpoint, let apiKey else {
      result(FlutterError(code: "not_initialized", message: "Call init() first", details: nil))
      return
    }

    guard let url = URL(string: "\(endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/v1/s2s/events") else {
      result(FlutterError(code: "invalid_endpoint", message: "Invalid endpoint URL", details: nil))
      return
    }

    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 15
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")

    do {
      request.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [])
    } catch {
      result(FlutterError(code: "serialization_error", message: "Failed to serialize payload", details: error.localizedDescription))
      return
    }

    URLSession.shared.dataTask(with: request) { _, response, error in
      DispatchQueue.main.async {
        if let error {
          result(FlutterError(code: "track_failed", message: error.localizedDescription, details: nil))
          return
        }

        guard let httpResponse = response as? HTTPURLResponse else {
          result(FlutterError(code: "track_failed", message: "Invalid response", details: nil))
          return
        }

        guard (200...299).contains(httpResponse.statusCode) else {
          result(FlutterError(code: "track_failed", message: "HTTP \(httpResponse.statusCode)", details: nil))
          return
        }

        result(nil)
      }
    }.resume()
  }

  private func storeDeferredToken(_ token: String) {
    let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
      return
    }
    deferredToken = trimmed
    if let shared = appGroupDefaults() {
      shared.set(trimmed, forKey: Self.pendingMatchTokenKey)
    }
  }

  private func hasUnconsumedToken() -> Bool {
    guard let deferredToken, !deferredToken.isEmpty else {
      return false
    }
    return deferredToken != defaults.string(forKey: Self.consumedTokenKey)
  }

  private func rememberConsumedToken(_ token: String?) {
    guard let token else {
      return
    }
    defaults.set(token, forKey: Self.consumedTokenKey)
  }

  /// Reads the App Group named by Info.plist key TixlnkAppGroup.
  /// A missing or blank key is a no-op. The token itself is never logged.
  private func appGroupDefaults() -> UserDefaults? {
    guard let group = Bundle.main.object(forInfoDictionaryKey: Self.appGroupInfoKey) as? String else {
      return nil
    }
    let trimmed = group.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
      return nil
    }
    return UserDefaults(suiteName: trimmed)
  }

  /// A token already captured from an installed link wins.
  /// Otherwise the shared App Clip value is read once and removed locally.
  private func consumeAppGroupTokenIfNeeded() {
    if let deferredToken {
      let current = deferredToken.trimmingCharacters(in: .whitespacesAndNewlines)
      if !current.isEmpty {
        return
      }
    }
    guard let shared = appGroupDefaults() else {
      return
    }
    let stored = shared.string(forKey: Self.pendingMatchTokenKey)?
      .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    shared.removeObject(forKey: Self.pendingMatchTokenKey)
    if stored.isEmpty {
      return
    }
    deferredToken = stored
  }

  private final class AttributionStreamHandler: NSObject, FlutterStreamHandler {
    private weak var plugin: TxlnkAdtechSdkPlugin?

    init(plugin: TxlnkAdtechSdkPlugin) {
      self.plugin = plugin
      super.init()
    }

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
      plugin?.attributionSink = events
      if let cached = plugin?.defaults.string(forKey: TxlnkAdtechSdkPlugin.attributionDataKey), !cached.isEmpty {
        plugin?.attributionSink?(cached)
      }
      return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
      plugin?.attributionSink = nil
      return nil
    }
  }
}

private final class DeferredMatchTokenResolver: NSObject, URLSessionTaskDelegate {
  private var session: URLSession?
  private var completion: ((Result<String, Error>) -> Void)?

  func resolve(url: URL, completion: @escaping (Result<String, Error>) -> Void) {
    self.completion = completion
    let configuration = URLSessionConfiguration.ephemeral
    session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    var request = URLRequest(url: url)
    request.httpMethod = "GET"
    request.timeoutInterval = 15
    session?.dataTask(with: request).resume()
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse,
    newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    // Keep the one-time token in the response header; do not forward it in a URL.
    completionHandler(nil)
    guard let token = response.value(forHTTPHeaderField: "X-Tixlnk-Match-Token"),
          !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      finish(.failure(resolutionError("Redirect did not include X-Tixlnk-Match-Token")))
      return
    }
    finish(.success(token))
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    guard let error else {
      finish(.failure(resolutionError("Link URL did not return a match-token redirect")))
      return
    }
    finish(.failure(error))
  }

  private func finish(_ result: Result<String, Error>) {
    guard let completion else { return }
    self.completion = nil
    completion(result)
    session?.finishTasksAndInvalidate()
    session = nil
  }

  private func resolutionError(_ message: String) -> NSError {
    NSError(domain: "TxlnkDeferredLink", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}
