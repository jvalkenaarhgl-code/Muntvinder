# Muntvinder 2.0 – automatische Nederlandse proofset-zoeker

**Versie:** 2.1 technisch bouwproject (niet vooraf gebouwde APK). Dit is een nieuwe, zelfstandige Android-app met cloudscanner; de geüploade APK is alleen gebruikt om het oude gedrag te onderzoeken. De bestaande APK is niet rechtstreeks gewijzigd of opnieuw ondertekend.

## Wat doet de scanner?

- Marktplaats: Nederlandse **losse proofsets én proofsetbundels**, ook guldensets. Combineert zoekresultaten op **laagste prijs** en **nieuwste** advertentie. Geen maximum jaartal of verplichte minimale winst.
- Catawiki: **uitsluitend bundels** met Nederlandse proofsets; losse veilingen worden genegeerd.
- Toont echte advertentietitel, vraagprijs of huidig bod, link, setjaren, indicatieve totale inkoop, verwachte Marktplaats-doorverkoopprijs en mogelijke marge.
- Vergelijking per exact jaar en type (gulden/euro), alleen met **vaste Marktplaats-vraagprijzen** van vergelijkbare losse proofsets. Voor de indicatie wordt expliciet de **aanname 80–95% van de mediane vraagprijs** gebruikt. Dit is géén bewezen verkoopprijs. Bij minder dan 3 vergelijkingen per set wordt **'onvoldoende vergelijkingen'** getoond, nooit een verzonnen prijs.
- Bundels worden waar mogelijk opgeteld uit losse setwaardes. Speciale edities, incomplete bundels en onduidelijke lotbeschrijvingen worden **niet** als gewone sets gewaardeerd.
- Marktplaats-advertenties met `MIN_BID`/`FAST_BID` zijn een **biedprijs** en geen vaste koopprijs. Catawiki-startbiedingen worden nooit als huidig bod gepresenteerd.
- Catawiki totaalprijs = huidig bod + 9% kopersbescherming + € 3 + **aangenomen € 12 verzendkosten** (zelf aanpassen). Het daadwerkelijke winnende bod en de verzendkosten moeten bij Catawiki worden gecontroleerd.
- De volgorde geeft prioriteit aan het gunstigste verschil tegenover vergelijkbare vraagprijzen. **Geen minimale nettowinst, ROI of jaartalfilter.** Bij gebrek aan vergelijkingen verschijnen goedkopere aanbiedingen met waarschuwing.
- Favorieten en advertentielinks in Android-app. **Geen Telegram en standaard géén pushmeldingen**.

## Zo laat je het automatisch draaien

**Je hebt een GitHub-account nodig.** De scanner draait niet in ChatGPT en draait ook niet zelfstandig in de APK als er geen feed beschikbaar is.

1. Maak op GitHub een nieuwe, bij voorkeur **publieke**, repository, bijvoorbeeld `muntvinder`.
2. Zet de bestanden in deze ZIP in de root van die repository (inclusief `.github/workflows/scan.yml`, `scan/` en `docs/feed.json`).
3. Ga naar **Settings → Pages → Build and deployment → Source: GitHub Actions**. GitHub Pages moet actief zijn voor de eerste scan.
4. Activeer **Actions** en ga naar `Zoek Nederlandse proofsets` → **Run workflow**. De scan publiceert `docs/feed.json` direct via GitHub Pages. Daarna wordt ongeveer elke 30 minuten gescand (de GitHub-scheduler kan vertraagd starten). De publieke feed wordt `https://JOUW-GEBRUIKERSNAAM.github.io/muntvinder/feed.json` (pas de repositorienaam aan indien anders).
5. Ga naar **Actions → Bouw Android APK → Run workflow**. Wacht tot de build in GitHub gereed is en download de workflow-artifact **Muntvinder-v2-APK**. Daarin zit de installeerbare `app-debug.apk`. Je hebt dus zelf geen Android Studio nodig. Als alternatief kan een ontwikkelaar de `android`-map lokaal met Android Studio, SDK 35 en JDK 17 bouwen. De bron-ZIP bevat nog geen vooraf gebouwde APK.
6. Pak de gedownloade GitHub-artifact uit en installeer de geproduceerde APK op je Android-telefoon. Dit is `nl.muntvinder.v2` en kan **naast** de oude APK blijven staan, zodat de originele installatie niet verloren gaat.
7. Open Muntvinder v2 en druk op **Verversen**. **Geen feed-URL invoeren:** de APK is bij deze GitHub-bouw automatisch voorzien van de URL naar je eigen gepubliceerde scan-feed. De app leest die ongeveer elke 30 minuten wanneer Android dat toestaat; het echte zoeken gebeurt op GitHub Actions.

