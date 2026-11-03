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

## Installeren en automatisch laten zoeken

De broncode en automatische workflows zijn ingericht in de repository
`jvalkenaarhgl-code/Muntvinder`. Er zijn geen Codex-credits nodig.

1. Ga naar **Actions → Bouw Android APK** voor nieuwe builds. De meest recente geslaagde run heeft de downloadbare artifact **Muntvinder-v2-APK** met `app-debug.apk`.
2. Installeer de APK op je Android-telefoon. De applicatie gebruikt de pakketnaam `nl.muntvinder.v2` en kan naast de oude Muntvinder blijven staan.
3. Open de app en druk op **Verversen**. De standaard feed-URL is automatisch ingebouwd:
   `https://raw.githubusercontent.com/jvalkenaarhgl-code/Muntvinder/main/docs/feed.json`.
4. De GitHub-workflow **Zoek Nederlandse proofsets** draait ongeveer elke 30 minuten. GitHub kan geplande runs vertragen. De workflow schrijft de nieuwste resultaten in `docs/feed.json` op de standaardbranch. **GitHub Pages is niet nodig.**
5. Bekijk de JSON-feed in `docs/feed.json`. Onbekende doorverkoopprijzen worden niet verzonnen; bij één of twee vergelijkingen wordt de berekende prijs duidelijk als **zeer onzeker** gemarkeerd.

**Huidige beperkingen (3 november 2026):** Marktplaats leverde 84 zichtbare resultaten in de eerste succesvolle scan. Catawiki gaf HTTP 403 en kon daarom nog geen bundels leveren. De openbare Marktplaats-zoekendpoint is niet officieel ondersteund en kan eveneens wijzigen. Een succesvolle build bewijst niet dat live databronnen blijvend werken.

### Als je een stabielere Marktplaats-bron wilt

De standaard gebruikte website-zoekendpoint `www.marktplaats.nl/lrp/api/search` is **geen officieel ondersteunde API** en kan worden geblokkeerd of veranderen. Optioneel kan de scanner `REEF_API_KEY` gebruiken voor een commerciële zoekdata-API. Zet de sleutel in **GitHub → Settings → Secrets and variables → Actions → New repository secret** met naam `REEF_API_KEY` (de sleutel komt **nooit** in de APK of publieke JSON). Controleer tarieven en toegestane toepassingen vooraf.

Catawiki wordt gelezen via de publiek zichtbare categorie- en veilingpagina's en kan veranderen of verzoeken blokkeren. Houd rekening met gebruiksvoorwaarden en pas de frequentie zo nodig aan. Er wordt nooit een captcha of inlogbeveiliging omzeild.

### Fouten en betrouwbaarheid

- De app leest de publieke GitHub-feed. Bij de start is de feed leeg; een geslaagde scanner-run vult deze uitsluitend met gevonden advertenties.
- Als één bron niet reageert, verschijnt een foutmelding en een status *gedeeltelijk*. De wel gevonden advertenties zijn vers opgehaald. Als beide bronnen falen, blijven eventueel oudere resultaten zichtbaar, duidelijk als verouderd.
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
