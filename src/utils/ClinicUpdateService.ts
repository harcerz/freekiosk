/**
 * ClinicUpdateService.ts
 * Self-update from the clinic portal (custom update server, set automatically
 * during QR pairing — see PairingService). Used by the Settings UI and by the
 * remote `updateCheck` command (clinic panel → embedded REST API →
 * POST /api/update/check), so the whole flow must work headless.
 *
 * Install semantics: silent under Device Owner, otherwise Android shows the
 * system install prompt on the tablet (one tap).
 */

import { StorageService } from './storage';
import UpdateService from './UpdateModule';

export type ClinicUpdateResult =
  | { status: 'no-server' }
  | { status: 'up-to-date'; current: string; latest: string }
  | { status: 'started'; current: string; latest: string };

/** Numeric segment-wise compare: 1 when a > b, -1 when a < b, 0 when equal. */
export function compareAppVersions(a: string, b: string): number {
  const parse = (v: string) =>
    v
      .replace(/^v/, '')
      .split('.')
      .map(part => parseInt(part, 10) || 0);
  const pa = parse(a);
  const pb = parse(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const diff = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (diff !== 0) {
      return diff > 0 ? 1 : -1;
    }
  }
  return 0;
}

/**
 * Check the clinic portal manifest and start download + install when a newer
 * version is available. Throws with a readable message on network/manifest
 * errors (surfaced in logs for the remote path, in the UI for settings).
 */
export async function checkAndInstallClinicUpdate(): Promise<ClinicUpdateResult> {
  const manifestUrl = await StorageService.getCustomUpdateUrl();
  if (!manifestUrl) {
    return { status: 'no-server' };
  }
  const [{ versionName }, info] = await Promise.all([
    UpdateService.getCurrentVersion(),
    UpdateService.checkForUpdatesFromUrl(manifestUrl),
  ]);
  if (compareAppVersions(info.version, versionName) <= 0) {
    console.log(
      `[ClinicUpdate] Up to date (installed ${versionName}, portal ${info.version})`,
    );
    return { status: 'up-to-date', current: versionName, latest: info.version };
  }
  console.log(
    `[ClinicUpdate] Updating ${versionName} → ${info.version} from ${info.downloadUrl}`,
  );
  await UpdateService.downloadAndInstall(info.downloadUrl, info.version);
  return { status: 'started', current: versionName, latest: info.version };
}
