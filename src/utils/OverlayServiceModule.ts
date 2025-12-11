import { NativeModules } from 'react-native';

interface OverlayServiceModuleType {
  startOverlayService(): Promise<boolean>;
  stopOverlayService(): Promise<boolean>;
}

const OverlayServiceModule: OverlayServiceModuleType = NativeModules.OverlayServiceModule;

export default OverlayServiceModule;
