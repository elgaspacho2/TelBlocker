# Bloqueur de télémétrie (Android)

Appli Android (sans root) qui bloque la télémétrie **appli par appli** : vous cochez les
applis à filtrer, et leurs requêtes DNS vers des domaines de pistage connus
(Firebase/Google Analytics, Crashlytics, AppsFlyer, Adjust, Sentry, Mixpanel…) reçoivent
une réponse « domaine inexistant ». Les autres applis ne sont pas touchées.

Compatible Android 10 (API 29) à Android 17.

## Fonctionnement

- L'appli crée un VPN **local** (rien ne sort du téléphone vers un serveur tiers) via
  `VpnService`, limité aux applis cochées (`addAllowedApplication`).
- Le VPN n'annonce qu'un faux serveur DNS et ne route que cette adresse : seules les
  requêtes DNS des applis choisies passent par l'appli, le reste de leur trafic utilise le
  réseau normal (pas de perte de débit).
- Domaine de télémétrie → réponse NXDOMAIN. Autre domaine → transmis au DNS du réseau
  (repli sur 1.1.1.1 / 9.9.9.9).
- Le **Journal** montre chaque requête, l'appli qui l'a faite et si elle a été bloquée.
  Touchez une ligne pour bloquer un domaine (ou ses sous-domaines) ou pour ajouter une
  exception si une appli ne fonctionne plus.
- L'écran **Domaines** permet d'ajouter vos propres domaines bloqués et des exceptions.

## Récupérer l'APK

Chaque push qui modifie `TelemetryBlocker/` lance le workflow GitHub Actions
« TelemetryBlocker APK ». Dans l'onglet *Actions* du dépôt, ouvrez le dernier run et
téléchargez l'artefact `TelemetryBlocker-apk` (zip contenant `app-debug.apk`).
Sur le téléphone, autorisez l'installation depuis votre navigateur / gestionnaire de fichiers.

Compiler soi-même (JDK 17 + Android SDK) :

```sh
cd TelemetryBlocker
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Limites à connaître

- Android n'autorise **qu'un seul VPN actif** : impossible de l'utiliser en même temps
  qu'un autre VPN.
- Si le **DNS privé** d'Android est réglé sur un nom d'hôte (Paramètres → Réseau →
  DNS privé), le filtrage peut être contourné : mettez-le sur « Automatique » ou
  « Désactivé ».
- Les applis qui font leur propre DNS chiffré (DoH, ex. certains navigateurs) ou qui
  contactent des adresses IP en dur ne sont pas filtrées.
- Certaines télémétries passent par le même domaine que des fonctions utiles
  (ex. `graph.facebook.com`) : elles ne sont pas dans la liste intégrée pour ne rien
  casser. Ajoutez-les vous-même si vous le souhaitez.
- Le journal est gardé en mémoire uniquement (effacé au redémarrage de l'appli).
- L'APK debug est signé avec une clé de debug différente à chaque build CI : pour mettre
  à jour, désinstallez d'abord l'ancienne version.
