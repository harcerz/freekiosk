import React, { useState, useEffect, useRef } from 'react';
import { View, StyleSheet, TouchableOpacity, Text, NativeEventEmitter, NativeModules, AppState } from 'react-native';
import RNBrightness from 'react-native-brightness-newarch';
import WebViewComponent from '../components/WebViewComponent';
import MotionDetector from '../components/MotionDetector';
import ExternalAppOverlay from '../components/ExternalAppOverlay';
import { StorageService } from '../utils/storage';
import KioskModule from '../utils/KioskModule';
import AppLauncherModule from '../utils/AppLauncherModule';
import OverlayServiceModule from '../utils/OverlayServiceModule';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/AppNavigator';

type KioskScreenNavigationProp = NativeStackNavigationProp<RootStackParamList, 'Kiosk'>;

interface KioskScreenProps {
  navigation: KioskScreenNavigationProp;
}

let tapCount = 0;
let tapTimer: any = null;

const KioskScreen: React.FC<KioskScreenProps> = ({ navigation }) => {
  const [url, setUrl] = useState<string>('');
  const [autoReload, setAutoReload] = useState<boolean>(false);
  const [screensaverEnabled, setScreensaverEnabled] = useState(false);
  const [isScreensaverActive, setIsScreensaverActive] = useState(false);
  const [defaultBrightness, setDefaultBrightness] = useState<number>(0.5);
  const [screensaverBrightness, setScreensaverBrightness] = useState<number>(0);
  const [inactivityEnabled, setInactivityEnabled] = useState(true);
  const [inactivityDelay, setInactivityDelay] = useState(600000);
  const [motionEnabled, setMotionEnabled] = useState(false);
  const timerRef = useRef<any>(null);

  // External app states
  const [displayMode, setDisplayMode] = useState<'webview' | 'external_app'>('webview');
  const [externalAppPackage, setExternalAppPackage] = useState<string | null>(null);
  const [autoRelaunchApp, setAutoRelaunchApp] = useState<boolean>(true);
  const [appCrashCount, setAppCrashCount] = useState<number>(0);
  const relaunchTimerRef = useRef<any>(null);
  const [isAppLaunched, setIsAppLaunched] = useState<boolean>(false);
  const appStateRef = useRef(AppState.currentState);

  // AppState listener - détecte quand l'app revient au premier plan
  useEffect(() => {
    const subscription = AppState.addEventListener('change', async nextAppState => {
      console.log(`[KioskScreen] AppState changed: ${appStateRef.current} -> ${nextAppState}`);
      
      // L'app revient au premier plan (depuis background ou inactive)
      if (appStateRef.current.match(/inactive|background/) && nextAppState === 'active') {
        console.log('[KioskScreen] App came to foreground, checking native block flag...');
        
        try {
          // Vérifier le flag natif (plus fiable que le ref React)
          const shouldBlock = await KioskModule.shouldBlockAutoRelaunch();
          console.log(`[KioskScreen] Native shouldBlockAutoRelaunch = ${shouldBlock}`);
          
          if (shouldBlock) {
            console.log('[KioskScreen] Blocked by native flag - NOT relaunching, clearing flag');
            // Reset le flag après l'avoir lu
            await KioskModule.clearBlockAutoRelaunch();
          } else if (displayMode === 'external_app' && externalAppPackage && autoRelaunchApp) {
            console.log('[KioskScreen] Auto-relaunching external app from AppState...');
            
            // Petit délai pour laisser l'UI se stabiliser
            setTimeout(() => {
              launchExternalApp(externalAppPackage);
            }, 300);
          }
        } catch (error) {
          console.error('[KioskScreen] Error checking block flag:', error);
        }
      }
      
      appStateRef.current = nextAppState;
    });

    return () => {
      subscription.remove();
    };
  }, [displayMode, externalAppPackage, autoRelaunchApp]);

  useEffect(() => {
    const unsubscribeFocus = navigation.addListener('focus', () => {
      loadSettings();
    });

    const unsubscribeBlur = navigation.addListener('blur', () => {
      clearTimer();
      setIsScreensaverActive(false);
      // On ne restaure pas la luminosité volontairement
    });

    return () => {
      unsubscribeFocus();
      unsubscribeBlur();
    };
  }, [navigation]);

  useEffect(() => {
    if (!isScreensaverActive) {
      (async () => {
        try {
          await RNBrightness.setBrightnessLevel(defaultBrightness);
          console.log(`[DEBUG Brightness] Luminosité normale appliquée: ${Math.round(defaultBrightness * 100)}%`);
        } catch (error) {
          console.error('[DEBUG Brightness] Erreur application luminosité:', error);
        }
      })();
    } else {
      console.log('[DEBUG Brightness] Screensaver active, skipping brightness restore');
    }
  }, [defaultBrightness, isScreensaverActive]);

  useEffect(() => {
    if (isScreensaverActive) {
      enableScreensaverEffects();
    }
  }, [isScreensaverActive, screensaverBrightness]);

  useEffect(() => {
    if (screensaverEnabled && inactivityEnabled) {
      resetTimer();
    } else {
      clearTimer();
      setIsScreensaverActive(false);
    }
  }, [screensaverEnabled, inactivityEnabled, inactivityDelay]);

  useEffect(() => {
    // Event emitter pour les événements natifs (MainActivity)
    const eventEmitter = new NativeEventEmitter(NativeModules.DeviceEventManagerModule);

    // Listen for app return events (émis depuis MainActivity.onResume)
    const appReturnedListener = eventEmitter.addListener(
      'onAppReturned',
      handleAppReturned
    );

    // Listen for navigateToPin event (5-tap depuis overlay ou Volume Up)
    const navigateToPinListener = eventEmitter.addListener(
      'navigateToPin',
      () => {
        console.log('[KioskScreen] Received navigateToPin event - navigating to PIN');
        // Le flag natif est déjà mis par OverlayService.returnToFreeKiosk()
        navigation.navigate('Pin');
      }
    );

    return () => {
      appReturnedListener.remove();
      navigateToPinListener.remove();
      if (relaunchTimerRef.current) {
        clearTimeout(relaunchTimerRef.current);
      }
    };
  }, [autoRelaunchApp, displayMode, externalAppPackage, appCrashCount, navigation]);

  const loadSettings = async (): Promise<void> => {
    try {
      const savedUrl = await StorageService.getUrl();
      const savedAutoReload = await StorageService.getAutoReload();
      const savedKioskEnabled = await StorageService.getKioskEnabled();
      const savedScreensaverEnabled = await StorageService.getScreensaverEnabled();
      const savedDefaultBrightness = await StorageService.getDefaultBrightness();
      const savedScreensaverBrightness = await StorageService.getScreensaverBrightness();
      const savedInactivityEnabled = await StorageService.getScreensaverInactivityEnabled();
      const savedInactivityDelay = await StorageService.getScreensaverInactivityDelay();
      const savedMotionEnabled = await StorageService.getScreensaverMotionEnabled();

      console.log('[DEBUG loadSettings] URL:', savedUrl);
      console.log('[DEBUG loadSettings] Screensaver enabled:', savedScreensaverEnabled);
      console.log('[DEBUG loadSettings] Default brightness:', savedDefaultBrightness);
      console.log('[DEBUG loadSettings] Screensaver brightness:', savedScreensaverBrightness);
      console.log('[DEBUG loadSettings] Inactivity enabled:', savedInactivityEnabled);
      console.log('[DEBUG loadSettings] Inactivity delay (ms):', savedInactivityDelay);
      console.log('[DEBUG loadSettings] Motion enabled:', savedMotionEnabled);

      if (savedUrl) setUrl(savedUrl);
      setAutoReload(savedAutoReload);
      setScreensaverEnabled(savedScreensaverEnabled ?? false);
      setDefaultBrightness(savedDefaultBrightness ?? 0.5);
      setScreensaverBrightness(savedScreensaverBrightness ?? 0);
      setInactivityEnabled(savedInactivityEnabled ?? true);
      setInactivityDelay(savedInactivityDelay ?? 600000);
      setMotionEnabled(savedMotionEnabled ?? false);

      // Load external app settings
      const savedDisplayMode = await StorageService.getDisplayMode();
      const savedExternalAppPackage = await StorageService.getExternalAppPackage();
      const savedAutoRelaunchApp = await StorageService.getAutoRelaunchApp();

      console.log('[DEBUG loadSettings] Display mode:', savedDisplayMode);
      console.log('[DEBUG loadSettings] External app package:', savedExternalAppPackage);
      console.log('[DEBUG loadSettings] Auto relaunch app:', savedAutoRelaunchApp);

      setDisplayMode(savedDisplayMode);
      setExternalAppPackage(savedExternalAppPackage);
      setAutoRelaunchApp(savedAutoRelaunchApp);

      if (savedKioskEnabled) {
        try {
          // Pass external app package so it gets added to whitelist
          const packageToWhitelist = savedDisplayMode === 'external_app' ? savedExternalAppPackage : null;
          await KioskModule.startLockTask(packageToWhitelist);
          console.log('[KioskScreen] Lock task enabled');
        } catch {
          console.log('[KioskScreen] Failed to start lock task');
        }
      } else {
        try {
          await KioskModule.stopLockTask();
          console.log('[KioskScreen] Lock task disabled');
        } catch {
          console.log('[KioskScreen] Not in lock task mode');
        }
      }

      // Launch external app if in external_app mode
      if (savedDisplayMode === 'external_app' && savedExternalAppPackage) {
        await launchExternalApp(savedExternalAppPackage);
      }
    } catch (error) {
      console.error('[KioskScreen] loadSettings error:', error);
    }
  };

  const resetTimer = () => {
    clearTimer();
    if (screensaverEnabled && inactivityEnabled) {
      timerRef.current = setTimeout(() => {
        setIsScreensaverActive(true);
      }, inactivityDelay);
      console.log('[DEBUG] Inactivity timer reset');
    }
  };

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
      console.log('[DEBUG] Inactivity timer cleared');
    }
  };

  const onUserInteraction = () => {
    console.log('[DEBUG] User interaction detected, resetting timer');
    resetTimer();
    if (isScreensaverActive) {
      setIsScreensaverActive(false);
      console.log('[DEBUG] Screensaver deactivated by user interaction');
    }
  };

  const onScreensaverTap = () => {
    setIsScreensaverActive(false);
    resetTimer();
    console.log('[DEBUG] Screensaver deactivated by tap on overlay');
  };

  const onMotionDetected = () => {
    console.log('[DEBUG MOTION] Motion detected!');
    if (isScreensaverActive) {
      console.log('[DEBUG MOTION] Waking up screensaver and resetting timer');
      setIsScreensaverActive(false);
      resetTimer(); // IMPORTANT: Reset timer after waking up
    } else {
      console.log('[DEBUG MOTION] Screensaver not active, ignoring');
    }
  };

  const enableScreensaverEffects = async () => {
    try {
      await RNBrightness.setBrightnessLevel(screensaverBrightness);
      console.log(`Screensaver activé : luminosité à ${Math.round(screensaverBrightness * 100)}%`);
    } catch (error) {
      console.error('Erreur activation luminosité screensaver:', error);
    }
  };

  const launchExternalApp = async (packageName: string): Promise<void> => {
    try {
      const isInstalled = await AppLauncherModule.isAppInstalled(packageName);
      if (!isInstalled) {
        console.error(`[KioskScreen] App not installed: ${packageName}`);
        return;
      }

      // Démarrer l'OverlayService AVANT de lancer l'app externe
      try {
        await OverlayServiceModule.startOverlayService();
        console.log('[KioskScreen] OverlayService started');
      } catch (overlayError) {
        console.warn('[KioskScreen] Failed to start overlay service:', overlayError);
        // Continue anyway - l'app externe peut toujours être lancée
      }

      await AppLauncherModule.launchExternalApp(packageName);
      setIsAppLaunched(true);
      console.log(`[KioskScreen] Launched external app: ${packageName}`);
    } catch (error) {
      console.error('[KioskScreen] Failed to launch app:', error);
    }
  };

  const handleAppReturned = (event?: { voluntary?: boolean }): void => {
    const isVoluntary = event?.voluntary ?? false;
    console.log(`[KioskScreen] handleAppReturned called (voluntary=${isVoluntary})`);
    setIsAppLaunched(false);

    // Arrêter l'OverlayService quand on revient sur FreeKiosk
    OverlayServiceModule.stopOverlayService()
      .then(() => console.log('[KioskScreen] OverlayService stopped'))
      .catch(error => console.warn('[KioskScreen] Failed to stop overlay:', error));

    // Si retour volontaire (5 taps), le flag natif est déjà mis par OverlayService
    if (isVoluntary) {
      console.log('[KioskScreen] Voluntary return detected - native flag already set');
      setAppCrashCount(0);
    }
    // Note: Le relaunch automatique est maintenant géré par AppState listener
  };

  const handleSecretTap = (): void => {
    tapCount++;
    if (tapTimer) clearTimeout(tapTimer);

    if (tapCount === 5) {
      tapCount = 0;
      clearTimer();
      setIsScreensaverActive(false);
      navigation.navigate('Pin');
      console.log('[DEBUG] Navigating to PIN screen');
    }

    tapTimer = setTimeout(() => {
      tapCount = 0;
    }, 2000);
  };

  const handleReturnToExternalApp = async (): Promise<void> => {
    if (externalAppPackage) {
      await launchExternalApp(externalAppPackage);
    }
  };

  const handleGoToSettings = (): void => {
    clearTimer();
    setIsScreensaverActive(false);
    navigation.navigate('Pin');
  };

  return (
    <View style={styles.container}>
      {displayMode === 'webview' ? (
        <WebViewComponent url={url} autoReload={autoReload} onUserInteraction={onUserInteraction} />
      ) : (
        <ExternalAppOverlay
          externalAppPackage={externalAppPackage}
          isAppLaunched={isAppLaunched}
          onReturnToApp={handleReturnToExternalApp}
          onGoToSettings={handleGoToSettings}
        />
      )}

      {/* Motion Detector - Only active when screensaver is ON */}
      <MotionDetector
        enabled={motionEnabled && isScreensaverActive}
        onMotionDetected={onMotionDetected}
        sensitivity="medium"
      />

      <TouchableOpacity
        style={styles.secretButton}
        onPress={handleSecretTap}
        activeOpacity={1}
      />

      {isScreensaverActive && screensaverEnabled && (
        <TouchableOpacity
          style={styles.screensaverOverlay}
          activeOpacity={1}
          onPress={onScreensaverTap}
        />
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  secretButton: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    width: 80,
    height: 80,
    backgroundColor: 'transparent',
  },
  screensaverOverlay: {
    position: 'absolute',
    top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: '#000',
    opacity: 1,
    zIndex: 1000,
  },
});

export default KioskScreen;
