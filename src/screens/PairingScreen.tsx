/**
 * PairingScreen.tsx
 * One-scan clinic pairing: point the tablet at the QR from the clinic admin
 * panel (Settings → Tablets) and PairingService configures everything —
 * WebView login, clinic hub, embedded REST API. Legacy QRs (no serverUrl)
 * are rejected with a hint to use manual setup instead.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import {
  Camera,
  useCameraDevice,
  useCameraPermission,
  useCodeScanner,
} from 'react-native-vision-camera';
import type { RootStackParamList } from '../navigation/AppNavigator';
import { parseClinicQr, pairWithClinic } from '../utils/PairingService';

type PairingStatus = 'scanning' | 'pairing' | 'success' | 'error';

const PairingScreen: React.FC = () => {
  const navigation =
    useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const device = useCameraDevice('back');
  const { hasPermission, requestPermission } = useCameraPermission();

  const [status, setStatus] = useState<PairingStatus>('scanning');
  const [message, setMessage] = useState<string | null>(null);
  const [pairedLabel, setPairedLabel] = useState<string>('');
  // Guards the scanner against firing while a scan is already processing.
  const busyRef = useRef(false);

  useEffect(() => {
    if (!hasPermission) {
      requestPermission();
    }
  }, [hasPermission, requestPermission]);

  const handleScannedValue = useCallback(
    async (raw: string) => {
      if (busyRef.current) {
        return;
      }
      busyRef.current = true;

      const payload = parseClinicQr(raw);
      if (!payload) {
        setMessage(
          'This is not a clinic pairing code. Generate a fresh QR in the admin panel (Settings → Tablets) or configure manually.',
        );
        // Let the user re-aim instead of spamming parse errors.
        setTimeout(() => {
          setMessage(null);
          busyRef.current = false;
        }, 2500);
        return;
      }

      setStatus('pairing');
      setMessage(null);
      try {
        const { label } = await pairWithClinic(payload);
        setPairedLabel(label);
        setStatus('success');
        setTimeout(() => {
          navigation.reset({ index: 0, routes: [{ name: 'Kiosk' }] });
        }, 1800);
      } catch (error: any) {
        console.error('[Pairing] Failed:', error);
        setStatus('error');
        setMessage(error?.message ?? 'Unknown pairing error');
      }
    },
    [navigation],
  );

  const codeScanner = useCodeScanner({
    codeTypes: ['qr'],
    onCodeScanned: codes => {
      const value = codes[0]?.value;
      if (value && status === 'scanning') {
        handleScannedValue(value);
      }
    },
  });

  const retryScan = () => {
    busyRef.current = false;
    setMessage(null);
    setStatus('scanning');
  };

  return (
    <View style={styles.container}>
      {status === 'scanning' && (
        <>
          {hasPermission && device ? (
            <Camera
              style={StyleSheet.absoluteFill}
              device={device}
              isActive={status === 'scanning'}
              codeScanner={codeScanner}
            />
          ) : (
            <View style={styles.permissionBox}>
              <Text style={styles.infoText}>
                Camera access is needed to scan the pairing QR code.
              </Text>
              <TouchableOpacity
                style={styles.primaryButton}
                onPress={requestPermission}
              >
                <Text style={styles.primaryButtonText}>Grant camera access</Text>
              </TouchableOpacity>
            </View>
          )}

          <View style={styles.overlayTop}>
            <Text style={styles.title}>Pair with clinic</Text>
            <Text style={styles.subtitle}>
              Scan the tablet QR code from the clinic admin panel
              (Settings → Tablets)
            </Text>
          </View>

          <View style={styles.frame} pointerEvents="none" />

          <View style={styles.overlayBottom}>
            {message && <Text style={styles.warnText}>{message}</Text>}
            <View style={styles.buttonRow}>
              <TouchableOpacity
                style={styles.secondaryButton}
                onPress={() => navigation.goBack()}
              >
                <Text style={styles.secondaryButtonText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.secondaryButton}
                onPress={() => navigation.navigate('Pin')}
              >
                <Text style={styles.secondaryButtonText}>
                  Configure manually
                </Text>
              </TouchableOpacity>
            </View>
          </View>
        </>
      )}

      {status === 'pairing' && (
        <View style={styles.centerBox}>
          <ActivityIndicator size="large" color="#41BDF5" />
          <Text style={styles.stateTitle}>Pairing with the clinic…</Text>
          <Text style={styles.infoText}>
            Verifying the code, signing the tablet in and starting the hub.
          </Text>
        </View>
      )}

      {status === 'success' && (
        <View style={styles.centerBox}>
          <Text style={styles.successIcon}>✅</Text>
          <Text style={styles.stateTitle}>Paired: {pairedLabel}</Text>
          <Text style={styles.infoText}>Opening the clinic tablet mode…</Text>
        </View>
      )}

      {status === 'error' && (
        <View style={styles.centerBox}>
          <Text style={styles.successIcon}>⚠️</Text>
          <Text style={styles.stateTitle}>Pairing failed</Text>
          {message && <Text style={styles.errorText}>{message}</Text>}
          <TouchableOpacity style={styles.primaryButton} onPress={retryScan}>
            <Text style={styles.primaryButtonText}>Scan again</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={() => navigation.goBack()}
          >
            <Text style={styles.secondaryButtonText}>Back</Text>
          </TouchableOpacity>
        </View>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  overlayTop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    padding: 24,
    backgroundColor: 'rgba(0,0,0,0.6)',
    alignItems: 'center',
  },
  title: {
    color: '#FFF',
    fontSize: 22,
    fontWeight: '700',
  },
  subtitle: {
    color: '#DDD',
    fontSize: 14,
    marginTop: 6,
    textAlign: 'center',
  },
  frame: {
    position: 'absolute',
    top: '25%',
    left: '30%',
    right: '30%',
    bottom: '25%',
    borderWidth: 2,
    borderColor: '#41BDF5',
    borderRadius: 16,
  },
  overlayBottom: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    padding: 20,
    backgroundColor: 'rgba(0,0,0,0.6)',
    alignItems: 'center',
  },
  buttonRow: {
    flexDirection: 'row',
    gap: 12,
  },
  centerBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
  },
  permissionBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
  },
  stateTitle: {
    color: '#FFF',
    fontSize: 20,
    fontWeight: '600',
    marginTop: 16,
    textAlign: 'center',
  },
  infoText: {
    color: '#BBB',
    fontSize: 14,
    marginTop: 10,
    textAlign: 'center',
  },
  warnText: {
    color: '#FFB74D',
    fontSize: 14,
    marginBottom: 12,
    textAlign: 'center',
  },
  errorText: {
    color: '#EF9A9A',
    fontSize: 14,
    marginTop: 10,
    textAlign: 'center',
  },
  successIcon: {
    fontSize: 48,
  },
  primaryButton: {
    marginTop: 20,
    backgroundColor: '#4CAF50',
    borderRadius: 8,
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  primaryButtonText: {
    color: '#FFF',
    fontSize: 15,
    fontWeight: '600',
  },
  secondaryButton: {
    marginTop: 12,
    backgroundColor: 'rgba(255,255,255,0.15)',
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 20,
  },
  secondaryButtonText: {
    color: '#FFF',
    fontSize: 14,
  },
});

export default PairingScreen;
