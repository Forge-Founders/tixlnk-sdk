Pod::Spec.new do |s|
  s.name             = 'txlnk_adtech_sdk'
  s.version          = '0.2.1'
  s.summary          = 'Tixlnk AdTech attribution SDK Flutter plugin.'
  s.description      = <<-DESC
Flutter plugin wrapper for the Tixlnk AdTech attribution SDK (KMP core).
                       DESC
  s.homepage         = 'https://github.com/Forge-Founders/tixlnk-sdk'
  s.license          = { :type => 'Apache-2.0', :file => '../LICENSE' }
  s.author           = { 'Tixlnk' => 'dev@txlnk.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '12.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  s.static_framework = true

  # Privacy Manifest (required for App Store submissions using reason-required APIs).
  s.resource_bundles = { 'txlnk_adtech_sdk_privacy' => ['PrivacyInfo.xcprivacy'] }
end
