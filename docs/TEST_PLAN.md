# 🧪 FreeKiosk - Plan de Test Release

## 📋 Prérequis

### Matériel
- [ ] Tablette/Téléphone Android (API 24+)
- [ ] Câble USB pour ADB
- [ ] PC avec ADB installé

### APK
- [ ] APK release signé installé
- [ ] Numéro de version : ___________

### Comptes de test
- [ ] URL de test pour mode WebView
- [ ] Application tierce installée pour mode External App

---

## 🔧 Configuration Device Owner

### Activer Device Owner
```bash
adb shell dpm set-device-owner com.freekiosk/.DeviceAdminReceiver
```

### Désactiver Device Owner
```bash
adb shell dpm remove-active-admin com.freekiosk/.DeviceAdminReceiver
```

### Vérifier le statut
```bash
adb shell dumpsys device_policy | grep "Device Owner"
```

---

## 📱 TESTS MODE DEVICE OWNER

### 1. Installation & Configuration initiale

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Installation APK | Installer APK via ADB | Installation réussie | ✅ |
| Activation Device Owner | Exécuter commande ADB | "Success" affiché | ✅ |
| Premier lancement | Ouvrir FreeKiosk | Écran Kiosk affiché | ✅ |
| Accès paramètres | 5 taps coin bas-droit | Écran PIN affiché | ✅ |
| PIN par défaut | Entrer "1234" | Accès paramètres | ✅ |

### 2. Mode WebView - Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Configuration URL | Entrer URL valide | URL acceptée | |
| Affichage WebView | Sauvegarder & retour | Page web affichée | |
| Navigation bloquée | Appuyer Home/Récents | Aucune réaction | |
| Bouton Retour | Appuyer bouton retour | Aucune réaction | |
| Barre de navigation | Swipe depuis le bas | Barre cachée/inaccessible | |
| Notifications | Swipe depuis le haut | Notifications bloquées | |
| Auto-reload | Activer + tester | Page rechargée après délai | |
| Certificat SSL custom | Visiter site HTTPS self-signed | Dialogue acceptation affiché | |
| Login popup | Tester site avec login popup | Login fonctionne | |

### 3. Screensaver - Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Activation screensaver | Activer dans paramètres | Option activée | |
| Délai inactivité | Attendre délai configuré | Écran noir affiché | |
| Luminosité screensaver | Vérifier luminosité | Luminosité réduite | |
| Désactivation par tap | Toucher écran | Screensaver désactivé | |
| Motion detection | Activer + bouger devant caméra | Screensaver désactivé | |

### 4. Mode External App - Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Sélection mode | Choisir "Android App" | Mode sélectionné | |
| Pas de warning DO | Vérifier absence warning rouge | Pas d'avertissement Device Owner | |
| Sélection app | Choisir app dans liste | App sélectionnée | |
| Permission overlay | Accorder permission | Permission accordée | |
| Lancement app | Sauvegarder & retour | App externe lancée | |
| Bouton overlay visible | Vérifier coin bas-droit | Bouton ↩ visible | |
| Navigation bloquée | Appuyer Home/Récents | Aucune réaction | |
| Barre navigation | Swipe depuis le bas | Barre cachée/inaccessible | |
| Retour FreeKiosk | 5 taps sur bouton overlay | Retour à FreeKiosk | |
| Overlay disparaît | Vérifier après retour | Bouton overlay disparu | |
| Pas d'auto-relaunch | Attendre 5 secondes | App PAS relancée (retour volontaire) | |
| Accès paramètres | 5 taps coin + PIN | Paramètres accessibles | |
| Re-lancement app | Retourner au kiosk | App externe relancée | |
| Overlay réapparaît | Vérifier coin | Bouton overlay présent | |

### 5. Auto-relaunch External App - Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Activer auto-relaunch | Activer dans paramètres | Option activée | |
| Simuler crash | Force-stop app via ADB | App relancée après 2s | |
| Limite 3 tentatives | Répéter crash 4x | Arrêt après 3 tentatives | |

