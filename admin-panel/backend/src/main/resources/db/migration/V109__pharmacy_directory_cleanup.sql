-- V109: pharmacy register cleanup (INK-154, data of 23.11.2026).
-- The register was loaded once from the storefront's stock locations (seed/pharmacies.json). The network is
-- only part of the pharmacy name and was spelled in many ways, the old parser glued villages onto it and cut
-- city names. Nothing refreshes these columns at night: the 06:30 job recalculates sales metrics only.
-- Data only, forward-only. Every statement is guarded by the current value, so a value edited by hand in the
-- admin panel later is left alone, and a fresh database (empty table at this point) is not touched.

-- 1. One spelling per network, as in the network's own pharmacy list. Same rules as ChainNames.kt.
--    Warehouses ("Склад ...") and names without a known network keep their value.
UPDATE pharmacies SET chain_name = CASE
        WHEN name ~* '^[[:space:]]*jnk' THEN 'JNK-фарм'
        WHEN name ~* '^[[:space:]]*ирбис[[:space:]-]+жер' THEN 'Ирбис-Жер'
        WHEN name ~* '^[[:space:]]*ирбис[[:space:]]+транс' THEN 'Ирбис Транс Систем'
        WHEN name ~* '^[[:space:]]*курмет' THEN 'Курмет-Фарм'
        WHEN name ~* '^[[:space:]]*аспект' THEN 'Аспект-траст'
        WHEN name ~* '^[[:space:]]*лимон' THEN 'Лимон Фарм'
        WHEN name ~* '^[[:space:]]*(тоо[[:space:]]*)?"?tengripharm' THEN 'ТОО "Tengripharm"'
        WHEN name ~* '^[[:space:]]*(тоо[[:space:]]+)?медин' THEN 'ТОО Медин'
        ELSE chain_name END,
    updated_at = now()
WHERE chain_id = 'inkar'
  AND name ~* '^[[:space:]]*(jnk|ирбис[[:space:]-]+жер|ирбис[[:space:]]+транс|курмет|аспект|лимон|(тоо[[:space:]]*)?"?tengripharm|(тоо[[:space:]]+)?медин)'
  AND chain_name IS DISTINCT FROM CASE
        WHEN name ~* '^[[:space:]]*jnk' THEN 'JNK-фарм'
        WHEN name ~* '^[[:space:]]*ирбис[[:space:]-]+жер' THEN 'Ирбис-Жер'
        WHEN name ~* '^[[:space:]]*ирбис[[:space:]]+транс' THEN 'Ирбис Транс Систем'
        WHEN name ~* '^[[:space:]]*курмет' THEN 'Курмет-Фарм'
        WHEN name ~* '^[[:space:]]*аспект' THEN 'Аспект-траст'
        WHEN name ~* '^[[:space:]]*лимон' THEN 'Лимон Фарм'
        WHEN name ~* '^[[:space:]]*(тоо[[:space:]]*)?"?tengripharm' THEN 'ТОО "Tengripharm"'
        WHEN name ~* '^[[:space:]]*(тоо[[:space:]]+)?медин' THEN 'ТОО Медин'
        ELSE chain_name END;

