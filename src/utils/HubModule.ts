/**
 * HubModule.ts
 * React Native bridge for the Dentrio clinic hub (native ClinicHubClient).
 *
 * The hub authenticates as the room's tablet against the clinic server,
 * keeps the watch summary fresh (REST + Socket.IO) and — in later stages —
 * relays it to the paired Wear OS watch. All networking lives in Kotlin;
 * JS only drives lifecycle and renders status (mirrors MqttModule).
 */

import { NativeModules, NativeEventEmitter, Platform } from 'react-native';
import { StorageService } from './storage';
import { getSecureHubToken } from './secureStorage';

const { HubModule } = NativeModules;

export interface HubConfig {
  serverUrl: string;
  socketUrl: string;
  deviceId: string;
  deviceToken: string;
  pollIntervalSec?: number;
  /**
   * Embedded REST API coordinates — when enabled the native hub self-reports
   * http://{localIp}:{port} (+ key) to the clinic (POST /api/tablet/report-status)
   * so the admin panel's "Manage" works without typing IPs.
   */
  restApiEnabled?: boolean;
  restApiPort?: number;
  restApiKey?: string | null;
}

export interface HubSessionCookie {
  name: string;
  value: string;
}

export interface HubSummary {
  room: { id: string; name: string; roomNumber: string | null } | null;
  conversationId: string | null;
  currentVisit: {
    appointmentId: string;
    patientName: string;
    doctorName: string;
    startTime: string;
    endTime: string;
    status: string;
    minutesOverrun: number;
  } | null;
  nextAppointment: {
    appointmentId: string;
    patientName: string;
    startTime: string;
    endTime: string;
    status: string;
    isWaiting: boolean;
    minutesWaiting: number | null;
  } | null;
  messages: unknown[];
  reason: 'no_room' | null;
}

class HubClientService {
  private eventEmitter: NativeEventEmitter | null = null;

  constructor() {
    if (Platform.OS === 'android' && HubModule) {
      this.eventEmitter = new NativeEventEmitter(HubModule);
    }
  }

