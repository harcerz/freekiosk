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
} from 'react-native';
import SettingsSection from './settings/SettingsSection';
import SettingsSwitch from './settings/SettingsSwitch';
import SettingsInput from './settings/SettingsInput';
import Icon from './Icon';
import { StorageService } from '../utils/storage';
import { hubClient, type HubSummary } from '../utils/HubModule';
import { getSecureHubToken, saveSecureHubToken } from '../utils/secureStorage';

interface ClinicHubSettingsSectionProps {
  onSettingsChanged?: () => void;
}

export const ClinicHubSettingsSection: React.FC<ClinicHubSettingsSectionProps> = ({
  onSettingsChanged,
}) => {
  const [hubEnabled, setHubEnabled] = useState(false);
  const [serverUrl, setServerUrl] = useState('');
  const [socketUrl, setSocketUrl] = useState('');
  const [deviceId, setDeviceId] = useState('');
  const [deviceToken, setDeviceToken] = useState('');
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
    const [enabled, server, socket, id, token] = await Promise.all([
      StorageService.getHubEnabled(),
      StorageService.getHubServerUrl(),
      StorageService.getHubSocketUrl(),
      StorageService.getHubDeviceId(),
      getSecureHubToken(),
    ]);
    setHubEnabled(enabled);
    setServerUrl(server);
    setSocketUrl(socket);
    setDeviceId(id);
    setDeviceToken(token);
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
    // Sensible default: the clinic socket server rides the same host on :3003.
    // (Manual parse — RN's URL polyfill has no typed protocol/hostname.)
    if (!socketUrl && value.trim()) {
      const match = value.trim().match(/^(https?):\/\/([^/:]+)/);
      if (match) {
        const derived = `${match[1]}://${match[2]}:3003`;
        setSocketUrl(derived);
        await StorageService.saveHubSocketUrl(derived);
      }
    }
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

          <SettingsInput
            label="Server URL"
            value={serverUrl}
            onChangeText={handleServerUrlChange}
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
