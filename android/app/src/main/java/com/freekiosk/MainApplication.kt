package com.freekiosk

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.modules.network.OkHttpClientProvider
import com.freekiosk.api.HttpServerPackage
import com.freekiosk.hub.HubPackage
import com.freekiosk.mqtt.MqttPackage
import com.freekiosk.net.AcceptedCertTrust

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here
          add(KioskPackage())
          add(CertificatePackage())
          add(MotionDetectionPackage())
          add(AppLauncherPackage())
          add(OverlayPermissionPackage())
          add(LauncherPackage())
          add(OverlayServicePackage())
          add(SystemInfoPackage())
          add(UpdatePackage())
          add(HttpServerPackage())
          add(MqttPackage())
          add(HubPackage())
          add(BlockingOverlayPackage())
          add(AutoBrightnessPackage())
          add(PrintPackage())
          add(AccessibilityPackage())
          add(FilePickerPackage())
          add(WifiControlPackage())
          add(BluetoothControlPackage())
          add(AudioControlPackage())
          add(FlashlightPackage())
          add(RotationControlPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Certificates accepted once in the kiosk WebView (self-signed clinic
    // servers) must be trusted app-wide — otherwise JS fetch (QR pairing)
    // fails with "Network request failed" while the WebView works fine.
    OkHttpClientProvider.setOkHttpClientFactory {
      AcceptedCertTrust.configure(
        OkHttpClientProvider.createClientBuilder(this),
        this,
      ).build()
    }
    loadReactNative(this)
  }
}
