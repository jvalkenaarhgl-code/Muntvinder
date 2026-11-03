# AutoVinder 1.0 — inkoop en verkoop van goedkope occasions

**AutoVinder is een aparte Android-app naast Muntvinder** (app-id: nl.autovinder.app).
Broncode, workflows en JSON-feed staan in de map \`autovinder/\` van dezelfde GitHub-repository.

## Wat werkt er?

- De automatische Marktplaats-scanner zoekt specifiek in de categorie Auto's (91).
- Doorzoekt goedkope advertenties tot **€5.000** en extra vergelijkingsadvertenties tot **€12.500**. De telefoon ontvangt een **selectie van maximaal 430 advertenties** (beste kansen plus goedkope opties uit diverse prijsklassen), niet iedere Marktplaats-advertentie. Dit houdt de mobiele feed klein.
- Zoekt via algemene auto-aanbiedingen plus 12 populaire goedkope merken/modellen.
- Filtert verdachte lease-, aanbetalings- en onderdelenadvertenties. Extreem lage vermelde prijzen (onder €300) krijgen een waarschuwing en gelden niet als onderbouwde koopsom.
- Herkent, waar de advertentiegegevens beschikbaar zijn: bouwjaar, kilometerstand, model, foto's, bronlink, locatie en eventuele technische schade.
- Vergelijkt alleen met minimaal drie **actieve vaste vraagprijzen** van hetzelfde merk/model binnen 2 bouwjaren en vergelijkbare kilometerstand; beschadigde en verdachte prijsvermeldingen tellen niet als vergelijkingsbasis.
- **Verwachte doorverkoop is geen gerealiseerde verkoopprijs**: als scenario 75–88% van de middelste vraagprijs. De bandbreedte is een voorzichtige aanname, geen garantie.
- Totale investering: inkoop + instelbare reparatiekosten + instelbare overige kosten. Marge is nog steeds een bruto-indicatie zonder onvoorziene reparaties en overige bedrijfskosten.
- Resultaten worden gerangschikt op mogelijke conservatieve winst, met waarschuwingen voor vermoedelijke lokprijzen en dure defecten.
- Android-filters: maxprijs (standaard €1.500), max-kilometerstand (250.000 km), minimum bouwjaar (1995), merk/model/trefwoorden, plaatsfilter, wel/niet schade, wel/niet onbekende km-stand en geschatte reparatie-/overige kosten.
- Bij een nieuwe onderbouwde topdeal kan een Android-melding volgen: maximaal één per twaalf uur; Android kan achtergrondcontroles uitstellen. Geen betaalde AI of Telegram vereist.
- Marktplaats-foto's en advertentielinks zijn te openen in de app.

## Facebook Marketplace
De scanner heeft nu een **optionele automatische koppeling** met de externe Apify-actor [parsebird/facebook-marketplace-scraper](https://apify.com/parsebird/facebook-marketplace-scraper). Dit is **geen officiële Meta-API**. Meta kan automatische toegang beperken en verbiedt niet-toegestane scraping in haar gebruiksvoorwaarden. Er wordt geen Facebook-accountwachtwoord of cookie gebruikt.

De koppeling is standaard **NIET actief**: zonder API-token worden geen Facebook-advertenties opgehaald en wordt er niets in rekening gebracht. De gebruiker kan daarnaast handmatig een Marketplace-advertentie bewaren, inclusief een eigen (ongecontroleerde) verkoopinschatting.

### Automatische Facebook-scan activeren

1. Maak een account aan via https://console.apify.com/ en lees de prijsvoorwaarden van de actor.
2. Kopieer in Apify Console onder **Settings / API & Integrations** je persoonlijke API-token. Deel dit token niet in de chat of Android-app.
3. Ga naar https://github.com/jvalkenaarhgl-code/Muntvinder/settings/secrets/actions/new .
4. Vul bij **Name** exact `APIFY_TOKEN` in en zet je token onder **Secret**. Sla het op.
5. De volgende GitHub-scan activeert Facebook automatisch. Controleer via **AutoVinder → Facebook** of de feed een actuele Facebook-status en echte advertenties bevat. Het eerste ophalen kan even duren. Je kunt eventueel via GitHub Actions de workflow handmatig starten.

De backend vraagt standaard maximaal 10 autoadvertenties in vijf Nederlandse regio's per run op. Er staat een Apify-uitgavenplafond van **$0,12 per run** in de API-aanroep (geen maandplafond). Facebook wordt rond vier UTC-uren per dag opnieuw gevraagd; tussendoor worden recente resultaten hergebruikt. Bij vier runs per dag is het theoretische totaal tot circa **$14,40 per 30 dagen**, afhankelijk van de Apify-facturatie en de actor. De gebruiker autoriseert eventuele externe kosten zelf door zijn token te koppelen. Het Apify-account kan gratis proefcredits bevatten; blijvend gratis is niet gegarandeerd.

De Android-app gebruikt dezelfde bronfilter voor beide bronnen. Facebook-vermeldingen met ontbrekende kilometerstand krijgen geen betrouwbare doorverkoopwaarde en verdwijnen standaard onder de filter `km onbekend`, tenzij deze optie wordt ingeschakeld. De vraagprijsschatting is op Marktplaats-vergelijkingen gebaseerd, niet op echte verkooptransacties.

## Techniek en onderhoud
- \`autovinder/scan/scanner.py\` — Python-scanner en prijsanalyse.
- \`autovinder/scan/tests/test_scanner.py\` — tests.
- \`.github/workflows/autovinder-scan.yml\` — GitHub Actions-scan ongeveer ieder uur; schrijft \`autovinder/docs/feed.json\`.
- \`.github/workflows/autovinder-build.yml\` — Android-build in een aparte map, artefact AutoVinder-APK.
- Android-code in \`autovinder/android/\`.
- De app leest https://raw.githubusercontent.com/jvalkenaarhgl-code/Muntvinder/main/autovinder/docs/feed.json.

Een knop **Verversen** haalt de recent gepubliceerde feed op; hij start niet meteen een nieuwe GitHub-scan. Filters passen **lokaal** de gescande dataset aan; een hoger bedrag dan €5.000 is momenteel niet ondersteund.

## Belangrijke beperkingen
- Er is geen garantie op prijs, schadevrijheid, APK, NAP, kilometerstand, onderhoud, of RDW-status. Alleen zichtbaar advertentiemateriaal wordt verwerkt. Verifieer vóór aankoop het kenteken en de werkelijke staat; laat je monteur de reparatiekosten begroten.
- Marktplaats gebruikt een niet-officieel openbaar zoekendpoint; het formaat en de toegang kunnen veranderen.
- Vergelijkingen zijn **vraagprijzen**, geen gerealiseerde verkopen. Bij minder dan drie vergelijkbare vaste vraagprijzen wordt een onbekende doorverkoopprijs getoond.
- Een aanbod kan een incorrecte of onvolledige vraagprijs tonen. AutoVinder markeert verdachte lage bedragen.
- Facebook Marketplace is **optioneel automatisch via een aparte Apify-dienst** als `APIFY_TOKEN` is geconfigureerd; zonder token blijft alleen de handmatige zoek- en bewaarmogelijkheid.
- Android-push is lokaal via periodieke achtergrondcontrole, geen instant Firebase-serverpush.
- Verkoop je structureel auto's met winstoogmerk, controleer dan vooraf belasting-, consumentenrechtelijke en RDW-verplichtingen.