  /**
   * Start the hub when enabled and fully configured. Called from
   * KioskScreen's init effect (same pattern as ApiService.autoStartMqtt).
   */
  async autoStart(): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    try {
      const enabled = await StorageService.getHubEnabled();
      if (!enabled) {
        return;
      }
      if (await this.isRunning()) {
        return;
      }
      const config = await this.loadConfig();
      if (!config) {
        console.warn('[Hub] Enabled but not fully configured — skipping autostart');
        return;
      }
      await this.start(config);
      console.log('[Hub] Autostarted for', config.serverUrl);
    } catch (error) {
      console.error('[Hub] Autostart failed:', error);
    }
  }

  /** Read the full config (AsyncStorage + Keychain); null when incomplete. */
  async loadConfig(): Promise<HubConfig | null> {
    const [serverUrl, socketUrl, deviceId, deviceToken] = await Promise.all([
      StorageService.getHubServerUrl(),
      StorageService.getHubSocketUrl(),
      StorageService.getHubDeviceId(),
      getSecureHubToken(),
    ]);
    if (!serverUrl || !socketUrl || !deviceId || !deviceToken) {
      return null;
    }
    // REST API coordinates ride along so the hub can self-report its address.
    const [restApiEnabled, restApiPort, restApiKey] = await Promise.all([
      StorageService.getRestApiEnabled(),
      StorageService.getRestApiPort(),
      StorageService.getRestApiKey(),
    ]);
    return {
      serverUrl,
      socketUrl,
      deviceId,
      deviceToken,
      restApiEnabled,
      restApiPort,
      restApiKey: restApiKey || null,
    };
  }

  async start(config: HubConfig): Promise<boolean> {
    if (Platform.OS !== 'android' || !HubModule) {
      throw new Error('HubModule is only available on Android');
    }
    return HubModule.startHub(config);
  }

  async stop(): Promise<boolean> {
    if (Platform.OS !== 'android' || !HubModule) {
      return false;
    }
    return HubModule.stopHub();
  }

  /** Restart with the currently persisted config (settings "save & apply"). */
  async restart(): Promise<void> {
    try {
      await this.stop();
    } catch {
      // not running — fine
    }
    const config = await this.loadConfig();
    if (config) {
      await this.start(config);
    }
  }

  async isRunning(): Promise<boolean> {
    if (Platform.OS !== 'android' || !HubModule) {
      return false;
    }
    return HubModule.isHubRunning();
  }

  /** Socket connectivity (REST may still work when false). */
  async isConnected(): Promise<boolean> {
    if (Platform.OS !== 'android' || !HubModule) {
      return false;
    }
    return HubModule.isHubConnected();
  }

  /** Last watch summary (or null) — instant status for the settings UI. */
  async getSummary(): Promise<HubSummary | null> {
    if (Platform.OS !== 'android' || !HubModule) {
      return null;
    }
    const json: string | null = await HubModule.getHubSummary();
    if (!json) {
      return null;
    }
    try {
      return JSON.parse(json) as HubSummary;
    } catch {
      return null;
    }
  }

  async sendQuickReply(content: string): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    await HubModule.sendQuickReply(content);
  }

  /** Resolves false during the server-side 30 s cooldown. */
  async sendHelpCall(note?: string): Promise<boolean> {
    if (Platform.OS !== 'android' || !HubModule) {
      return false;
    }
    return HubModule.sendHelpCall(note ?? null);
  }

  async toggleReaction(messageId: string, emoji: string): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    await HubModule.toggleReaction(messageId, emoji);
  }

  /**
   * NextAuth session cookie from the native cookie jar (or null before
   * login). Used to log the kiosk WebView in without exposing the
   * deviceToken to JS — only the derived session cookie crosses the bridge.
   */
  async getSessionCookie(): Promise<HubSessionCookie | null> {
    if (Platform.OS !== 'android' || !HubModule) {
      return null;
    }
    return HubModule.getSessionCookie();
  }

  /** Watch battery relayed from the Wear OS Data Layer (Etap B2). */
  async updateWatchBattery(level: number, charging: boolean): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    await HubModule.updateWatchBattery(level, charging);
  }

  /** Force a full self-report (endpoint + battery) — used after pairing. */
  async reportStatusNow(): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    await HubModule.reportStatusNow();
  }

  /** Best-effort clear of the reported endpoint + battery (unpair). */
  async reportUnpaired(): Promise<void> {
    if (Platform.OS !== 'android' || !HubModule) {
      return;
    }
    try {
      await HubModule.reportUnpaired();
    } catch {
      // hub not running — nothing reported, nothing to clear
    }
  }

  onConnectionChanged(callback: (connected: boolean) => void): () => void {
    if (!this.eventEmitter) {
      return () => {};
    }
    const subscription = this.eventEmitter.addListener(
      'onHubConnectionChanged',
      (event: { connected: boolean }) => callback(event.connected),
    );
    return () => subscription.remove();
  }

  onConnectionError(callback: (message: string) => void): () => void {
    if (!this.eventEmitter) {
      return () => {};
    }
    const subscription = this.eventEmitter.addListener(
      'onHubConnectionError',
      (event: { message: string }) => callback(event.message),
    );
    return () => subscription.remove();
  }

  onSummaryChanged(callback: (summary: HubSummary) => void): () => void {
    if (!this.eventEmitter) {
      return () => {};
    }
    const subscription = this.eventEmitter.addListener(
      'onHubSummaryChanged',
      (event: { summary: string }) => {
        try {
          callback(JSON.parse(event.summary) as HubSummary);
        } catch (error) {
          console.error('[Hub] Failed to parse summary event:', error);
        }
      },
    );
    return () => subscription.remove();
  }
}

// Export singleton instance
export const hubClient = new HubClientService();
