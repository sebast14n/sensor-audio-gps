# Observații aplicație mobilă — de rezolvat (batch, fără build separat)

Strângem observațiile aici; le rezolvăm împreună într-un singur release când lista e completă.

Status: 🔴 de făcut · 🟡 în lucru · ✅ gata

---

## 1. 🔴 „Ascultă" pare pierdut din „🗂 Înregistrări"

**Simptom (raportat de user):** în lista de înregistrări nu mai găsești „Ascultă".

**Cauză (diagnosticat):** opțiunea există în cod, dar a devenit ascunsă.
- `RecordingsActivity.kt:117` — **tap** pe rând = doar bifează checkbox-ul (selectare pt. upload/ștergere în lot).
- `RecordingsActivity.kt:118` — meniul cu acțiuni individuale **„▶ Ascultă" / 🗺 Hartă / ⬆ Upload doar asta / 🗑 Șterge** s-a mutat pe **long-press** (apăsare lungă) → nedescoperibil.

Regresie față de v1.5.3 (atunci tap-ul deschidea direct meniul Ascultă/Upload/Șterge — vezi memoria proiectului).

**Fix propus (la implementare):** tap pe rând → deschide `singleActions(dir)` (meniul cu Ascultă etc.); selecția în lot rămâne pe **checkbox** (handler propriu pe `cb`, ca atingerea casetei să bifeze fără a deschide meniul). Eventual și un mic „▶" vizibil pe rând. Astfel: tap = acțiuni (inclusiv Ascultă), checkbox = selectare, long-press = (opțional) tot acțiuni.

**Fișiere:** `app/src/main/java/com/example/logger/RecordingsActivity.kt` (rândurile ~117-118, `singleActions`, `play`).

---

## 2. 🔴 Versiunea aplicației nu e afișată nicăieri

**Simptom:** userul nu găsește versiunea instalată.
**Acum:** `BuildConfig.VERSION_NAME` există (1.9.0) dar nu e afișat în UI; `MainActivity` folosește doar `VERSION_CODE` pt logica de update.
**Fix propus:** afișează `versionName (vc N)` într-un loc fix — ex. un `TextView` mic jos pe ecranul principal (lângă `tvPath`), sau în meniul de autentificare. `app/.../MainActivity.kt` + `activity_main.xml`.
**✅ Decizie (user):** afișare **discretă, mică** — nu mare/proeminentă.

---

## 3. 🔴 Verificare update: doar la pornire + doar dacă are semnal

**Acum:** `MainActivity.kt:115-117` verifică **o singură dată per instalare** (gate `update_checked_vc != VERSION_CODE`), în `onCreate`, **fără** test de conectivitate.
**Dorit:** verifică la **fiecare pornire** (cold start), **doar dacă există rețea**; NU în timpul folosirii / la resume.
**Fix propus:** scoate gate-ul „o dată per instalare"; adaugă verificare conectivitate (`ConnectivityManager.activeNetwork`) înainte de `UpdateChecker.checkAsync(this, verbose=false)`; păstrează apelul DOAR în `onCreate` (deja e doar acolo). `app/.../MainActivity.kt` (+ `UpdateChecker.kt`).

---

## 4. 🔴 Mini-player la „Ascultă"

**Simptom:** când asculți, nu e clar când s-a oprit sau ce se întâmplă.
**Acum:** `play()` = `MediaPlayer` + un toast „▶ nume"; fără controale, fără indicator de stare/progres. (`RecordingsActivity.kt:222`, `MainActivity.kt:388 playSession`.)
**Fix propus:** un mini-player vizibil (bară jos / dialog persistent) cu ▶/⏸/⏹ + timp scurs/total + numele fișierului; se actualizează la `onCompletion`. Aplicat în ambele locuri (RecordingsActivity + MainActivity).
**✅ Decizie (user):** player **peste o hartă**, cu un marker care **se mișcă pe traseul GPX sincronizat cu redarea** — la transect, pe măsură ce „se aud punctele". La senzor fix (un punct / fără traseu) = marker static + player simplu. Sursa poziției = trackpoint-urile cu timp din `track_*.gpx`, corelate cu poziția curentă din audio (segmentele `audio_NNN_…` au timestamp). Reutilizează `MapActivity`.
**✅ LIVRAT 1.9.3:** `PlaybackActivity` — hartă osmdroid + traseu GPX (polilinie) + marker care avansează pe traseu după progresul global al redării (segment + poziție) + mini-player vizibil (⏮ ⏯ ⏹ ⏭ + SeekBar + timp + nume fișier + navigare segmente). „Ascultă" (RecordingsActivity + MainActivity) deschide acum PlaybackActivity. Sincronizare = mapare proporțională progres→traseu (suficient pt transect la pas constant; nu sync exact pe timestamp GPX — follow-up dacă e nevoie).

