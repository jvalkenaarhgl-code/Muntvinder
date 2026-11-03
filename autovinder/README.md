# AutoVinder 1.2 — inkoop en verkoop van goedkope occasions

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

## Facebook Marketplace — volledig gratis (beperkte openbare vondsten)

De scanner gebruikt **geen Apify, geen betaalde API, geen abonnement, geen Facebook-wachtwoord en geen cookie**. Een beperkte, gratis methode zoekt maximaal achtmaal per zes uur in een publiek toegankelijke **zoekmachine-RSS-index** naar Facebook Marketplace-autoadvertenties. Er worden geen verzoeken naar Facebook zelf gestuurd.

**Beperking:** een zoekmachine indexeert slechts een klein deel van Marketplace; resultaten kunnen oud of onvolledig zijn. Het is dus niet hetzelfde als volledig en actueel Facebook Marketplace doorzoeken. Als er geen geschikte Facebook-advertenties zijn gevonden, blijft de Facebook-lijst leeg en geeft AutoVinder de bronstatus aan. De bot verzint geen auto's.

Uit zoekresultaten worden alleen echte `facebook.com/marketplace/item/<nummer>`-links met herkenbaar automodel en expliciete prijs verwerkt. De prijzen zijn **ongeverifieerd** en worden nooit als betrouwbare topdeal of basis voor pushmeldingen behandeld. Kilometers en bouwjaar kunnen ontbreken; in dat geval kan de Android-filter `onbekende km-stand toestaan` helpen om zulke aanbiedingen te zien. Je kunt ook nog steeds een Facebook-advertentie handmatig toevoegen en zelf de gegevens invullen.

De automatische Marktplaats-scanner blijft de belangrijkste bron voor onderbouwde waarderingen. Voor Facebook-resultaten gebruikt de app dezelfde voorzichtige modelvergelijking met Marktplaats-vraagprijzen en onderscheidt ze de ongeverifieerde koopprijs. Alle functies blijven kosteloos zolang standaard GitHub Actions in een openbare repository wordt gebruikt.

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
- Facebook Marketplace werkt alleen als openbare zoekmachines recente, geschikte Marketplace-links teruggeven. Geen volledige Marketplace-toegang, geen betaalde diensten.
- Android-push is lokaal via periodieke achtergrondcontrole, geen instant Firebase-serverpush.
- Verkoop je structureel auto's met winstoogmerk, controleer dan vooraf belasting-, consumentenrechtelijke en RDW-verplichtingen.
