# 📦 Skladník

Mobilná inventúra pre Android: vyhľadaj skladovú kartu, naskenuj EAN (fotoaparátom alebo cez NFC),
zadaj napočítané množstvo a celý zoznam exportuj do Excelu (.xlsx).

## Stiahnutie

Sekcia **Releases** → najnovší súbor `Skladnik-1.0.X.apk` → otvoriť v telefóne a nainštalovať.

## Databáza skladových kariet

V aplikácii ťukni na **Načítať databázu** a vyber:

- **XML export z BlueGastro** (`ExportTovarJedlo.xml`) – načítajú sa všetky skladové karty s číslom, názvom, jednotkou a EAN kódmi, alebo
- **Excel** `.xlsx` / `.csv` (popis nižšie). Stĺpec s hlavičkou „QR“ sa tiež prečíta – z textu QR kódu sa vyberie EAN a číslo karty.

Po načítaní aplikácia ukáže, koľko kariet ešte **nemá EAN**. Tlačidlo **Bez EAN** otvorí ich zoznam –
ťukni na kartu, naskenuj jej čiarový alebo QR kód a EAN sa ku karte pridá. Pri opätovnom načítaní databázy
sa EAN kódy pridané v telefóne zachovajú.

Formát Excelu:
Stĺpce sa rozpoznajú podľa hlavičky, napríklad:

| Kód | Názov | EAN | Množstvo |
|-----|-------|-----|----------|
| 1001 | Skrutka M6x20 | 8580000000011 | 250 |
| 1001 | Skrutka M6x20 | 8580000000028 | |
| 1002 | Matica M6 | 8580000000035, 8580000000042 | 120 |

- Viac EAN pre jednu kartu: buď viac riadkov s rovnakým kódom, alebo viac EAN v jednej bunke oddelených čiarkou.
- Ak súbor nemá hlavičku, berie sa poradie stĺpcov A = kód, B = názov, C = EAN, D = množstvo.
- Starý formát `.xls` treba v Exceli uložiť ako `.xlsx`.

## Postup

1. **Skladová karta** – začni písať názov alebo kód (funguje aj bez diakritiky).
2. **Čiarový alebo QR kód** – Skenovať, priložiť NFC štítok alebo napísať. Po naskenovaní sa karta nájde sama.
   - číslo EAN (8, 12, 13 číslic) sa zapíše ako **EAN**,
   - 6-miestne číslo sa zapíše ako **číslo skladovej karty** a karta sa vyberie,
   - z textu QR kódu (napr. „Clinil 1 lt - 4028163018201“) sa vyberie EAN, prípadne aj číslo karty.
   Ak má karta viac EAN, aplikácia ponúkne výber. Neznámy EAN sa dá pridať ku karte.
3. **Množstvo** – zadaj napočítané množstvo a ťukni **Pridať do zoznamu**.

Každé pridanie je samostatný riadok – tá istá karta môže byť v zozname viackrát (napr. s rôznymi EAN).
Ak pridáš rovnakú kartu s rovnakým EAN znova, aplikácia sa opýta, či množstvo pripočítať, alebo vytvoriť nový riadok.

## Export

**Zoznam a export → Exportovať** – vyber formát, miesto uloženia a potom súbor môžeš zdieľať (e-mail, Drive, WhatsApp…):

- **Inventúra – Excel (.xlsx)** – stĺpce Kód, Názov skladovej karty, EAN, Napočítané množstvo, Stav v databáze, Rozdiel, Dátum a čas.
- **Inventúra – XML (.xml)** – tie isté údaje v XML.
- **Karty s novými EAN – XML pre BlueGastro** – pôvodný `ExportTovarJedlo.xml`, do ktorého sa ku kartám dopíšu EAN kódy
  pridané v telefóne. Ostatné údaje ostanú bez zmeny (kódovanie WINDOWS-1250). Import najprv vyskúšaj na záložnej kópii.
- **Karty a EAN – Excel (.xlsx)** – všetky karty s EAN, vrátane nových a kariet, ktorým EAN chýba.
