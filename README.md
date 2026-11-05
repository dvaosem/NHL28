# NHL28 (Android)

Tenký WebView obal okolo https://dvaosem.com/nhl/ . Web sa aktualizuje cez hosting, APK sa prebuduje len keď sa mení obal.

## Inštalácia
Stiahni najnovší APK: https://github.com/dvaosem/nhl28/releases/latest/download/nhl28.apk
Prvá inštalácia je manuálna (povoliť inštaláciu z neznámych zdrojov). Ďalšie updaty ponúkne appka sama (GitHub Releases).

## Build
Každý push do `main` spustí GitHub Actions, ktorý postaví APK a zverejní release `build-N`.
`versionCode` = číslo behu CI. Zmeny len v `README.md` a `docs/` build nespúšťajú.

## Podpis
`app/debug.keystore` je commitnutý schválne: každý build má rovnaký podpis, takže sa inštaluje cez starý.