### 6. Sécurité - Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| PIN incorrect | Entrer mauvais PIN 3x | Message erreur | |
| Changement PIN | Changer PIN | Nouveau PIN fonctionne | |
| Sortie kiosk | Bouton "Exit Kiosk Mode" | Sortie après confirmation | |
| Redémarrage | Redémarrer appareil | FreeKiosk relancé auto | |

---

## 📱 TESTS MODE NON-DEVICE OWNER (Screen Pinning)

### 1. Installation & Configuration

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Installation APK | Installer APK | Installation réussie | ✅ |
| Vérifier pas DO | Vérifier dans paramètres | Indicateur "Non Device Owner" | ✅ |
| Premier lancement | Ouvrir FreeKiosk | Écran Kiosk affiché | ✅ |
| Screen Pinning prompt | Activer Lock Mode | Demande confirmation utilisateur | ❌ Se PIN direct (mais c'est bien)|

### 2. Mode WebView - Non-Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Configuration URL | Entrer URL valide | URL acceptée | ✅ |
| Affichage WebView | Sauvegarder & retour | Page web affichée | ✅ |
| Screen Pinning actif | Vérifier status bar | Indicateur pinning visible | ✅ |
| Home bloqué | Appuyer Home | Notification "App is pinned" | ✅ |
| Sortie pinning | Maintenir Back+Récents | Sortie du pinning possible | ✅ |

### 3. Mode External App - Non-Device Owner

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Sélection mode | Choisir "Android App" | Mode sélectionné | ✅ |
| Warning affiché | Vérifier warning rouge | ⚠️ "Device Owner Recommended" visible | ✅ |
| Permission overlay | Accorder permission | Permission accordée | ✅ |
| Lancement app | Sauvegarder & retour | App externe lancée | ✅ |
| Bouton overlay visible | Vérifier coin bas-droit | Bouton ↩ visible | ✅ |
| Home accessible | Appuyer Home | ⚠️ Retour au launcher (comportement attendu) | ✅ |
| Récents accessible | Appuyer Récents | ⚠️ Apps récentes visibles (comportement attendu) | ✅ |
| Retour via overlay | 5 taps sur bouton | Retour à FreeKiosk | ✅ |

### 4. Limitations documentées - Non-Device Owner

| Limitation | Vérifié | Notes |
|------------|---------|-------|
| Navigation système accessible en mode External App | ✅ | Comportement attendu |
| Screen Pinning uniquement sur FreeKiosk | ✅ | |
| Confirmation utilisateur requise pour pinning | ❌ | Mais c'est très bien comme ça |

---

## 🔄 Tests de régression

| Test | Étapes | Résultat attendu | ✅/❌ |
|------|--------|------------------|-------|
| Mise à jour APK | Installer nouvelle version par-dessus | Paramètres conservés | ✅ |
| Rotation écran | Tourner appareil | Interface stable | ✅ |
| Batterie faible | Simuler batterie <15% | Pas de popup système | |
| Connexion perdue | Désactiver WiFi | Message erreur WebView | ✅ |
| Reconnexion | Réactiver WiFi | Page rechargée | ✅(Uniquement quand rechargement activé) |

---

## 📊 Résumé des tests

### Device Owner
- Total tests : ___
- Réussis : ___
- Échoués : ___

### Non-Device Owner
- Total tests : ___
- Réussis : ___
- Échoués : ___

---

## 🐛 Bugs trouvés

| # | Description | Sévérité | Statut |
|---|-------------|----------|--------|
| 1 | | | |
| 2 | | | |
| 3 | | | |

---

## ✅ Validation Release

- [ ] Tous les tests Device Owner passent
- [ ] Tous les tests Non-Device Owner passent (avec limitations documentées)
- [ ] Pas de bugs critiques
- [ ] Documentation à jour
- [ ] APK signé et prêt

**Testeur :** _______________  
**Date :** _______________  
**Version :** _______________  
**Approuvé pour release :** ☐ Oui ☐ Non