### Als je een stabielere Marktplaats-bron wilt

De standaard gebruikte website-zoekendpoint `www.marktplaats.nl/lrp/api/search` is **geen officieel ondersteunde API** en kan worden geblokkeerd of veranderen. Optioneel kan de scanner `REEF_API_KEY` gebruiken voor een commerciële zoekdata-API. Zet de sleutel in **GitHub → Settings → Secrets and variables → Actions → New repository secret** met naam `REEF_API_KEY` (de sleutel komt **nooit** in de APK of publieke JSON). Controleer tarieven en toegestane toepassingen vooraf.

Catawiki wordt gelezen via de publiek zichtbare categorie- en veilingpagina's en kan veranderen of verzoeken blokkeren. Houd rekening met gebruiksvoorwaarden en pas de frequentie zo nodig aan. Er wordt nooit een captcha of inlogbeveiliging omzeild.

### Fouten en betrouwbaarheid

- De app kan zonder koppeling nog geen actuele resultaten tonen. `docs/feed.json` begint daarom met een **lege lijst**; er zijn geen verzonnen aanbiedingen.
- Als een bron niet reageert, wordt een foutmelding gepubliceerd en staan gevonden aanbiedingen expliciet als *niet volledig actueel*. Als beide bronnen falen, blijven eventueel oudere resultaten zichtbaar, duidelijk als verouderd.
- De ingestelde 30-minuten-cyclus is geen garantie dat Android/GitHub exact op dat tijdstip draait.
- Vraagprijzen zijn **geen gerealiseerde verkoopprijzen**; winstberekeningen zijn indicatief, zonder kosten om de spullen vervolgens door te verkopen. Een aanbieder kan een advertentie intrekken of de prijs veranderen.
- Een advertentie kan onvolledig zijn, meerdere munten i.p.v. complete sets bevatten of verkocht zijn. Altijd de advertentie en foto's controleren.
- De oude Muntvinder-meldingendatabase en oude favorieten worden niet automatisch naar v2 overgezet. Deze versie gebruikt een eigen, nieuwe app-installatie.

## Testen

```
python -m pip install -r scan/requirements.txt pytest
python -m pytest scan/tests -q
python scan/scanner.py
```

Alle 8 unit tests op lokale, fictieve *testfixtures* zijn uitgevoerd en geslaagd. **Geen live website-toegang of Android APK-compilatie gevalideerd** in de bouwomgeving; eventuele nieuwe platform- of parserschommelingen zijn daardoor nog mogelijk.

## Bestanden

- `scan/scanner.py`: bronnen, filterbeleid, vergelijking, marge, foutmeldingen
- `.github/workflows/scan.yml`: geautomatiseerde cloudscan om de 30 minuten
- `.github/workflows/build-apk.yml`: bouwt een installeerbare Android-debug-APK via GitHub Actions
- `docs/feed.json`: initiële lege feed, automatisch gevuld na succesvolle scan
- `android/`: native Android Studio-app, met achtergrondverversing, favorieten, URL-instellingen en koopkansenoverzicht

Bronverwijzingen voor technische keuzes: Marktplaats API-documentatie `https://api.marktplaats.nl/docs/v2/` (toegang via OAuth); observaties niet-officiële website-zoekendpoint `https://github.com/jasp-nerd/marktplaats-monitor/blob/master/docs/marktplaats-api.md`; Catawiki koperskosten `https://www.catawiki.com/nl/help/fees/how-does-the-buyer-protection-fee-work`.
