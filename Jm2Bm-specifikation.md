# Jm2Bm

**Teknisk specifikation · v1.1 (för implementation)**
**Datum:** 2026-10-25
**Målmiljö:** Minecraft Java Edition 26.3 · Fabric eller NeoForge · dedikerad server
**Modnamn / mod-id:** `Jm2Bm` / `jm2bm`

> **Syfte:** En liten, renodlad servermod i en gemensam Fabric/NeoForge-JAR som håller utvalda BlueMap Marker Sets automatiskt synkroniserade med administratörsskapade, globala JourneyMap-waypoints. JourneyMap är den enda sanningskällan. Gruppkopplingar och BlueMap-ikoner definieras i en enkel JSON-konfiguration.

## 1. Beslut som är låsta

1. **Endast serverhanterade globala waypoints** från JourneyMap synkas. Spelarnas personliga waypoints berörs aldrig.
2. Synkningen är **enkelriktad: JourneyMap → BlueMap**. Ändringar på BlueMap skrivs inte tillbaka till JourneyMap.
3. **JourneyMap-grupper identifieras med deras permanenta GUID/UUID**, aldrig med visningsnamn. Waypoints identifieras med deras permanenta GUID/UUID, aldrig namn eller koordinater.
4. Konfigurationen binder ett **JourneyMap-grupp-ID** till ett **befintligt BlueMap Marker Set-ID**, med möjlighet att ange en **egen BlueMap-ikon** per bindning.
5. **Flera JourneyMap-grupper får peka på samma BlueMap Marker Set.** Deras waypoints slås ihop till ett gemensamt önskat innehåll, men kan använda olika ikoner.
6. **Modden äger hela innehållet** i varje kopplat BlueMap Marker Set på den aktuella kartan. Den får skapa, uppdatera och ta bort **alla** markers i setet, även sådana som skapats manuellt eller av andra tillägg. Inget som saknar motsvarighet i den önskade JourneyMap-snapshoten ska finnas kvar efter en lyckad synk.
7. **Befintliga BlueMap Marker Sets återanvänds (alternativ B).** Modden skapar inte automatiskt saknade Marker Sets. Namn/label, synlighet, sortering och toggle-inställningar sköts i BlueMap, inte i bryggans konfiguration.
8. **Eventstyrd synk** vid förändringar i JourneyMap, samt full avstämning vid uppstart, BlueMap-reload och `/jm2bm reload`. **Ingen återkommande polling** i v1.
9. **Ingen databas**, inget externt API och ingen klientmod för själva bryggan.
10. **Säkerhetsregel:** Ett tekniskt fel eller ofullständigt resultat från JourneyMap får inte förväxlas med en tom grupp och därmed tömma BlueMap.

## 2. Omfattning

### Ingår i v1

- Skapa, ändra och radera motsvarande BlueMap POI-markers.
- Hålla namn, position, dimension och grupptillhörighet synkroniserade.
- Hantera en JourneyMap-waypoint som byter grupp eller dimension.
- Hantera flera JourneyMap-grupper mot ett och samma BlueMap Marker Set.
- Egna ikonfiler (`.png` och `.svg`), valda per mappning, oberoende av JourneyMaps egna ikoner.
- Flera BlueMap-kartor för en och samma Minecraft-dimension.
- Full avstämning efter omstart/reload och inkrementella avstämningar däremellan.
- Administratörskommandona `/jm2bm reload` och `/jm2bm status`.
- Tydlig loggning och skydd mot destruktiv synk vid fel.

### Ingår inte i v1

- Dubbelriktad synk eller redigering av JourneyMap via BlueMap.
- Personliga, klientlokala eller privata JourneyMap-waypoints.
- Automatisk mappning av JourneyMaps waypointikoner, färger eller beacon-inställningar.
- Olika ikonregler för enskilda waypoints inom samma grupp.
- Att skapa BlueMap Marker Sets eller ändra deras label, synlighet och sortering.
- Webbgränssnitt för administration, databas eller schemalagd polling.

