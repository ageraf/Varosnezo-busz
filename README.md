# Városnéző Busz – MVP (1. funkció), ingyenes változat

- **Busz:** város + járat → irány és megállók legördülő listából; az útvonal mentén
  Wikipedia-látnivalókat olvas fel, mielőtt odaér a jármű.
- **Séta:** útvonal nélkül; a környék látnivalóit olvassa fel, amikor kb. 50 m-re odaérsz.
- **Térkép:** OpenStreetMap, útvonal, látnivalók, saját pozíció.
- Szövegek: mindig a választott nyelvű eredeti Wikipedia-cikk (nincs gépi fordítás).
Nincs API-kulcs, nincs fizetős szolgáltatás.

| Rész | Forrás |
|---|---|
| Megálló, indulások, járat útvonala | MOTIS API – alapból a Transitous közösségi szervere |
| Látnivalók | Wikipedia API |
| Térkép | OpenStreetMap (osmdroid) |
| Felolvasás | a telefon beépített TTS-e |

## Beállítás
1. `app/src/main/java/hu/abris/varosnezo/data/Http.kt` → `CONTACT`: írd át a saját e-mail-címedre
   (a Transitous és a Wikipedia is kéri).
2. Push a `main` ágra → Actions → `varosnezo-debug-apk` artifact → telepítés.

## Fontos: Transitous feltételek
A Transitous önkéntesek által üzemeltetett szolgáltatás nyílt forrású és nonprofit projektekhez,
kereskedelmi célra nem használható. Prototípus-tesztelésre, kis forgalommal rendben van;
termékként saját MOTIS-szervert kell futtatni (nyílt forrású, a BKK GTFS-adata ingyenes),
és az `app/build.gradle.kts`-ben a `ROUTING_URL`-t átírni.

## Tesztelés buszút nélkül
Útvonal tervezése után a **Szimuláció** gomb GPS nélkül végigmegy az útvonalon (~29 km/h).

## Telefonon
- Kell magyar (vagy angol) TTS-hang: Beállítások → Felolvasás.
- vivo: az app akkumulátor-beállításánál engedélyezd a korlátlan háttérfutást.
- A CI minden futáskor új debug-kulccsal ír alá → frissítés előtt töröld az előző verziót.

## Hangolható értékek
- `TourService`: `MIN_LEAD_M`, `LEAD_S` (mennyivel előbb szóljon), `SIM_SPEED`
- `WikiPoiClient.poisAlong`: `corridorM` (sáv szélessége), `minGapM` (ritkítás)
