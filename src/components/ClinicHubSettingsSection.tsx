/**
 * ClinicHubSettingsSection.tsx
 * Settings section for the Dentrio clinic hub (watch relay).
 *
 * The tablet authenticates as its room's device against the clinic server
 * (deviceId + deviceToken from the clinic admin panel, Settings → Tablety)
 * and keeps the room's watch summary fresh. Mirrors MqttSettingsSection.
 */

import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Alert,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import SettingsSection from './settings/SettingsSection';
import SettingsSwitch from './settings/SettingsSwitch';
import SettingsInput from './settings/SettingsInput';
import Icon from './Icon';
import { StorageService } from '../utils/storage';
import { hubClient, type HubSummary } from '../utils/HubModule';
import { getSecureHubToken, saveSecureHubToken } from '../utils/secureStorage';
import { unpair } from '../utils/PairingService';
import type { RootStackParamList } from '../navigation/AppNavigator';

interface ClinicHubSettingsSectionProps {
  onSettingsChanged?: () => void;
}

export const ClinicHubSettingsSection: React.FC<ClinicHubSettingsSectionProps> = ({
  onSettingsChanged,
}) => {
  const navigation =
    useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const [hubEnabled, setHubEnabled] = useState(false);
  const [serverUrl, setServerUrl] = useState('');
  const [socketUrl, setSocketUrl] = useState('');
  const [deviceId, setDeviceId] = useState('');
  const [deviceToken, setDeviceToken] = useState('');
  const [pairedLabel, setPairedLabel] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [isConnected, setIsConnected] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [roomName, setRoomName] = useState<string | null>(null);
  const connectionTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    loadSettings();
  }, []);

  // Poll connection + room status while the section is visible.
  useEffect(() => {
    const checkStatus = async () => {
      setIsConnected(await hubClient.isConnected());
      const summary = await hubClient.getSummary();
      setRoomName(summary?.room?.name ?? null);
    };
    checkStatus();
    const interval = setInterval(checkStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const unsubscribe = hubClient.onConnectionChanged((connected) => {
      setIsConnected(connected);
      setIsLoading(false);
      if (connected) {
        setConnectionError(null);
      }
      if (connectionTimeoutRef.current) {
        clearTimeout(connectionTimeoutRef.current);
        connectionTimeoutRef.current = null;
      }
    });
    return () => {
      unsubscribe();
      if (connectionTimeoutRef.current) {
        clearTimeout(connectionTimeoutRef.current);
      }
    };
  }, []);

  useEffect(() => {
    const unsubscribe = hubClient.onConnectionError((message) => {
      setConnectionError(message);
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    const unsubscribe = hubClient.onSummaryChanged((summary: HubSummary) => {
      setRoomName(summary.room?.name ?? null);
    });
    return unsubscribe;
  }, []);

  const loadSettings = async () => {
    const [enabled, server, socket, id, token, label] = await Promise.all([
      StorageService.getHubEnabled(),
      StorageService.getHubServerUrl(),
      StorageService.getHubSocketUrl(),
      StorageService.getHubDeviceId(),
      getSecureHubToken(),
      StorageService.getPairedLabel(),
    ]);
    setHubEnabled(enabled);
    setServerUrl(server);
    setSocketUrl(socket);
    setDeviceId(id);
    setDeviceToken(token);
    setPairedLabel(label);
  };

  const handleUnpair = () => {
    Alert.alert(
      'Unpair from clinic',
      `This clears the clinic configuration of "${pairedLabel || 'this tablet'}" (login, hub, remote management). Re-pair by scanning a fresh QR from the admin panel.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Unpair',
          style: 'destructive',
          onPress: async () => {
            setIsLoading(true);
            try {
              await unpair();
              await loadSettings();
              setIsConnected(false);
              setRoomName(null);
              setConnectionError(null);
              onSettingsChanged?.();
            } catch (error: any) {
              console.error('[ClinicHub] Unpair failed:', error);
              setConnectionError(error?.message ?? 'Unpair failed');
            } finally {
              setIsLoading(false);
            }
          },
        },
      ],
    );
  };

  const handleConnect = async () => {
    setIsLoading(true);
    setConnectionError(null);
    try {
      await hubClient.restart();
      connectionTimeoutRef.current = setTimeout(() => {
        setIsLoading((current) => {
          if (current) {
            setConnectionError(
              'Connection timed out after 15 seconds. Check the server URL, socket URL and device credentials.',
            );
            return false;
          }
          return current;
        });
      }, 15000);
    } catch (error: any) {
      console.error('[ClinicHub] Failed to connect:', error);
      setConnectionError(error.message || 'Unknown error');
      setIsLoading(false);
    }
  };

  const handleDisconnect = async () => {
    setIsLoading(true);
    try {
      await hubClient.stop();
      setIsConnected(false);
      setRoomName(null);
    } catch (error: any) {
      console.error('[ClinicHub] Failed to disconnect:', error);
    } finally {
      setIsLoading(false);
    }
  };

  const handleHubEnabledChange = async (value: boolean) => {
    setHubEnabled(value);
    await StorageService.saveHubEnabled(value);
    if (value) {
      await handleConnect();
    } else {
      await handleDisconnect();
    }
    onSettingsChanged?.();
  };

  const handleServerUrlChange = async (value: string) => {
    setServerUrl(value);
    await StorageService.saveHubServerUrl(value.trim());
    onSettingsChanged?.();
  };

  // Fill the socket URL from the server URL only once the user leaves the
  // field (onBlur) — deriving it per-keystroke captured half-typed hosts.
  // Default: same host, port 3003 (the clinic socket server default).
  const handleServerUrlBlur = async () => {
    if (socketUrl.trim()) return;
    const match = serverUrl.trim().match(/^(https?):\/\/([^/:\s]+)/);
    if (!match) return;
    const derived = `${match[1]}://${match[2]}:3003`;
    setSocketUrl(derived);
    await StorageService.saveHubSocketUrl(derived);
    onSettingsChanged?.();
  };

  const handleSocketUrlChange = async (value: string) => {
    setSocketUrl(value);
    await StorageService.saveHubSocketUrl(value.trim());
    onSettingsChanged?.();
  };

  const handleDeviceIdChange = async (value: string) => {
    setDeviceId(value);
    await StorageService.saveHubDeviceId(value.trim());
    onSettingsChanged?.();
  };

  const handleDeviceTokenChange = async (value: string) => {
    setDeviceToken(value);
    await saveSecureHubToken(value.trim());
    onSettingsChanged?.();
  };

  const isConfigured =
    serverUrl.trim().length > 0 &&
    socketUrl.trim().length > 0 &&
    deviceId.trim().length > 0 &&
    deviceToken.trim().length > 0;

  const getStatusColor = () => {
    if (isLoading) return '#FF9800';
    return isConnected ? '#4CAF50' : '#F44336';
  };

  const getStatusText = () => {
    if (isLoading) return 'Connecting...';
    if (isConnected) {
      return roomName ? `Connected — ${roomName}` : 'Connected';
    }
    return 'Disconnected';
  };

  return (
    <SettingsSection title="Clinic Hub (Dentrio)" icon="cellphone-link">
      {/* One-scan pairing status / entry point */}
      {pairedLabel ? (
        <View style={styles.pairedRow}>
          <Icon name="check-circle" size={18} color="#4CAF50" />
          <Text style={styles.pairedText}>
            Paired: {pairedLabel}
            {isConnected && roomName ? ` — ${roomName}` : ''}
          </Text>
          <TouchableOpacity
            style={styles.unpairButton}
            onPress={handleUnpair}
            disabled={isLoading}
          >
            <Text style={styles.unpairButtonText}>Unpair</Text>
          </TouchableOpacity>
        </View>
      ) : (
        <TouchableOpacity
          style={styles.pairButton}
          onPress={() => navigation.navigate('Pairing')}
        >
          <Icon name="camera" size={16} color="#FFF" />
          <Text style={styles.pairButtonText}>Pair with clinic (scan QR)</Text>
        </TouchableOpacity>
      )}

      <SettingsSwitch
        label="Enable Clinic Hub"
        value={hubEnabled}
        onValueChange={handleHubEnabledChange}
        icon="cellphone-link"
        hint="Relay room chat, waiting-patient and visit info to the room's smartwatch"
      />

      {hubEnabled && (
        <>
          {/* Connection status + connect/disconnect */}
          <View style={styles.statusContainer}>
            <View style={styles.statusRow}>
              <View
                style={[styles.statusIndicator, { backgroundColor: getStatusColor() }]}
              />
              <Text style={styles.statusText}>{getStatusText()}</Text>
              {isLoading && (
                <ActivityIndicator size="small" color="#007AFF" style={styles.loader} />
              )}
            </View>

            {connectionError && <Text style={styles.errorText}>{connectionError}</Text>}

            {!isLoading && (
              <View style={styles.connectButtonRow}>
                {isConnected ? (
                  <TouchableOpacity
                    style={[styles.connectButton, styles.disconnectButton]}
                    onPress={handleDisconnect}
                  >
                    <Icon name="lan-disconnect" size={16} color="#FFF" />
                    <Text style={styles.connectButtonText}>Disconnect</Text>
                  </TouchableOpacity>
                ) : isConfigured ? (
                  <TouchableOpacity style={styles.connectButton} onPress={handleConnect}>
                    <Icon name="lan-connect" size={16} color="#FFF" />
                    <Text style={styles.connectButtonText}>Connect</Text>
                  </TouchableOpacity>
                ) : (
                  <Text style={styles.connectHint}>
                    Fill in server URL, device ID and device token to connect
                  </Text>
                )}
              </View>
            )}
          </View>

          {/* Manual connection settings — normally filled by QR pairing. */}
          <TouchableOpacity
            style={styles.advancedToggle}
            onPress={() => setShowAdvanced((value) => !value)}
          >
            <Icon
              name={showAdvanced ? 'chevron-down' : 'chevron-right'}
              size={18}
              color="#666"
            />
            <Text style={styles.advancedToggleText}>
              Advanced — manual connection settings
            </Text>
          </TouchableOpacity>

          {showAdvanced && (
            <>
              <SettingsInput
                label="Server URL"
                value={serverUrl}
                onChangeText={handleServerUrlChange}
                onBlur={handleServerUrlBlur}
                placeholder="e.g. https://portal.local"
                keyboardType="url"
                icon="server-network"
                hint="Clinic system base URL (required)"
              />

              <SettingsInput
                label="Socket URL"
                value={socketUrl}
                onChangeText={handleSocketUrlChange}
                placeholder="e.g. https://portal.local:3003"
                keyboardType="url"
                icon="lan-connect"
                hint="Clinic realtime (Socket.IO) server — auto-filled from the server URL"
              />

              <SettingsInput
                label="Device ID"
                value={deviceId}
                onChangeText={handleDeviceIdChange}
                placeholder="Tablet ID from the clinic admin panel"
                icon="tablet"
                hint="Clinic admin panel → Settings → Tablets"
              />

              <SettingsInput
                label="Device Token"
                value={deviceToken}
                onChangeText={handleDeviceTokenChange}
                placeholder="Device token from the clinic admin panel"
                secureTextEntry
                icon="lock"
                hint={
                  deviceToken.length > 0
                    ? 'Token is saved securely. Leave untouched to keep the current token.'
                    : undefined
                }
              />
            </>
          )}

          <View style={styles.hintContainer}>
            <Icon name="information-outline" size={20} color="#41BDF5" />
            <Text style={styles.hintText}>
              The hub signs in as this room's tablet and keeps the smartwatch summary
              fresh (current visit, next patient, room chat). Pair the watch with this
              tablet via Bluetooth to receive updates.
            </Text>
          </View>
        </>
      )}
    </SettingsSection>
  );
};

const styles = StyleSheet.create({
  pairedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#E8F5E9',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  pairedText: {
    flex: 1,
    marginLeft: 8,
    fontSize: 14,
    fontWeight: '500',
    color: '#2E7D32',
  },
  unpairButton: {
    backgroundColor: '#F44336',
    borderRadius: 6,
    paddingVertical: 6,
    paddingHorizontal: 12,
  },
  unpairButtonText: {
    color: '#FFF',
    fontSize: 13,
    fontWeight: '600',
  },
  pairButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#1976D2',
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 16,
    marginBottom: 8,
  },
  pairButtonText: {
    color: '#FFF',
    fontSize: 14,
    fontWeight: '600',
    marginLeft: 8,
  },
  advancedToggle: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
  },
  advancedToggleText: {
    marginLeft: 4,
    fontSize: 14,
    color: '#666',
    fontWeight: '500',
  },
  statusContainer: {
    backgroundColor: '#F8F9FA',
    borderRadius: 8,
    padding: 12,
    marginVertical: 8,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusIndicator: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 8,
  },
  statusText: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  loader: {
    marginLeft: 8,
  },
  connectButtonRow: {
    marginTop: 10,
  },
  connectButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#4CAF50',
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 16,
  },
  disconnectButton: {
    backgroundColor: '#F44336',
  },
  connectButtonText: {
    color: '#FFF',
    fontSize: 14,
    fontWeight: '600',
    marginLeft: 8,
  },
  connectHint: {
    fontSize: 13,
    color: '#999',
    fontStyle: 'italic',
  },
  errorText: {
    fontSize: 13,
    color: '#F44336',
    marginTop: 8,
  },
  hintContainer: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#E3F2FD',
    borderRadius: 8,
    padding: 12,
    marginTop: 8,
  },
  hintText: {
    flex: 1,
    marginLeft: 8,
    fontSize: 13,
    color: '#1565C0',
    lineHeight: 18,
  },
});