## 3. Versions- och beroendekrav

| Komponent | Krav / utgångspunkt |
| --- | --- |
| Minecraft | Java Edition **26.3** |
| Modloader | **Fabric** eller **NeoForge**, på dedikerad server |
| Java | **25** enligt JourneyMaps 26.3-release |
| JourneyMap | **6.0.x** för 26.3; använd loader-matchande Fabric- eller NeoForge-release **26.3-6.0.10** eller senare kompatibel version |
| JourneyMap API | Publicerad 26.3-release uppger **API v26.3-2.0.0** |
| BlueMap | En kompatibel **5.x-version** för Minecraft 26.3; 5.29 finns publicerad |
| Fabric API | Krävs endast på Fabric; kompatibel version för 26.3 |
| NeoForge | **26.3.0.98** eller senare kompatibel 26.3-version |

Både JourneyMap och BlueMap är **nödvändiga serverberoenden** för att bryggan ska fungera. Bryggan ska inte paketera/shada deras API-klasser i sin distribuerade JAR. BlueMaps publika transitiva `flow-math`-beroende får ligga som loader-deklarerad nested dependency så att NeoForge kan deduplicera versionen.

**Verifieringspunkt före kodning:** kontrollera de exakta API-signaturerna, eventnamnen, Gradle-artifakterna och laddningsordningen för **26.3**, inte enbart exempel från 1.21.1. JourneyMaps offentliga API-exempel visar bland annat Fabric-entrypointen `journeymap_server`, men versionsspecifikt bygge måste verifieras.

## 4. Flöde och ägandeskap

```text
JourneyMap på servern
  └─ Globala waypoint-grupper, gruppernas GUID och waypoint-GUID
        │
        │  API-snapshot + events
        ▼
Jm2Bm
  ├─ ConfigurationManager  ─ läser mappings + ikonfiler
  ├─ JourneyMapAdapter    ─ läser globala grupper/waypoints
  ├─ SyncCoordinator      ─ beräknar önskat läge och skillnader
  └─ BlueMapAdapter       ─ uppdaterar befintliga Marker Sets
        │
        ▼
BlueMap API
  └─ Map A / Map B / ...
        └─ Redan existerande Marker Set-ID:n
             └─ POI-markers med JourneyMap-waypointens GUID som ID
```

**Äganderegel:** Om ett `blueMapMarkerSet` finns i den aktiva konfigurationen är hela dess markersamling på varje berörd BlueMap-karta exklusivt för bryggan. Allt som inte ska finnas enligt JourneyMap tas bort vid lyckad avstämning. Administratören ansvarar för att andra plugins eller manuell BlueMap-konfiguration inte använder samma set för information som ska bevaras.

BlueMap Marker Sets är **per BlueMap-karta**: samma set-ID kan förekomma på flera kartor. Varje karta stäms av mot waypoints som gäller för kartans dimension.

## 5. Filstruktur

```text
<server>/
├─ mods/
│  ├─ journeymap-...-<fabric|neoforge>.jar
│  ├─ bluemap-...-<fabric|neoforge>.jar
│  └─ jm2bm-...jar
└─ config/
   └─ jm2bm/
      ├─ config.json
      └─ icons/
         ├─ default.svg
         ├─ town.svg
         ├─ shop.png
         └─ dungeon.svg
```

Filnamnen för andra moddar är illustrativa. `config.json` skapas med ett tydligt exempel vid första start om filen saknas. Saknade ikonfiler ska inte krascha servern.

## 6. Konfigurationsformat

**Fil:** `config/jm2bm/config.json`

```json
{
  "syncOnStartup": true,
  "defaultIcon": "default.svg",
  "mappings": [
    {
      "journeyMapGroupUuid": "550e8400-e29b-41d4-a716-446655440000",
      "blueMapMarkerSet": "towns",
      "icon": "town.svg"
    },
    {
      "journeyMapGroupUuid": "6ba7b810-9dad-41d1-80b4-00c04fd430c8",
      "blueMapMarkerSet": "shops",
      "icon": "shop.png"
    },
    {
      "journeyMapGroupUuid": "123e4567-e89b-42d3-a456-426614174000",
      "blueMapMarkerSet": "towns",
      "icon": "dungeon.svg"
    }
  ]
}
```

