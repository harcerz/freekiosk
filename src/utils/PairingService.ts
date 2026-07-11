/**
 * PairingService.ts
 * One-scan clinic pairing for DenTRIO.
 *
 * The clinic admin panel renders a versioned QR
 * ({ v:1, token, deviceId, url, serverUrl, socketUrl, locale }) where `token`
 * is a ONE-TIME qrToken. The service exchanges it via
 * POST /api/tablets/verify-qr for the permanent device credentials and then
 * configures everything in one go: WebView kiosk URL, the native clinic hub
 * (which owns the tablet session), the embedded REST API (auto-generated key)
 * and the WebView session-cookie transplant so the tablet-mode page is logged
 * in without a second scan. The deviceToken lives only in the Keychain and
 * the native hub — it never reaches the WebView.
 *
 * Crash safety: the permanent credentials are persisted FIRST (the qrToken is
 * burned by verify-qr), then a pending-config flag is written and the
 * non-secret settings are applied; `resumePendingPairing()` re-applies the
 * flag's payload after a crash, so a half-paired device heals without a
 * fresh QR.
 */

import AsyncStorage from '@react-native-async-storage/async-storage';
import CookieManager from '@react-native-cookies/cookies';
import { StorageService } from './storage';
import {
  saveSecureHubToken,
  clearSecureHubToken,
  clearSecureApiKey,
} from './secureStorage';
import { hubClient } from './HubModule';
import { ApiService } from './ApiService';
import { httpServer } from './HttpServerModule';

const PAIRING_PENDING_KEY = '@kiosk_pairing_pending';
const DEFAULT_REST_PORT = 8080;
const COOKIE_WAIT_ATTEMPTS = 20;
const COOKIE_WAIT_DELAY_MS = 500;

export interface ClinicQrPayload {
  v: number;
  token: string;
  deviceId: string;
  serverUrl: string;
  socketUrl: string;
  locale: string;
}

/** Non-secret part of the pairing, persisted as the crash-recovery flag. */
interface PendingPairingConfig {
  serverUrl: string;
  socketUrl: string;
  deviceId: string;
  locale: string;
  label: string;
  restApiPort: number;
}

function stripTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

/** Same default the clinic uses: socket server on the base host, port 3003. */
function deriveSocketUrl(serverUrl: string): string {
  const match = serverUrl.match(/^(https?):\/\/([^/:\s]+)/);
  if (!match) {
    return serverUrl;
  }
  return `${match[1]}://${match[2]}:3003`;
}

/**
 * Parse a scanned code. Returns null when it is not a v1+ clinic pairing QR
 * (older QRs without `serverUrl` fall back to the manual setup path).
 */
export function parseClinicQr(raw: string): ClinicQrPayload | null {
  try {
    const data = JSON.parse(raw);
    if (
      typeof data !== 'object' ||
      data === null ||
      typeof data.v !== 'number' ||
      data.v < 1 ||
      typeof data.token !== 'string' ||
      !data.token ||
      typeof data.deviceId !== 'string' ||
      !data.deviceId ||
      typeof data.serverUrl !== 'string' ||
      !/^https?:\/\//.test(data.serverUrl)
    ) {
      return null;
    }
    const serverUrl = stripTrailingSlash(data.serverUrl.trim());
    const socketUrl =
      typeof data.socketUrl === 'string' && /^https?:\/\//.test(data.socketUrl)
        ? stripTrailingSlash(data.socketUrl.trim())
        : deriveSocketUrl(serverUrl);
    const locale =
      typeof data.locale === 'string' && /^[a-z]{2}$/.test(data.locale)
        ? data.locale
        : 'pl';
    return {
      v: data.v,
      token: data.token,
      deviceId: data.deviceId,
      serverUrl,
      socketUrl,
      locale,
    };
  } catch {
    return null;
  }
}

/** Random hex key for the embedded REST API (browser-safe, no Node crypto). */
function generateApiKey(): string {
  let key = '';
  for (let i = 0; i < 32; i++) {
    key += Math.floor(Math.random() * 16).toString(16);
  }
  return key;
}