-- 2. City where the register has a dash or a cut-off word: the locality named in the pharmacy name, otherwise
--    the city in the network's pharmacy list (divisions, 04.11.2026).
UPDATE pharmacies SET city = 'Панфилово', updated_at = now() WHERE id = 'sloc_01KSAHYDSJ6EHP68VM4R33WWCX' AND city = '—';
UPDATE pharmacies SET city = 'Жезказган', updated_at = now() WHERE id = 'sloc_01KSAHYDSCYNTA3M8R30YFHX1W' AND city = 'ан';
UPDATE pharmacies SET city = 'Узынагаш', updated_at = now() WHERE id = 'sloc_01KSAHYDSGV4229V6XX182Y97E' AND city = 'аш';
UPDATE pharmacies SET city = 'Боралдай', updated_at = now() WHERE id = 'sloc_01KSAHYDSEC4ED0C9PX6M5BZJ3' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSKZ3K1NK0GVSRWYY93' AND city = '—';
UPDATE pharmacies SET city = 'Байсерке', updated_at = now() WHERE id = 'sloc_01KSAHYDSKBX8FQYXEGG0EXP26' AND city = '—';
UPDATE pharmacies SET city = 'Водник', updated_at = now() WHERE id = 'sloc_01KSAHYDSA3GS21TR3KTF95DJQ' AND city = '—';
UPDATE pharmacies SET city = 'Боралдай', updated_at = now() WHERE id = 'sloc_01KSAHYDS9HGPVGPEJV9PXTRBW' AND city = '—';
UPDATE pharmacies SET city = 'Алматинская область', updated_at = now() WHERE id = 'sloc_01KSAHYDSAC3KGXHSRTYHM6H87' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSBTFWR5RSFJKTMYQ63' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSE5M8Q87W91KSM21JD' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSB0BEEG93FABFDNFTK' AND city = '—';
UPDATE pharmacies SET city = 'Жаркент', updated_at = now() WHERE id = 'sloc_01KSAHYDSGZ9NAWXPTXJSC2HZC' AND city = '—';
UPDATE pharmacies SET city = 'Боралдай', updated_at = now() WHERE id = 'sloc_01KSAHYDSFDQQB5E8KNEPZB4F2' AND city = '—';
UPDATE pharmacies SET city = 'Отеген батыр', updated_at = now() WHERE id = 'sloc_01KSAHYDSKYSNR7X767KNND3X4' AND city = 'ен';
UPDATE pharmacies SET city = 'Шелек', updated_at = now() WHERE id = 'sloc_01KSAHYDSMTVE5R704YV5SBQYH' AND city = '—';
UPDATE pharmacies SET city = 'Жетиген', updated_at = now() WHERE id = 'sloc_01KSAHYDSB2X042YH51YM2S37E' AND city = 'ен';
UPDATE pharmacies SET city = 'Водник', updated_at = now() WHERE id = 'sloc_01KSAHYDSMJXQFMK9R50WNTD17' AND city = '—';
UPDATE pharmacies SET city = 'Каскелен', updated_at = now() WHERE id = 'sloc_01KSAHYDSNC5KM9TKKJQMBHPT1' AND city = '—';
UPDATE pharmacies SET city = 'Сарыозек', updated_at = now() WHERE id = 'sloc_01KSBH8M03T66GJKBJE8Z6956C' AND city = '—';
UPDATE pharmacies SET city = 'Байтерек', updated_at = now() WHERE id = 'sloc_01KSAHYDSPFH1QQDF16TC3GM4S' AND city = '—';
UPDATE pharmacies SET city = 'Водник', updated_at = now() WHERE id = 'sloc_01KSAHYDSPA5SA3MRBHRTY6HPW' AND city = '—';
UPDATE pharmacies SET city = 'Конаев', updated_at = now() WHERE id = 'sloc_01KSAHYDSDXHF1V3EXPV10VYDJ' AND city = '—';
UPDATE pharmacies SET city = 'Конаев', updated_at = now() WHERE id = 'sloc_01KSAHYDSDFMX7B22Q18VQ97JZ' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSER81E6532HNPAJ8VX' AND city = '—';
UPDATE pharmacies SET city = 'Есик', updated_at = now() WHERE id = 'sloc_01KSAHYDSD79FH3X27DPKW3D1H' AND city = 'имова';
UPDATE pharmacies SET city = 'Алматинская область', updated_at = now() WHERE id = 'sloc_01KSAHYDSEREH6WNSZH3JQ4J57' AND city = 'урова';
UPDATE pharmacies SET city = 'Астана', updated_at = now() WHERE id = 'sloc_01KSBH8M01GFASVNMKB2V81X41' AND city = 'елик';

-- 3. Misspelt city names.
UPDATE pharmacies SET city = 'Усть-Каменогорск', updated_at = now() WHERE city = 'Усть-каменагорск';
UPDATE pharmacies SET city = 'Усть-Каменогорск', updated_at = now() WHERE city = 'Усть-Каменагорск';
UPDATE pharmacies SET city = 'Жезказган', updated_at = now() WHERE city = 'Жезгазкан';
UPDATE pharmacies SET city = 'Конаев', updated_at = now() WHERE city = 'Кунаева';

-- 4. Closed pharmacies ("закрыта" in the name) with no till key and no pharmacists: switch off.
--    Closed-looking names that still have a till key are left for a check with the network.
UPDATE pharmacies p SET active = false, updated_at = now()
WHERE p.id IN ('sloc_01KSBH8M075A0WRTF1SXT6XPFY',
               'sloc_01KSBH8M08E25N0AKBKSGFS00G',
               'sloc_01KSBH8M08SJNS8KGMTHRQAVA0',
               'sloc_01KSBH8M08TG18FQ5KNTG2M29R',
               'sloc_01KSBRMS3ACBVBTYKHGTTZ2R5F')
  AND p.active AND p.name ~* 'закрыт'
  AND NOT EXISTS (SELECT 1 FROM fulfillment_devices d WHERE d.pharmacy_id = p.id AND d.active)
  AND NOT EXISTS (SELECT 1 FROM pharmacists x WHERE x.pharmacy_id = p.id);
