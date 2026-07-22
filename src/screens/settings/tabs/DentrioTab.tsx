/**
 * DenTRIO Tab — everything clinic-specific in one place: pairing status,
 * the clinic hub (watch relay) and unpair. The section component is
 * self-contained (fetches its own state, navigates to the Pairing screen),
 * so this tab is a thin host that keeps clinic settings out of Advanced.
 */

import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ClinicHubSettingsSection } from '../../../components/ClinicHubSettingsSection';
import UpdateService from '../../../utils/UpdateModule';
import { Colors, Spacing, Typography } from '../../../theme';

const DentrioTab: React.FC = () => {
  const [version, setVersion] = useState<string>('');

  useEffect(() => {
    UpdateService.getCurrentVersion()
      .then(info => setVersion(info.versionName))
      .catch(() => setVersion(''));
  }, []);

  return (
    <View>
      <ClinicHubSettingsSection />
      <Text style={styles.hint}>
        Pairing with the clinic (QR from the admin panel) configures the
        tablet, the watch hub and app updates automatically. App updates are
        served by the clinic portal after pairing.
      </Text>
      {version ? <Text style={styles.version}>DenTRIO v{version}</Text> : null}
    </View>
  );
};

const styles = StyleSheet.create({
  hint: {
    ...Typography.labelSmall,
    color: Colors.textSecondary,
    marginTop: Spacing.sm,
    marginHorizontal: Spacing.md,
  },
  version: {
    ...Typography.labelSmall,
    color: Colors.textSecondary,
    textAlign: 'center',
    marginTop: Spacing.md,
    marginBottom: Spacing.sm,
  },
});

export default DentrioTab;