*UUID-värdena ovan är exempel, inte riktiga JourneyMap-grupper.* Det tredje exemplet visar uttryckligen **flera JourneyMap-grupper till samma BlueMap Marker Set** (`towns`), med olika ikoner.

### Fält

| Fält | Typ | Betydelse |
| --- | --- | --- |
| `syncOnStartup` | boolean | Standard: `true`. Gör full avstämning när både JourneyMap och BlueMap är redo. |
| `defaultIcon` | string | Reservikon om en mappning saknar `icon`. Avser fil i `icons/`. |
| `mappings` | array | Lista över explicita gruppkopplingar. Endast dessa grupper synkas. |
| `journeyMapGroupUuid` | string | Permanent ID för JourneyMaps **globala grupp**. |
| `blueMapMarkerSet` | string | Exakt, befintligt BlueMap Marker Set-ID (inte visningsnamn). |
| `icon` | string | Valfri PNG/SVG-ikon för denna JourneyMap-grupp; åsidosätter `defaultIcon`. |

### Validering och regler

- Gruppidentifiering ska baseras på JourneyMaps permanenta GUID. Konfigurationsvärdet lagras som UUID-sträng, utan uppslag via namn. Exakt representation/format kontrolleras mot 26.3-API:t.
- `blueMapMarkerSet` matchas exakt, inklusive stora/små bokstäver.
- En och samma kombination `(journeyMapGroupUuid, blueMapMarkerSet)` får inte förekomma två gånger; ogiltiga dubletter avvisas med felmeddelande.
- Olika grupp-ID:n får dela samma `blueMapMarkerSet` och ha olika ikoner.
- Tom `mappings` är tillåtet och betyder att ingen aktiv koppling synkas.
- Om en grupp saknar mappning synkas den inte.
- Ingen mappning via gruppnamn eller automatiska wildcard-regler i v1.
- En ogiltig config vid `/jm2bm reload` får **inte** ersätta den senast giltiga konfigurationen och får **inte** trigga destruktiv rensning.
- Ikonsökvägar får bara referera till filer under bryggans `icons/`-katalog; blockera sökvägar med `..` eller absoluta sökvägar.

## 7. Regler för BlueMap-markers

Varje JourneyMap-waypoint som ingår i en mappad global grupp omvandlas till en vanlig BlueMap **POIMarker**.

| JourneyMap | BlueMap |
| --- | --- |
| Waypoint-GUID | Marker-ID (stabilt, t.ex. GUID-strängen) |
| Waypointens namn | Marker label |
| X, Y, Z | Marker position |
| Dimension(er) | Vilka BlueMap-kartor markern ska visas på |
| Global grupps GUID | Mappning till ett eller flera valda Marker Set-ID:n |
| Gruppens mappade ikon | BlueMap POI-ikon (med korrekt URL och ankare) |

- Waypointens **namn får aldrig användas som primärnyckel**: namnbyte ska uppdatera samma marker, inte skapa en ny.
- Detsamma gäller vid positionsändring.
- **Flera grupper → samma set:** önskat innehåll är unionen av samtliga waypoints i mappade grupper. Uppdatera **inte** ett set separat för varje grupp, eftersom sista körningen då skulle radera de andra gruppernas markers.
- Vid en gruppflytt ska markern flyttas mellan berörda Marker Sets; om de två grupperna pekar på samma set blir markern kvar med eventuellt ny ikon.
- Om waypointen gäller flera dimensioner ska den visas på alla relevanta BlueMap-kartor; dimensioner utan motsvarande BlueMap-karta hoppas över utan att ändra JourneyMap-data.
- Om flera BlueMap-kartor finns för samma dimension ska markern finnas på **samtliga** där det mappade Marker Set-ID:t finns.
- Waypoints och BlueMap-markerobjekt ska jämföras på relevanta fält; oförändrade objekt lämnas ifred för att undvika onödiga uppdateringar.
- Journeymaps egna ikoner, färger och synlighetsinställningar påverkar **inte** BlueMap-ikonen eller BlueMaps gruppinställningar i v1.

