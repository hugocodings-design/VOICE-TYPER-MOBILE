# Voice Typer Mobile

La version Android de [Voice Typer](https://github.com/hugocodings-design/Voice-Typer) : une **bulle micro
flottante** par-dessus toutes vos applications. Touchez un champ de texte n'importe où (Gmail, WhatsApp,
Chrome, Notes…), touchez la bulle, parlez, touchez-la encore : le texte est transcrit **sur le téléphone**,
mis en forme selon le **préréglage de l'application**, puis écrit là où se trouve le curseur.

> Pourquoi Android seulement ? iOS n'autorise aucune application à dessiner par-dessus les autres ni à
> écrire dans leurs champs. Sur iPhone, la seule voie serait un clavier personnalisé (piste pour plus tard).

## Fonctions (v0.1)

- [x] Bulle flottante (service d'accessibilité) : apparaît quand vous écrivez, se déplace où vous voulez,
      ne prend jamais le focus (le curseur reste dans le champ)
- [x] Toucher = commencer / toucher = arrêter (dictée longue : la reconnaissance redémarre après chaque pause),
      ou arrêt automatique au silence
- [x] Transcription en direct au-dessus de la bulle, niveau du micro animé
- [x] **Reconnaissance sur l'appareil** (Android 12+), mode « Hors ligne seulement » par défaut ;
      l'application n'a **pas** la permission Internet
- [x] **Préréglages** (comme les préréglages d'écriture du PC) : Normal, Message rapide, Courriel
      (salutation avec le nom dicté, petits paragraphes, formule de politesse, signature), Notes en points,
      Brut ; créez les vôtres (style, langue, texte avant/après, couleur)
- [x] **Préréglages par application** : Gmail/Outlook → Courriel, WhatsApp/Messages/Teams → Message,
      Keep/OneNote → Notes… modifiables ; ou depuis la bulle : appui long → « Toujours dans … »
- [x] Appui long sur la bulle : choisir un autre préréglage pour cette dictée, recoller la dernière dictée,
      masquer la bulle dans cette app
- [x] Commandes vocales FR/EN reprises du PC (« à la ligne », « nouveau paragraphe », « virgule »,
      « ouvrez les guillemets »…) et **« efface ça »** dit seul pour effacer la dernière dictée
- [x] Dictionnaire de remplacements (« chat GPT » → « ChatGPT »)
- [x] Insertion intelligente : espace automatique, minuscule en milieu de phrase, remplace la sélection
- [x] Si un champ refuse l'écriture directe : collage par le presse-papiers (marqué « sensible »), sinon
      le texte reste copié
- [x] Français par défaut, English ou Auto ; changement rapide : appui long sur la bulle → Langue (badge FR/EN sur la bulle)
- [x] Android 13+ : une seule session de reconnaissance pendant toute la dictée (pas de mots perdus aux pauses)

## Feuille de route

1. **Whisper sur l'appareil** (whisper.cpp, modèles base/small) pour la même qualité qu'au PC, sans dépendre
   du service de reconnaissance de Google
2. **Réécriture IA locale** des préréglages (petit LLM sur l'appareil, comme Qwen au PC) au lieu des règles
3. Import des réglages exportés du PC (dictionnaire, préréglages, signature)
4. Clavier iOS / Android en complément de la bulle

## Installer

Chaque push sur `main` compile l'APK dans GitHub Actions. Pour chaque nouvelle version (`versionName` dans
`app/build.gradle.kts`), une **release** est publiée avec l'APK : ouvrez **Releases** depuis le téléphone et
touchez `VoiceTyperMobile-vX.Y.Z.apk`.

Sur le téléphone : ouvrez l'APK (autorisez « sources inconnues »), puis dans Voice Typer :
1. **Micro** → Autoriser
2. **Bulle de dictée** → Activer → Applications installées → Voice Typer → activer
   (Android affiche un avertissement standard pour tout service d'accessibilité)
3. **Reconnaissance hors ligne** → téléchargez le français (et l'anglais) si ce n'est pas déjà fait

> Android 13+ (« Accès refusé » / « Paramètre restreint ») bloque l'accessibilité des apps hors Play Store : Paramètres →
> Applications → Voice Typer → ⋮ → **Autoriser les paramètres restreints**, puis réactivez le service
> (bouton « Ouvrir les infos de Voice Typer » dans l'app). Samsung : si l'option manque, désactivez
> temporairement Sécurité et confidentialité → **Blocage automatique**.

## Développement

- `core/` : Kotlin pur (commandes vocales, dictionnaire, styles des préréglages, insertion, réglages JSON),
  testé sans Android : `./gradlew -PcoreOnly :core:test`
- `app/` : l'application Android (Kotlin, Jetpack Compose). Il faut le SDK Android (Android Studio) :
  `./gradlew :app:assembleDebug`

L'APK release est signé avec la clé de debug tant qu'une vraie clé n'est pas configurée.
