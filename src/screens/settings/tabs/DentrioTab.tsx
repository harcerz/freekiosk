/**
 * DenTRIO Tab — everything clinic-specific in one place: pairing status,
 * the clinic hub (watch relay) and unpair. The section component is
 * self-contained (fetches its own state, navigates to the Pairing screen),
 * so this tab is a thin host that keeps clinic settings out of Advanced.
 */

import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { ClinicHubSettingsSection } from '../../../components/ClinicHubSettingsSection';
import { Colors, Spacing, Typography } from '../../../theme';

const DentrioTab: React.FC = () => {
  return (
    <View>
      <ClinicHubSettingsSection />
      <Text style={styles.hint}>
        Pairing with the clinic (QR from the admin panel) configures the
        tablet, the watch hub and app updates automatically. App updates are
        served by the clinic portal after pairing.
      </Text>
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
});

export default DentrioTab;