### Ikonpublicering

1. Läs ikonfilen från `config/jm2bm/icons/`.
2. Kopiera/publicera ikonen till en webbtillgänglig plats via BlueMaps API, exempelvis BlueMaps webroot eller kartans asset storage.
3. Använd webb-URL:en (inte lokal filsystemsökväg) i POI-markern.
4. Uppdatera resurs och markerreferens vid `/jm2bm reload` när en ikonfil ändras. Hantera webbläsarcache med versionsstyrd resursadress eller motsvarande.
5. Saknas en gruppspecifik ikon: använd `defaultIcon`. Saknas också reservikonen: använd BlueMaps standardikon och logga en varning.
6. `.png` och `.svg` stöds. Ikonankare kan använda en gemensam förnuftig standard i v1; eget ankare per grupp kan läggas till senare.

## 8. Synkalgoritm

**Avstämningen utförs per `(BlueMap-karta, Marker Set-ID)`**, inte per JourneyMap-grupp.

```text
reconcileAll():
  1. Säkerställ att både JourneyMap och BlueMap är redo.
  2. Läs och validera hela den aktiva konfigurationen.
  3. Hämta en konsekvent snapshot av samtliga globala JourneyMap-grupper
     och globala waypoints som behövs för alla mappings.
  4. Om snapshoten misslyckas eller är ofullständig: AVBRYT utan radering.
  5. Gruppera mappings efter destinationens Marker Set-ID.
  6. För varje BlueMap-karta och dess konfigurerade Marker Sets:
      a. Samla alla relevanta waypoints från ALLA JourneyMap-grupper
         som pekar på detta set och gäller kartans dimension.
      b. Bygg desiredMarkers: Map<WaypointGUID, DesiredPOIMarker>.
      c. Om setet saknas: logga fel, skapa det INTE, fortsätt med övriga.
      d. Skapa saknade marker-ID:n.
      e. Uppdatera existerande markers med ändrat innehåll.
      f. Radera ALLA marker-ID:n i setet som saknas i desiredMarkers.
  7. Spara senaste lyckade synkstatus och räknare för /jm2bm status.
```

**Definition av lyckad synk:** en korrekt, komplett JourneyMap-snapshot och genomförd avstämning för respektive BlueMap-karta/set. Om snapshot eller validering är osäker får ingen rensning ske för den berörda destinationen.

### Exempel på händelser

| Händelse | Förväntad effekt |
| --- | --- |
| Ny global waypoint i mappad grupp | Markern skapas i rätt set och dimension(er). |
| Waypoint tas bort | Markern tas bort från alla berörda set/kartor. |
| Waypoint får nytt namn | Samma marker-ID får ny label. |
| Waypoint flyttas | Samma marker-ID får ny position. |
| Waypoint byter grupp | Flytta mellan set eller ändra ikon om destinationen är densamma. |
| Grupp får ny ikon i config | Alla dess markers använder den nya ikonen efter reload. |
| Manuell marker läggs i ägt BlueMap-set | Tas bort vid nästa lyckade avstämning. |
| Mappad JourneyMap-grupp är bekräftat tom | Motsvarande bidrag är tomt; setet kan tömmas om inga andra grupper är kopplade. |
| BlueMap laddas om | Samtliga kopplade set avstäms igen. |
| Servern startar om | Samtliga kopplade set avstäms efter att API:erna är redo. |
| Blueprint/ikon ändras via config | `/jm2bm reload` laddar om config, publicerar ikoner och gör full avstämning. |

### Särskilt fall: borttagna mappningar