async function verifyQrToken(
  serverUrl: string,
  token: string,
): Promise<{ deviceToken: string; deviceId: string; label: string }> {
  let response: Response;
  try {
    response = await fetch(`${serverUrl}/api/tablets/verify-qr`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
  } catch (error: any) {
    throw new Error(
      `Cannot reach the clinic server at ${serverUrl} (${error?.message ?? 'network error'})`,
    );
  }
  const body = await response.json().catch(() => ({}));
  if (!response.ok || !body?.credentials?.deviceToken) {
    const reason =
      body?.error ||
      (response.status === 401
        ? 'QR code is invalid or was already used — generate a new one in the admin panel'
        : `HTTP ${response.status}`);
    throw new Error(reason);
  }
  return {
    deviceToken: body.credentials.deviceToken,
    deviceId: body.credentials.deviceId,
    label: body.tablet?.deviceName || 'Clinic tablet',
  };
}

/** Apply the non-secret settings (idempotent — also used by crash recovery). */
async function applyPendingConfig(config: PendingPairingConfig): Promise<void> {
  await StorageService.saveUrl(
    `${config.serverUrl}/${config.locale}/tablet/waiting`,
  );
  await StorageService.saveDisplayMode('webview');
  await StorageService.saveHubEnabled(true);
  await StorageService.saveHubServerUrl(config.serverUrl);
  await StorageService.saveHubSocketUrl(config.socketUrl);
  await StorageService.saveHubDeviceId(config.deviceId);
  await StorageService.saveRestApiEnabled(true);
  await StorageService.saveRestApiPort(config.restApiPort);
  await StorageService.saveRestApiAllowControl(true);
  await StorageService.savePairedLabel(config.label);
}

/**
 * Wait for the native hub to log in, then copy its NextAuth session cookie
 * into the WebView cookie store. Safe to call repeatedly (e.g. on app start)
 * — it simply refreshes the cookie. Returns false when the hub never logged
 * in within the wait window (WebView then shows the normal login page).
 */
export async function transplantSessionCookie(
  serverUrl: string,
): Promise<boolean> {
  for (let attempt = 0; attempt < COOKIE_WAIT_ATTEMPTS; attempt++) {
    const cookie = await hubClient.getSessionCookie();
    if (cookie) {
      const secure =
        serverUrl.startsWith('https://') ||
        cookie.name.startsWith('__Secure-');
      await CookieManager.set(serverUrl, {
        name: cookie.name,
        value: cookie.value,
        path: '/',
        secure,
        httpOnly: true,
      });
      await CookieManager.flush();
      console.log('[Pairing] Session cookie transplanted into the WebView');
      return true;
    }
    await new Promise<void>(resolve =>
      setTimeout(() => resolve(), COOKIE_WAIT_DELAY_MS),
    );
  }
  console.warn('[Pairing] Hub session cookie not available — WebView stays logged out');
  return false;
}

/**
 * Full one-scan pairing. Throws with a human-readable message on failure;
 * the caller (PairingScreen) renders it and offers a retry with a fresh QR.
 */
export async function pairWithClinic(
  payload: ClinicQrPayload,
): Promise<{ label: string }> {
  // 1. Burn the one-time token for the permanent credentials.
  const { deviceToken, deviceId, label } = await verifyQrToken(
    payload.serverUrl,
    payload.token,
  );

  // 2. Secrets first — after this point a crash no longer needs a new QR.
  await saveSecureHubToken(deviceToken);
  await StorageService.saveRestApiKey(generateApiKey());

  // 3. Pending flag + non-secret config (recovered by resumePendingPairing).
  const pending: PendingPairingConfig = {
    serverUrl: payload.serverUrl,
    socketUrl: payload.socketUrl,
    deviceId,
    locale: payload.locale,
    label,
    restApiPort: DEFAULT_REST_PORT,
  };
  await AsyncStorage.setItem(PAIRING_PENDING_KEY, JSON.stringify(pending));
  await applyPendingConfig(pending);

  // 4. Bring everything up: hub (owns the session), REST API, WebView cookie.
  await hubClient.restart();
  await ApiService.autoStart();
  await transplantSessionCookie(payload.serverUrl);
  await hubClient.reportStatusNow();

  await AsyncStorage.removeItem(PAIRING_PENDING_KEY);
  return { label };
}

/**
 * Crash recovery: when a pending pairing flag exists (app died between
 * persisting secrets and finishing the config), re-apply it without a new
 * QR. Call once from KioskScreen startup, BEFORE the hub autostart.
 */
export async function resumePendingPairing(): Promise<boolean> {
  try {
    const raw = await AsyncStorage.getItem(PAIRING_PENDING_KEY);
    if (!raw) {
      return false;
    }
    const pending = JSON.parse(raw) as PendingPairingConfig;
    console.log('[Pairing] Resuming interrupted pairing for', pending.label);
    await applyPendingConfig(pending);
    await AsyncStorage.removeItem(PAIRING_PENDING_KEY);
    return true;
  } catch (error) {
    console.error('[Pairing] Failed to resume pending pairing:', error);
    return false;
  }
}

/** True when the tablet has been paired with a clinic (hub configured). */
export async function isPaired(): Promise<boolean> {
  const [enabled, deviceId] = await Promise.all([
    StorageService.getHubEnabled(),
    StorageService.getHubDeviceId(),
  ]);
  return enabled && !!deviceId;
}

/**
 * Undo the pairing: best-effort clear on the server, stop hub + REST API,
 * wipe secrets and clinic settings, drop WebView cookies. Re-pairing simply
 * scans a freshly generated QR.
 */
export async function unpair(): Promise<void> {
  await hubClient.reportUnpaired();
  try {
    await hubClient.stop();
  } catch {
    // not running
  }
  try {
    await httpServer.stopServer();
  } catch {
    // not running
  }
  await clearSecureHubToken();
  await clearSecureApiKey();
  await StorageService.saveHubEnabled(false);
  await StorageService.saveHubServerUrl('');
  await StorageService.saveHubSocketUrl('');
  await StorageService.saveHubDeviceId('');
  await StorageService.saveRestApiEnabled(false);
  await StorageService.savePairedLabel('');
  await StorageService.saveUrl('');
  await AsyncStorage.removeItem(PAIRING_PENDING_KEY);
  try {
    await CookieManager.clearAll(true);
  } catch {
    // cookie wipe is cosmetic — ignore
  }
  console.log('[Pairing] Tablet unpaired from the clinic');
}
