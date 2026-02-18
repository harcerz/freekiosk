import {NativeModules, Platform} from 'react-native';

interface IAccessibilityModule {
  /**
   * Check if the AccessibilityService is enabled in Android system settings.
   */
  isAccessibilityServiceEnabled(): Promise<boolean>;

  /**
   * Check if the AccessibilityService is currently running (connected).
   */
  isAccessibilityServiceRunning(): Promise<boolean>;

  /**
   * Open Android Accessibility Settings page.
   */
  openAccessibilitySettings(): Promise<boolean>;

  /**
   * In Device Owner mode, programmatically enable the AccessibilityService
   * without requiring manual user intervention.
   */
  enableViaDeviceOwner(): Promise<boolean>;
}

const AccessibilityModule: IAccessibilityModule =
  Platform.OS === 'android'
    ? NativeModules.AccessibilityModule
    : {
        isAccessibilityServiceEnabled: () => Promise.resolve(false),
        isAccessibilityServiceRunning: () => Promise.resolve(false),
        openAccessibilitySettings: () => Promise.resolve(false),
        enableViaDeviceOwner: () => Promise.resolve(false),
      };

export default AccessibilityModule;