- Vid **giltig config-reload** stäms ett set som fortfarande har någon mappning av mot den **nya** unionen. Markers från borttagna JourneyMap-grupper försvinner därmed.
- Om den **sista** mappningen till ett tidigare ägt set tas bort, töms dess markers **en gång i samband med reload** och därefter slutar bryggan förvalta setet. Detta kräver att gamla och nya configen jämförs i minnet.
- Vid direkt filändring följd av **serveromstart**, utan mellanliggande reload, går det inte säkert att känna till tidigare borttagna mappningar utan extra beständig metadata. Detta är en **känd begränsning i v1**. För att säkert rensa ett avkopplat set: kör `/jm2bm reload` medan den gamla konfigurationen är aktivt inläst, och ta därefter omstarten. Alternativet är att administratören rensar det gamla BlueMap-setet manuellt.

## 9. Events, livscykel och trådar

- Registrera en **server-side JourneyMap API v2-plugin** med Fabric-entrypoint och NeoForges annoteringsbaserade upptäckt enligt den aktuella 26.3-versionen.
- Lyssna på server-events för global waypoint skapad/uppdaterad/raderad, globala gruppändringar och eventuellt gruppbyte om API:t exponerar separata händelser.
- Vid event: markera berörda set som ändrade och utför avstämning, så att snabba följdevents kan slås ihop utan onödiga upprepningar.
- Gör **en full avstämning** när Minecraft-servern, JourneyMap och BlueMap har initierats och är redo.
- Registrera `BlueMapAPI.onEnable(...)` och återställ/avstäm alla markers vid varje BlueMap-enable, inte bara första gången. BlueMaps API-markers är inte varaktiga över BlueMap-reloads.
- Hantera `BlueMapAPI.onDisable(...)` genom att pausa synk tills API:t är aktivt igen; behåll JourneyMaps data som sanningskälla.
- JourneyMap-händelser som inträffar innan BlueMap är redo ska inte tappas; en full synk när BlueMap blir aktiv ger korrekt slutläge.
- **Trådsäkerhet:** JourneyMap-/Minecraft-data läses på korrekt servertråd eller enligt API-kontraktet; BlueMap-callbacks kan köras asynkront. Synkkoordinatorn ska serialisera avstämningar och aldrig samtidigt mutera samma marker set från flera trådar.
- Vid mod-avstängning avregistreras relevanta lyssnare om API:t kräver det. Ingen separat waypoint-databas behöver sparas.

## 10. Kommandon och åtkomst

### `/jm2bm reload`

- Endast administratör/operatör (lämplig Minecraft-behörighetsnivå, t.ex. nivå 2 eller högre).
- Validerar och laddar om `config.json`.
- Publicerar/uppdaterar ikoner.
- Kör en full avstämning och hanterar borttagna mappningar enligt avsnitt 8.
- Ger sammanfattande resultat eller ett tydligt fel utan att överge senast giltiga config.

### `/jm2bm status`

- Visar JourneyMap-status, BlueMap-status, antal aktiva mappings, antal förvaltade set/kartor, senaste synktidpunkt och senaste resultat.
- Visar gärna antal markers per set och eventuella saknade Marker Sets eller grupp-ID:n.
- Endast administratör/operatör i v1.

Exempel på **tänkt** statusutskrift:

```text
Jm2Bm
JourneyMap: Connected
BlueMap: Connected
Mappings: 3
Managed sets: 2
  towns: 14 markers
  shops: 8 markers
Last sync: OK
```

## 11. Felhantering och säkerhetsgarantier

