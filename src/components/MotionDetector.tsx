import React, { useRef, useEffect, useState, useCallback } from 'react';
import { View, StyleSheet } from 'react-native';
import { Camera, useCameraDevice, useCameraPermission } from 'react-native-vision-camera';
import MotionDetectionModule from '../utils/MotionDetectionModule';

export type MotionStatus = 'idle' | 'active' | 'error' | 'no-camera';

interface MotionDetectorProps {
  enabled: boolean;
  onMotionDetected: () => void;
  sensitivity: 'low' | 'medium' | 'high';
  cameraPosition?: 'front' | 'back';
  onStatusChange?: (status: MotionStatus) => void;
}

const THROTTLE_INTERVAL = 2000; // Minimum 2s entre détections
const CAPTURE_INTERVAL = 1000; // Capturer une photo par seconde
const CAMERA_READY_DELAY = 1500; // Delay before starting detection to let camera initialize and auto-expose
const WARMUP_FRAMES = 3; // Skip first N frames to let camera auto-exposure adjust

// Seuils de sensibilité : ratio de pixels qui doivent changer
const SENSITIVITY_THRESHOLDS = {
  low: 0.15,    // 15% de changement
  medium: 0.08, // 8% de changement
  high: 0.04,   // 4% de changement
};

const MotionDetector: React.FC<MotionDetectorProps> = ({
  enabled,
  onMotionDetected,
  sensitivity,
  cameraPosition = 'front',
  onStatusChange,
}) => {
  const device = useCameraDevice(cameraPosition);
  const { hasPermission } = useCameraPermission();
  const cameraRef = useRef<Camera>(null);
  const lastMotionTime = useRef<number>(0);
  const detectionInterval = useRef<ReturnType<typeof setInterval> | null>(null);
  const isMounted = useRef<boolean>(true);
  const isCapturing = useRef<boolean>(false);
  const warmupFrames = useRef<number>(0);
  const captureCount = useRef<number>(0);
  const [isCameraActive, setIsCameraActive] = useState(false);
  const [isCameraReady, setIsCameraReady] = useState(false);

  // Track mounted state
  useEffect(() => {
    isMounted.current = true;
    console.log(`[MotionDetection] Initializing with ${cameraPosition} camera`);
    return () => {
      isMounted.current = false;
      console.log('[MotionDetection] Component unmounted');
    };
  }, [cameraPosition]);

  const stopDetection = useCallback(() => {
    if (detectionInterval.current) {
      clearInterval(detectionInterval.current);
      detectionInterval.current = null;
    }
    isCapturing.current = false;
    warmupFrames.current = 0;
    captureCount.current = 0;
    // Reset native module
    MotionDetectionModule?.reset().catch(() => {});
  }, []);

  const captureAndCompare = useCallback(async () => {
    // Guard against multiple concurrent captures and unmounted state
    if (!isMounted.current || !enabled || isCapturing.current || !isCameraReady) {
      return;
    }

    // Check if camera ref exists and is valid
    const camera = cameraRef.current;
    if (!camera) {
      console.warn('[MotionDetection] Camera ref is null, skipping capture');
      return;
    }

    isCapturing.current = true;

    try {
      const photo = await camera.takePhoto({
        flash: 'off',
        enableShutterSound: false,
      });

      // Check if still mounted after async operation
      if (!isMounted.current || !enabled) {
        return;
      }

      if (!photo || !photo.path) {
        console.warn('[MotionDetection] Photo capture returned no path');
        return;
      }

      captureCount.current += 1;

      // Skip warmup frames to let camera auto-exposure adjust
      // This is critical when screen brightness is 0 (dark environment)
      if (warmupFrames.current < WARMUP_FRAMES) {
        warmupFrames.current += 1;
        console.log(`[MotionDetection] Warmup frame ${warmupFrames.current}/${WARMUP_FRAMES}, skipping comparison`);
        // Still send to native to build up the "previous" frame
        await MotionDetectionModule.compareImages(
          photo.path,
          SENSITIVITY_THRESHOLDS[sensitivity]
        );
        return;
      }

      // Use native module for pixel comparison
      const hasMotion = await MotionDetectionModule.compareImages(
        photo.path,
        SENSITIVITY_THRESHOLDS[sensitivity]
      );

      // Log periodically (every 10 captures) to avoid log spam
      if (captureCount.current % 10 === 0) {
        console.log(`[MotionDetection] Capture #${captureCount.current}, hasMotion=${hasMotion}, sensitivity=${sensitivity}, threshold=${SENSITIVITY_THRESHOLDS[sensitivity]}`);
      }

      // Check again after async operation
      if (!isMounted.current || !enabled) {
        return;
      }

      if (hasMotion) {
        console.log('[MotionDetection] Motion detected! Triggering callback');
        const now = Date.now();
        if (now - lastMotionTime.current > THROTTLE_INTERVAL) {
          lastMotionTime.current = now;
          onMotionDetected();
        } else {
          console.log('[MotionDetection] Motion throttled (too soon after last detection)');
        }
      }
    } catch (error: any) {
      // Log the error for debugging - but don't crash
      // findCameraView errors happen when camera is unmounted during capture
      if (error?.message && !error.message.includes('findCameraView')) {
        console.warn(`[MotionDetection] Capture error: ${error.message}`);
      }
    } finally {
      isCapturing.current = false;
    }
  }, [enabled, sensitivity, onMotionDetected, isCameraReady]);

  const startDetection = useCallback(() => {
    stopDetection();

    // Add a small delay to ensure camera is fully initialized
    setTimeout(() => {
      if (!isMounted.current || !enabled) return;

      detectionInterval.current = setInterval(() => {
        if (isMounted.current && enabled && isCameraReady) {
          captureAndCompare();
        }
      }, CAPTURE_INTERVAL);
    }, CAMERA_READY_DELAY);
  }, [stopDetection, captureAndCompare, enabled, isCameraReady]);

  useEffect(() => {
    if (enabled && hasPermission && device) {
      setIsCameraActive(true);
    } else {
      setIsCameraActive(false);
      setIsCameraReady(false);
      stopDetection();
    }

    return () => {
      stopDetection();
    };
  }, [enabled, hasPermission, device, stopDetection]);

  // Start detection only when camera is ready
  useEffect(() => {
    if (isCameraReady && enabled && hasPermission) {
      startDetection();
    }
    return () => {
      stopDetection();
    };
  }, [isCameraReady, enabled, hasPermission, startDetection, stopDetection]);

  const handleCameraInitialized = useCallback(() => {
    if (isMounted.current) {
      console.log(`[MotionDetection] Camera initialized successfully (${cameraPosition})`);
      setIsCameraReady(true);
      onStatusChange?.('active');
    }
  }, [cameraPosition, onStatusChange]);

  const handleCameraError = useCallback((error: any) => {
    console.error(`[MotionDetection] Camera error (${cameraPosition}):`, error);
    setIsCameraReady(false);
    stopDetection();
    onStatusChange?.('error');
  }, [cameraPosition, stopDetection, onStatusChange]);

  // Check if camera is available and notify status
  useEffect(() => {
    if (!device && enabled) {
      console.warn(`[MotionDetection] No ${cameraPosition} camera available on this device`);
      onStatusChange?.('no-camera');
    } else if (!enabled) {
      onStatusChange?.('idle');
    }
  }, [device, enabled, cameraPosition, onStatusChange]);

  if (!enabled || !device || !hasPermission) {
    return null;
  }

  return (
    <View style={styles.container} pointerEvents="none">
      <Camera
        ref={cameraRef}
        style={styles.camera}
        device={device}
        isActive={isCameraActive}
        photo={true}
        lowLightBoost={device.supportsLowLightBoost}
        onInitialized={handleCameraInitialized}
        onError={handleCameraError}
        enableZoomGesture={false}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    // Use off-screen positioning instead of opacity:0 which can prevent
    // camera capture on some Android devices
    left: -1000,
    top: -1000,
    width: 1,
    height: 1,
    overflow: 'hidden',
  },
  camera: {
    width: 320,
    height: 240,
  },
});

export default MotionDetector;
