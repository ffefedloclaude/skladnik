# 📦 Skladník

Mobilná inventúra pre Android: vyhľadaj skladovú kartu, naskenuj EAN (fotoaparátom alebo cez NFC),
zadaj napočítané množstvo a celý zoznam exportuj do Excelu (.xlsx).

## Stiahnutie

Sekcia **Releases** → najnovší súbor `Skladnik-1.0.X.apk` → otvoriť v telefóne a nainštalovať.

## Databáza skladových kariet

V aplikácii ťukni na **Načítať Excel** a vyber súbor `.xlsx` alebo `.csv` z telefónu.
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
2. **EAN** – 📷 Skenovať, priložiť NFC štítok alebo napísať. Po naskenovaní sa karta nájde sama.
   Ak má karta viac EAN, aplikácia ponúkne výber. Neznámy EAN sa dá pridať ku karte.
3. **Množstvo** – zadaj napočítané množstvo a ťukni **Pridať do zoznamu**.

Každé pridanie je samostatný riadok – tá istá karta môže byť v zozname viackrát (napr. s rôznymi EAN).
Ak pridáš rovnakú kartu s rovnakým EAN znova, aplikácia sa opýta, či množstvo pripočítať, alebo vytvoriť nový riadok.

## Export

**Zoznam a export → Exportovať do Excelu** – vyber, kam sa má súbor uložiť, potom ho môžeš zdieľať (e-mail, Drive, WhatsApp…).
Stĺpce: Kód, Názov skladovej karty, EAN, Napočítané množstvo, Stav v databáze, Rozdiel, Dátum a čas.