| Situation | Krav |
| --- | --- |
| JourneyMap-API inte redo, fel vid dataläsning eller ofullständig snapshot | **Ingen radering.** Logga orsak och avvakta nästa event/reload. |
| Fullständig snapshot visar att en grupp är tom | Rensa dess bidrag till destinationen; rensa hela setet om unionen är tom. |
| Grupp-UUID saknas i en i övrigt **verifierat komplett** snapshot | Hantera som borttagen/tom källa och logga varning. Om fullständigheten är osäker: avbryt rensningen. |
| BlueMap är inte aktivt | Ingen mutation; synka när `onEnable` körs. |
| Angivet Marker Set-ID saknas på en BlueMap-karta | Logga fel; **skapa inget set automatiskt**. Fortsätt med andra kartor/set. |
| Fel i en destinations uppdatering | Rapportera delvis fel och gör ny avstämning vid nästa trigger; radera inte i andra set baserat på osäker källa. |
| Ogiltig/trasig JSON eller dublettmappning | Behåll senast giltiga config; ingen destruktiv omladdning. |
| Saknad ikon | Gruppens ikon → `defaultIcon` → BlueMaps standardikon, med varning. |
| Två kandidater producerar samma marker-ID i samma destinations-set med olika innehåll | Avvisa/avbryt den destinationens synk och logga konflikt; välj inte godtyckligt en marker. |
| Annat tillägg skriver i ett förvaltat set | Bryggan har tolkningsföreträde; främmande markers tas bort vid nästa lyckade synk. |

Logga gärna formatet `[Jm2Bm] Sync completed: created=X updated=Y removed=Z sets=S`, och inkludera kartans ID, Marker Set-ID och berörd JourneyMap-grupp vid fel. Undvik spam för oförändrade händelser.

## 12. Föreslagen kodstruktur

```text
src/main/java/.../jm2bm/
├─ Jm2BmMod.java       # gemensam runtime, config och kommandon
├─ config/
│  ├─ BridgeConfig.java
│  └─ ConfigManager.java
├─ journeymap/
│  ├─ JourneyMapServerPlugin.java # JourneyMaps server-entrypoint
│  ├─ JourneyMapAdapter.java      # snapshot av globala grupper/waypoints
│  └─ JourneyMapEventListener.java
├─ bluemap/
│  ├─ BlueMapAdapter.java         # befintliga sets + POI-markers
│  └─ IconPublisher.java         # ikonresurser och webb-URL:er
├─ sync/
│  ├─ SyncCoordinator.java        # serialized reconcile
│  ├─ DesiredMarker.java
│  └─ SyncStatus.java
└─ command/
   └─ BridgeCommands.java
fabric/src/main/java/.../Jm2BmFabric.java       # Fabric-entrypoint
neoforge/src/main/java/.../Jm2BmNeoForge.java   # NeoForge-entrypoint
```

Håll API-specifik kod i var sin adapter och låt `SyncCoordinator` arbeta med enkla, egna datatyper. Det förenklar tester och gör det lättare att hantera mindre API-skillnader i framtiden.

## 13. Acceptanskriterier för v1

- [ ] Samma JAR startar på dedikerad Fabric 26.3- och NeoForge 26.3-server med loader-matchande JourneyMap och BlueMap installerade, utan klientkod i servermodden.
- [ ] Enbart administratörsskapade **globala** JourneyMap-waypoints hämtas; inga personliga waypoints exponeras.
- [ ] Gruppkopplingar använder stabila **GUID/UUID**, och waypoint-markers har stabilt ID.
- [ ] En ny waypoint blir synlig i korrekt, befintligt BlueMap Marker Set utan serveromstart.
- [ ] Namn- och positionsändringar uppdaterar samma marker.
- [ ] Borttagna waypoints försvinner från BlueMap.
- [ ] Gruppbyte flyttar markern till rätt set eller ändrar ikon vid delat set.
- [ ] **Två globala grupper kan dela ett Marker Set** och visa olika ikoner; ingen av dem raderar den andras markers.
- [ ] En lyckad avstämning tar bort **alla främmande/stale markers** i ett förvaltat set.
- [ ] Tom, korrekt inläst källa kan tömma setet; **felaktig eller ofullständig källa får aldrig göra det**.
- [ ] Alla befintliga BlueMap-kartor för rätt dimension uppdateras, inklusive Nether/End och eventuella anpassade dimensioner.
- [ ] Saknat Marker Set ger tydligt fel men skapas inte automatiskt.
- [ ] Bytta PNG/SVG-ikoner visas efter `/jm2bm reload`.
- [ ] Synkning fungerar efter serveromstart och BlueMap-reload.
- [ ] Ingen polling, extern tjänst eller databas krävs.
- [ ] `/jm2bm status` rapporterar anslutning, mappings och synkresultat.
- [ ] `/jm2bm reload` har konfigurationsvalidering och förstör inte data vid fel.
- [ ] Administratörskommandon kräver lämplig behörighet.