---

## 5. 🟡 Tiles cache 5km → 1km (se încarcă greu)

**Acum:** `MapActivity.kt:162 precache5km` cu `delta=0.025` (~5×5 km), zoom 13-16, buton „🛰 Cache 5km" (`:97,:102`). Plus `precacheTiles()` default. Multe tile-uri → lent.
**Fix propus:** redu la ~1×1 km (`delta=0.005`), reetichetează „🛰 Cache 1km"; eventual scade zoom-ul maxim (16→15) ca să mai taie din tile-uri. De verificat și aria din `precacheTiles()`. `app/.../MapActivity.kt`.
**✅ Decizie (user):** 1×1 km e suficient.

---

## 6. 🟡 Format înregistrări — motor LOSSLESS livrat în 1.9.2 (FLAC experimental)

**LIVRAT 1.9.2:** captura rescrisă de pe `MediaRecorder`/AAC pe **`AudioRecord` (PCM 16-bit, UNPROCESSED)**
→ `AudioSegmentRecorder.kt`. **WAV = implicit** (lossless, 100% fiabil, **identic Song Meter**). **FLAC =
experimental** (encoder `MediaCodec` + header din CSD), selectabil din ecranul 🔧 Diagnostic („Comută WAV ⇄
FLAC"), cu **fallback automat pe WAV** dacă encoderul FLAC nu pornește. App + server acceptă `.flac`
(filtre extensii + BirdNET citește după conținut). **De testat pe teren:** înregistrează în FLAC, verifică
că fișierele se aud + se urcă; dacă-s valide → fac FLAC implicit în 1.9.3 (o linie). **Follow-up server:**
parsare durată/sample-rate din STREAMINFO FLAC la upload (acum sr/dur lipsesc pt FLAC — nu blochează BirdNET).
Detalii istorice (de ce ≠ Song Meter inițial) mai jos.

---

### (istoric) Format înregistrări ≠ Wildlife Acoustics — constatarea inițială

**Verificare cerută de user — rezultat: NU coincid.**
- **App-ul nostru** (`RecordingService.kt:197-201`): `MPEG_4 / AAC`, mono, **48 kHz, 256 kbps → fișier `.m4a` (cu pierderi)**. Nume: `audio_{NNN}_{yyyyMMdd_HHmmss}.m4a` în `session_{ts}/`.
- **Song Meter (Wildlife Acoustics)**: **WAV PCM 16-bit (fără pierderi)**, 48 kHz (acustic) sau 256/384 kHz (ultrasonic, lilieci). Nume: `PREFIX_YYYYMMDD_HHMMSS.wav`.
- **Diferențe:** codec (AAC cu pierderi vs PCM fără pierderi) · container (m4a vs wav) · telefonul **nu poate** ultrasonic (Android plafonează ~48 kHz) → lilieci imposibil pe telefon oricum. Pt **păsări la 48 kHz** merge (serverul acceptă m4a/mp3/wav), dar NU e identic cu Song Meter.
- **Decizie necesară (la implementare):** trecem app-ul pe **WAV PCM 16-bit 48 kHz** (via `AudioRecord` + scriere header WAV) ca să fie identic cu Song Meter? **Plus:** lossless, format standard, ușor de procesat. **Minus:** fișiere ~3× mai mari (~5,8 MB/min vs ~1,9). Recomandare: DA, dacă spațiul permite (păstrăm și `*_Summary.txt`-like? — de discutat).
- **✅ Decizie (user): FLAC (lossless, ~50% din WAV).** Captură cu `AudioRecord` (PCM brut 48 kHz/16-bit/mono, sursă `UNPROCESSED`) → encoder `MediaCodec` `audio/flac` (disponibil din API 26 = minSdk-ul nostru) → fișier `.flac`. Server: adaugă `.flac` la whitelist-ul de upload (acum wav/mp3/m4a) — BirdNET/librosa citesc FLAC nativ. Lossless (echivalent WAV, convertibil), dar pe jumătate ca spațiu → autonomie card mult mai bună. De decis ulterior: prefix nume în stil Song Meter (`PREFIX_YYYYMMDD_HHMMSS.flac`).

---

## 7. 🔴 Editor program senzor fix (ca aplicația Wildlife Acoustics)

**Dorit:** un mic editor pentru senzorul fix — ore pornire/oprire în funcție de **sunrise/sunset + locație**, **intervale ON și intervale OFF** (duty cycle), și **între orele x și y** (interval absolut).
**Acum:** `RecordWindow.kt` = o **singură fereastră nocturnă continuă** (apus−30 min → răsărit+30 min; fallback 19:00–07:00). Fără duty cycle, fără offset-uri configurabile, fără intervale absolute. Senzorul fix rulează în mod `scheduled`.
**Fix propus:** extinde `RecordWindow` cu config (offset sunrise/sunset ±min, duty cycle record X / pauză Y, interval orar absolut) + un ecran de setări care arată un rezumat ca la Song Meter; persistă în prefs; `RecordingService` citește config-ul. `app/.../RecordWindow.kt` + nou `ScheduleEditorActivity` + `RecordingService.kt`.
**✅ Decizie (user):** orar **multi-fereastră** (ca Wildlife Acoustics), fiecare fereastră cu propriul duty cycle. Scop: maxim de specii, în special prioritare (răpitoare nocturne + clocitori), cu economie de baterie pt 3 zile. Implicit:
- **Zori** (cel mai bogat): răsărit−30 min → +3 h, **continuu** (cor de dimineață — majoritatea paseriformelor + clocitori prioritari).
- **Apus**: apus−30 min → +1,5 h, continuu (cânt de seară, crepusculare, bufnițe la început).
- **Noapte** (între): **5 min ON / 30 min OFF** (bufnițe — Tyto/Otus/Strix prioritare, cristei, migranți nocturni care strigă).
- **Zi** (mijloc): OFF (activitate mică → economie baterie). Opțional sparse 5/55 pt răpitoare diurne.
Editorul trebuie să permită N ferestre (relative la răsărit/apus sau ore absolute) + duty cycle per fereastră. Calc autonomie: ~6-7 h înregistrate/zi → FLAC ~1,2 GB/zi (3 zile ~3,6 GB, lejer); bateria = limita, dar 3 zile rămâne fezabil.

---

## 9. 🔴 Măsurarea consumului de baterie (pt optimizare reală)

**Cerință (user):** să știm cât consumă pe oră de ascultat/înregistrat și pe zi → optimizare reală.

**Acum:** doar instantaneu (`MainActivity.batteryReport()` — nivel/încărcare/temp, o dată la amplasare) +
alertă la baterie scăzută (`AntiTheftMonitor` ACTION_BATTERY_LOW). **Niciun log în timp** → nu poate da %/oră.

**Android expune exact ce trebuie** (`BatteryManager`), deci putem măsura **exact**, nu estima:
- `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh rămași) → **delta între 2 momente = mAh consumați exact**.
- `BATTERY_PROPERTY_CURRENT_NOW` / `CURRENT_AVERAGE` (µA) → curent instantaneu/mediu.
- `CAPACITY` (%), `EXTRA_TEMPERATURE`, `EXTRA_VOLTAGE`, stare încărcare.

**Fix propus — logger de baterie:**
- În `RecordingService` (rulează oricum în timpul sesiunii), un sampler periodic (la ~5 min) scrie un rând
  CSV în `/BioEcho/battery_log.csv` (sau per-sesiune): `timestamp, charge_counter_uah, current_now_ua,
  level_pct, temp_c, voltage_mv, charging, recording_active, screen_on`.
- Din `charge_counter` delta → **mAh/oră** pe fiecare interval; separat pe **fereastră ON (înregistrare)
  vs OFF (pauză/somn)** → atribui consumul: microfon+CPU+FLAC vs GPS fix vs scanare BLE vs idle.
- **Analiză:** un sumar în app („~X mAh/h înregistrare, ~Y mAh/h pauză → autonomie estimată Z zile la
  programul curent") + opțional upload CSV ca să-l analizăm pe server pe mai multe telefoane/programe.
- **Plus pentru optimizare:** comparăm programe (duty cycle 5/30 vs 5/55, zori continuu vs 5/5) pe consum
  real → alegem orarul care maximizează speciile/mAh. Leagă direct de editorul de program (#7) și de
  protocolul de 3 zile.

**Fișiere:** `RecordingService.kt` (sampler + scriere CSV), `Storage.kt` (cale log), opțional un ecran
sumar în `MainActivity` + upload prin `UploadManager`.

**✅ Refinare (user): aliniere cu Wildlife Acoustics + sumar în app.**
- **Song Meter loghează bateria ca VOLTAJ** în `*_Summary.txt` (coloane `…,POWER(V),…,TEMP(C)`, dată
  `YYYY-Mon-DD,HH:MM:SS`). Serverul îl parsează DEJA (`tenants.py::_parse_summary` → panoul „🔋 Baterie"
  + estimare „X nopți"). → **Telefonul să emită un `*_Summary.txt` IDENTIC** (POWER(V)=voltaj baterie din
  `EXTRA_VOLTAGE`/1000, TEMP(C)=`EXTRA_TEMPERATURE`/10, un rând per înregistrare) → se conectează automat
  la pipeline-ul existent, fără cod nou pe server. Unifică telefoane + Song Meter.
- ⚠️ **DAR estimarea autonomiei pt telefon NU pe voltaj:** Li-ion are curbă de voltaj plată/neliniară →
  estimarea „X nopți" (calibrată pe baterii de senzor) ar fi greșită. Pt telefon folosește **% / mAh
  (charge_counter)**. Deci: Summary.txt cu voltaj = pt compatibilitate/afișare; estimarea reală = din CSV-ul %/mAh.
- **Sumar în app (user: da):** card „🔋 consum: ~X mAh/h înregistrare · ~Y mAh/h pauză · autonomie ~Z zile".
- **„Ce alte aplicații consumă cel mai mult":** ⚠️ Android **NU** lasă o aplicație normală să citească
  consumul ALTOR aplicații (`BATTERY_STATS` = permisiune privilegiată/signature, indisponibilă sideload).
  Putem: (a) buton care deschide ecranul de sistem Setări→Baterie (deep-link `Intent`) ca să vadă userul
  manual; (b) măsurăm **exact consumul NOSTRU** (charge_counter cât rulează serviciul); (c) pe un telefon
  DEDICAT de teren oricum app-ul nostru ≈ singurul consumator → drain total ≈ consumul nostru. Recomandare:
  telefon „curat" (fără alte app-uri grele) + buton spre ecranul de baterie de sistem pt transparență.

---

## 8. ✅ Înregistrări vizibile fără login — RĂMÂNE AȘA (rezolvat)

**Observație:** ecranul „🗂 Înregistrări" e accesibil fără autentificare.
**✅ Decizie (user):** se lasă așa — sunt fișiere locale, oricum le poate asculta; a bloca vizualizarea n-are sens. **Doar UPLOAD-ul cere autentificare** (deja așa: `RecordingsActivity.kt:141` verifică `jwtToken` la upload). Niciun cod de schimbat.

---

## 10. 🔴 Cont platformă (FĂRĂ Google) + QR de provizionare (config + login local)

**Cerință (user):** telefoanele să NU folosească un cont Google / Gmail-ul personal. App-ul scanează un
**cod QR de unde își ia TOATĂ configurația** (ex. scheduling) și **se loghează cu un cont local**.

**Context:** designul există — `docs-Sebastian/STRUCTURA-SENZORI-QR-OBSERVER.md` (3 piese: cont flotă
observator, `device_tokens` revocabile, QR de provizionare). **Evoluție cerută acum:**
1. **Fără Google deloc** — platforma are deja login pe email (magic-link) + utilizatori creați de admin →
   contul „flotă senzori" poate fi **platform-native** (NU Google). Telefonul nu atinge niciodată Google.
2. **QR-ul cară și CONFIGUL**, nu doar identitatea (provizionare, nu doar login).

**Acum (de schimbat):** `mobile.py::qr-generate`+`mobile-verify` copiază identitatea din sesiunea WEB
(deci poate `admin`/emailul tău) și emite un JWT stateless de 30 zile (nerevocabil, fără identitate de
dispozitiv) = exact riscurile R1/R2/R3 din doc.

**Fix propus (endpoint-uri noi):**
- `POST /api/devices/provision` (admin): alegi proiect/AOI + preset orar + nume → creează un device +
  un **enroll code** (one-time) + stochează configul intenționat. Întoarce QR `bioecho://provision/<code>`.
- `POST /api/devices/claim` (app, fără auth): trimite enroll code → server creează un **device_token**
  (revocabil, hash în DB, legat de contul flotă `observer`) + întoarce **token + config JSON complet**
  (orar/ferestre/duty cycle, proiect, format FLAC, interval ping locație, etc.). App-ul salvează tokenul
  (login LOCAL, fără Google) + aplică configul.
- `GET /api/devices/config` (cu device_token): reia ultimul config → **poți schimba orarul din platformă
  și telefonul îl preia** (sămânța pt management de la distanță / C&C v2.0).
- Token revocabil: `device furat → UPDATE revoked=true` taie un singur telefon, restul merg.

**Fișiere:** server `nozero/api/` (devices provision/claim/config + tabel `device_tokens` + cont flotă);
app `QrScanActivity.kt` (scheme `provision`), `GoogleLogin`→opțional, stocare token local + aplicare config
(RecordWindow/prefs). Înlocuiește dependența de Google pt senzorii ficși.
**✅ Decizie (user):** UN singur cont flotă **per platformă** (global), nu per proiect — suficient.
Atribuirea uploadurilor la proiectul corect se face din configul QR (proiect/AOI), nu din cont.

---

## 11. 🔴 Trimitere locație periodică (~15 min, dacă are net)

**Cerință (user):** telefonul să trimită locația, **dacă are internet**, nu frecvent — ~o dată la 15 min.

**Acum:** `mobile.py::mobile-alert` acceptă deja `lat/lon` (trimis doar la alerte: furt/baterie/BLE). Nu
există ping periodic.

**Fix propus:** un task periodic (WorkManager la 15 min, sau în `RecordingService`) care, **dacă există
rețea**, ia locația (last-known/coarse = ieftin) și face POST (reutilizăm `mobile-alert` cu
`kind='location_ping'`, sau endpoint dedicat `/api/devices/ping`). Serverul reține **ultima poziție per
device** → afișare pe hartă (unde-i amplasat fiecare telefon) + anti-furt (dacă se mută neașteptat). Ieftin
la baterie (un fix + un POST mic la 15 min). Se leagă de #10 (identitate de dispozitiv → știi CARE telefon).

**🟦 NOTAT pt viitor (user) — fallback SMS:** dacă NU are net dar are semnal GSM, să poată trimite locația
prin **SMS**. Mai complicat (recepție + interpretare/parsare pe partea de server — nevoie de un modem/gateway
SMS sau un serviciu) → DOAR notat acum, neimplementat. Util ca ultim resort pt anti-furt în zone fără date.

**Senzor de mișcare (răspuns la întrebarea ta):** DA, aplicația folosește deja senzorul de mișcare —
`Sensor.TYPE_SIGNIFICANT_MOTION` (`AntiTheftMonitor.kt:46`), hardware, very low power, one-shot → alertă
la ridicarea telefonului. ⚠️ E nullable: pe un telefon FĂRĂ acest senzor compozit, `getDefaultSensor`
întoarce null și anti-furtul pe mișcare nu se declanșează (NU există fallback). **De adăugat:** (a) fallback
pe **accelerometru** (TYPE_ACCELEROMETER — universal) cu prag, dacă SIGNIFICANT_MOTION lipsește; (b) ecran
**„Diagnostic senzori"** în app (`SensorManager.getSensorList(TYPE_ALL)`) care listează ce are telefonul
(accelerometru / giroscop / significant-motion / GPS) — așa vezi pe Armox X16 exact ce e disponibil.
**Pt „furt" real:** semnalul fiabil e **deplasarea GPS** (s-a mutat > X m față de amplasare), nu accelerometrul
(care sare la vânt). Combinație: SIGNIFICANT_MOTION (ridicat) + GPS-displacement (mutat).

**✅ Precizare (user) — sensibilitate MARE:** telefoanele se amplasează pe **arbori mari / poziții stabile**
care nu se mișcă ușor → deci **orice mișcare, chiar mică = cineva l-a luat din copac** → declanșează alarmă.
Deci: declanșare **pe mișcare singură** (significant-motion / accelerometru), sensibilă; GPS-displacement =
confirmare secundară, NU condiție obligatorie. ⚠️ `TYPE_SIGNIFICANT_MOTION` e **one-shot** → trebuie
**re-armat** după fiecare declanșare ca să detecteze în continuare (de verificat în `AntiTheftMonitor`).

---

## 12. 🔴 Ecran „🔧 Diagnostic" (senzori + probă microfon vizuală + GPS + baterie)

**Cerință (user):** ecran de diagnostic care (a) listează senzorii telefonului, (b) **verificare vizuală a
microfonului** — „1-2-3 probă", să vezi că se aude/captează sunet.

**Conținut propus (checklist pre-amplasare):**
- **Senzori:** `SensorManager.getSensorList(TYPE_ALL)` → listă + ✓/✗ pe cele relevante
  (accelerometru, giroscop, **significant-motion** [anti-furt], magnetometru/busolă, GPS). Rezolvă „ce are
  Armox X16".
- **Probă microfon (vizual):** `AudioRecord` (sursă UNPROCESSED/MIC, 48 kHz mono) citește în timp real →
  **bară de nivel** (RMS/peak în dBFS, verde→roșu) + mică **undă/waveform** care reacționează când vorbești
  („1-2-3"). Afișează și **ce microfon e activ** (intern / USB-C / Bluetooth — logica există în
  `RecordingService.logAudioDevice`). Oprește `AudioRecord` la ieșirea din ecran. Reutilizează captura
  `AudioRecord` care vine oricum cu FLAC (#6).
- **GPS:** buton „testează fix" → ia o poziție, arată lat/lon + precizie (m) + timp până la fix.
- **Baterie:** instantaneu nivel/voltaj/temp + (după #9) consum estimat.

**Fișiere:** nou `DiagnosticActivity.kt` + buton în `MainActivity` („🔧 Diagnostic"). Folosește
`SensorManager`, `AudioRecord`, `LocationManager`, `BatteryManager`. Independent de serviciul de înregistrare.

---

## 13. 🔴 Cozi de alerte offline + livrare SILENȚIOASĂ (stealth)

**Cerință (user):** dacă NU există semnal, alertele (furt etc.) să fie **păstrate local** și trimise
**imediat ce telefonul se reconectează la internet** — **fără ca hoțul să-și dea seama** că se întâmplă asta.

**Acum (gap):** `AntiTheftMonitor.sendAlert` face direct POST la `/api/auth/mobile-alert`; dacă nu e net,
**POST-ul eșuează și alerta se PIERDE** (fără coadă, fără retry).

**Fix propus:**
- **Coadă locală persistentă:** la fiecare alertă → scrie un rând într-un fișier de coadă (JSON, în
  stocarea privată a app-ului — NU în folderul public, ca hoțul să nu-l vadă/șteargă). Încearcă trimiterea;
  la succes → scoate din coadă.
- **Flush la reconectare:** `ConnectivityManager.registerNetworkCallback(onAvailable)` **+** `WorkManager`
  cu constrângere `NetworkType.CONNECTED` (garantat, supraviețuiește reboot/kill) → golește coada de îndată
  ce apare net. (Pingul de locație #11 e și el un moment bun de flush.)
- **Stealth total:** alerta NU produce nimic vizibil/audibil — fără notificare, fără sunet, fără vibrație,
  fără toast, fără schimbare de UI. Singura notificare e cea a serviciului foreground (impusă de Android pt
  microfon) — de păstrat **discretă/generică** (nu „🚨 alertă trimisă"). Hoțul nu vede nimic.
- Fiecare alertă în coadă păstrează **timestamp-ul real** al evenimentului (când s-a mișcat), nu al trimiterii
  → pe server vezi când a fost luat efectiv, chiar dacă a ajuns mai târziu.

**Fișiere:** `AntiTheftMonitor.kt` (coadă + retry), nou helper coadă (ex. `AlertQueue.kt`), `WorkManager`
worker pt flush; server `mobile-alert` acceptă `occurred_at` (timestamp eveniment) pe lângă ora primirii.


