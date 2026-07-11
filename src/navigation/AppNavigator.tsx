import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import KioskScreen from '../screens/KioskScreen';
import PinScreen from '../screens/PinScreen';
import PairingScreen from '../screens/PairingScreen';
// v1.2: Use new settings screen with Material tabs
import { SettingsScreen } from '../screens/settings';
import BlockingOverlaysScreen from '../screens/settings/BlockingOverlaysScreen';

export type RootStackParamList = {
  Kiosk: undefined;
  Pin: undefined;
  Pairing: undefined;
  Settings: undefined;
  BlockingOverlays: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

const AppNavigator: React.FC = () => {
  return (
    <NavigationContainer>
      <Stack.Navigator
        initialRouteName="Kiosk"
        screenOptions={{
          headerShown: false,
        }}
      >
        <Stack.Screen 
          name="Kiosk" 
          component={KioskScreen}
          options={{
            headerShown: false,
          }}
        />
        <Stack.Screen
          name="Pin"
          component={PinScreen}
          options={{
            headerShown: false,
            gestureEnabled: false,
          }}
        />
        <Stack.Screen
          name="Pairing"
          component={PairingScreen}
          options={{
            headerShown: false,
          }}
        />
        <Stack.Screen 
          name="Settings" 
          component={SettingsScreen}
          options={{
            headerShown: false, // v1.2: Custom header with tabs
            gestureEnabled: false,
          }}
        />
        <Stack.Screen 
          name="BlockingOverlays" 
          component={BlockingOverlaysScreen}
          options={{
            headerShown: false,
          }}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
};

export default AppNavigator;