## 14. Verifieringspunkter inför implementation

Följande är **tekniska kontrollpunkter**, inte nya produktbeslut:

1. Bekräfta API v26.3-2.0.0:s exakta åtkomst till `global waypoints`, `global groups`, GUID och waypointens dimensionslista. Äldre JourneyMap API v2-exempel visar bland annat `getGuid()`, `getGroupId()`, `getDimensions()` samt serverpluginstöd, men den aktuella 26.3-versionen är auktoritativ.
2. Kontrollera exakt vilka JourneyMap **server-events** som avfyras vid gruppflytt, dimensionsändring, radering och gruppändring. Säkerställ att full avstämning täcker allt även om något event saknas.
3. Kontrollera BlueMaps API-version/Gradle-beroende för BlueMap 5.x på 26.3, `BlueMapAPI.onEnable`, `BlueMapMap.getMarkerSets()`, `MarkerSet.getMarkers()` samt POIMarker-ikonmetoden.
4. Bestäm en stabil lösning för BlueMaps ikon-URL:er samt cacheuppdatering vid `reload`.
5. Kontrollera vad som är korrekt tråd för att läsa JourneyMap-snapshoten och mutera BlueMap-objekt.

Dessa punkter ska avgöras innan färdig kod publiceras; de ändrar inte den funktionsmässiga specifikationen.

## 15. Referenser

- [JourneyMap 26.3-6.0.10 + Fabric (Modrinth)](https://modrinth.com/plugin/journeymap/version/26.3-6.0.10%2Bfabric) — kompatibilitet, Java 25, API-version.
- [JourneyMap 26.3-6.0.10 + NeoForge (Modrinth)](https://modrinth.com/plugin/journeymap/version/26.3-6.0.10%2Bneoforge) — NeoForge-kompatibilitet och API-version.
- [NeoForge 26.3 MDK](https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle) — auktoritativ bygg- och livscykelreferens.
- [JourneyMap API v2 — integrationsguide](https://teamjm.github.io/journeymap-docs/6.0.x/tools/integration/) — server-events, API v2 och versionsspecifika anvisningar.
- [JourneyMap API-källkod](https://github.com/TeamJM/journeymap-api) — auktoritativ referens för signaturer.
- [JourneyMap API: exempel på serverplugin](https://github.com/TeamJM/journeymap-api/blob/1.21.1_2.0.0/docs/howto.md) — äldre referens för `journeymap_server`; verifiera mot 26.3.
- [BlueMap: Markers](https://bluemap.bluecolored.de/wiki/customization/Markers.html) — befintliga marker sets, POI-markers och deras inställningar.
- [BlueMap API](https://github.com/BlueMap-Minecraft/BlueMapAPI) — map/set-API och livscykel.
- [BlueMap API: `MarkerSet.getMarkers()`](https://repo.bluecolored.de/javadoc/releases/de/bluecolored/bluemap-api/2.7.3/raw/de/bluecolored/bluemap/api/markers/MarkerSet.html) — modifierbar map av marker-ID till marker.
- [BlueMap-versioner](https://hangar.papermc.io/Blue/BlueMap/versions) — publicerade, kompatibla versioner.

---

**Sammanfattat kontrakt:** *Varje BlueMap Marker Set som binds i config ska, efter en lyckad synk, innehålla exakt unionen av alla relevanta globala JourneyMap-waypoints från de UUID-identifierade grupper som pekar mot setet, med konfigurerade BlueMap-ikoner — varken fler eller färre.*
