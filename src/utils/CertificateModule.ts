import { NativeModules } from 'react-native';

export interface CertificateInfo {
  fingerprint: string;
  url: string;
  expiryTime: number;
  expiryDate: string;
  isExpired: boolean;
}

/** Result of a no-trust TLS probe (see native fetchServerCertificate). */
export interface ServerCertificateInfo {
  /** True when the platform trusts the chain OR the leaf was user-accepted. */
  trusted: boolean;
  fingerprint?: string;
  fingerprintFormatted?: string;
  subject?: string;
  issuer?: string;
  validUntil?: string;
}

interface ICertificateModule {
  clearAcceptedCertificates(): Promise<boolean>;
  getAcceptedCertificates(): Promise<CertificateInfo[]>;
  removeCertificate(fingerprint: string): Promise<boolean>;
  fetchServerCertificate(url: string): Promise<ServerCertificateInfo>;
  acceptCertificate(fingerprint: string, url: string): Promise<boolean>;
}

const { CertificateModule } = NativeModules;

export default CertificateModule as ICertificateModule;
