# TelBlocker

[English](README.md) · **Français**

**Bloqueur de télémétrie appli par appli pour Android, sans root.**

TelBlocker vous laisse choisir, appli par appli, ce qui a le droit d'envoyer des données.
Vous pouvez bloquer seulement le pistage et les statistiques d'une appli, ou lui couper
complètement le réseau. Un seul interrupteur suffit pour Google Play Services et le
Play Store.

Fonctionne d'Android 10 (API 29) à Android 17.

## Fonctionnalités

- **Un mode par appli.** Touchez une appli et choisissez l'un des trois modes :
  - *Aucun filtrage* : l'appli n'est pas touchée.
  - *Télémétrie* : les requêtes vers les domaines de pistage et de statistiques connus
    sont bloquées, tout le reste fonctionne normalement.
  - *Tout bloquer* : l'appli n'a plus aucun accès réseau, ni en sortie ni en entrée.
- **Un interrupteur pour les services Google.** Il met d'un coup Google Play Services,
  Google Services Framework et le Play Store en mode *Tout bloquer*.
- **Journal en direct.** Il affiche chaque requête DNS et chaque connexion bloquée, avec
  l'appli qui l'a faite. Touchez une ligne pour bloquer ce domaine, ou pour l'autoriser
  si une appli ne fonctionne plus.
- **Règles personnalisées.** Vous pouvez ajouter vos propres domaines bloqués et des
  exceptions. Les sous-domaines sont couverts automatiquement.
- **Liste intégrée.** Elle couvre Firebase / Google Analytics, Crashlytics, AppsFlyer,
  Adjust, Sentry, Mixpanel, Amplitude, Microsoft, Unity, Xiaomi, Huawei et d'autres.
- **Respect de la vie privée.** Tout se passe sur le téléphone. Pas de compte, pas de
  serveur, et aucune statistique collectée par l'appli elle-même.

## Fonctionnement

TelBlocker utilise le `VpnService` d'Android pour créer un VPN **local**. Aucun trafic
n'est envoyé vers un serveur distant. Le VPN ne s'applique qu'aux applis que vous avez
choisies.

- **Mode Télémétrie.** Le VPN ne capture que le DNS. Les requêtes vers des domaines de
  télémétrie connus reçoivent la réponse « ce domaine n'existe pas ». Les autres requêtes
  partent vers votre serveur DNS habituel, et le reste du trafic de l'appli ne passe pas
  du tout par TelBlocker.
- **Mode Tout bloquer.** Toutes les requêtes DNS de l'appli échouent. Si *toutes* les
  applis filtrées sont dans ce mode, TelBlocker capture et jette aussi tout leur trafic,
  y compris les connexions directes vers des adresses IP.

## Installation

1. Allez dans l'onglet [Actions](../../actions), ouvrez le dernier run réussi
   **TelBlocker APK** et téléchargez l'artefact `TelBlocker-apk`.
2. Dézippez-le et copiez `app-debug.apk` sur votre téléphone.
3. Ouvrez l'APK et autorisez l'installation depuis votre navigateur ou votre
   gestionnaire de fichiers quand Android le demande.
4. Dans l'appli, choisissez un mode pour les applis voulues, touchez
   **Activer la protection** et acceptez la demande de VPN d'Android.

### Compiler soi-même

Prérequis : JDK 17 et le SDK Android.

```sh
cd TelBlocker
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Limites

- **Un seul VPN à la fois.** Android n'autorise qu'un VPN actif : TelBlocker ne peut pas
  tourner en même temps qu'une autre appli VPN.
- **Mélanger les modes affaiblit le blocage total.** Si certaines applis sont en mode
  *Télémétrie* et d'autres en *Tout bloquer*, les applis bloquées ne sont coupées que
  via le DNS. Une connexion vers une adresse IP en dur, ou ouverte avant l'activation de
  la protection, peut encore passer. Pour un blocage complet, n'utilisez que le mode
  *Tout bloquer*.
- **DNS privé.** Si le DNS privé d'Android est réglé sur un nom d'hôte précis, le
  filtrage peut être contourné. Réglez-le sur *Automatique* ou *Désactivé*.
- **DNS chiffré propre à une appli.** Les applis qui utilisent leur propre DNS-over-HTTPS
  (certains navigateurs, par exemple) ne sont pas filtrées en mode *Télémétrie*.
- **Couper les services Google a des conséquences.** Cela désactive les notifications
  push de beaucoup d'applis, les téléchargements et mises à jour du Play Store, la
  connexion avec Google, Google Pay/Wallet, les sauvegardes, Localiser mon appareil et
  la localisation rapide.
- **Versions de debug.** Les builds de la CI sont signés avec une clé de debug
  temporaire : désinstallez l'ancienne version avant d'installer la nouvelle.

## Organisation du projet

```
TelBlocker/                       Appli Android (Kotlin, sans dépendance externe)
  app/src/main/java/...           Service VPN, traitement des paquets et du DNS, écrans
  app/src/main/assets/            Liste intégrée des domaines de télémétrie
.github/workflows/                Build CI qui produit l'APK
```

## Licence

[GPL-3.0](LICENSE)
