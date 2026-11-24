-- Pharmacies whose ePharm name does not match their Standard-N profile caption (V112 links by caption only).
-- Each link was checked on 24.11.2026: receipts that reached ePharm from the pharmacy were found in the central
-- Standard-N database in this profile at the same minute, with the same amount and the same cashier.
-- 'manual' links are never overwritten by StandardNProfileLinker.
UPDATE pharmacies SET standardn_profile_id = v.profile_id, standardn_profile_source = 'manual'
FROM (VALUES
    ('ph_6582f9ff', 578),                     -- Жетысу 2, 85: profile «Лимон Фарм … Жетысу 2, 85» (5 of 5 receipts)
    ('ph_096cf002', 588),                     -- Сейфуллина 574/2: profile spelled «Сейфулина» (5 of 5)
    ('ph_17459c55', 595),                     -- Талдыкорган, Жансугурова 71 (5 of 5)
    ('sloc_01KSAHYDSDQFHX0S6NA3D25T63', 400), -- Кызылорда, «Калымбекова/Калымбетова» 2Б (5 of 5)
    ('sloc_01KSAHYDSD96YH17NZ08YHA8SS', 423), -- Астана, Абылай хана 26 (5 of 5)
    ('sloc_01KSAHYDSE0X11YTHQH1YARX46', 198), -- Астана, Айтматова 60 (4 of 5)
    ('sloc_01KSAHYDSBZ0GYCBDE4WP0D3KF', 336), -- Есик, Ыскака 76д (3 of 5)
    -- The tills registered to these two ePharm pharmacies write receipts into another pharmacy's database:
    -- the pharmacy record itself needs fixing (closed pharmacy, tills moved), the link follows the tills.
    ('sloc_01KSAHYDSE1GZKTGKS04W2VTKT', 590), -- registered «Ауэзова 163а» (closed), receipts in «Айнабулак 3 д 143» (5 of 5)
    ('sloc_01KSBH8KZZ65JKKZ6052KMCBT9', 593)  -- registered «Уральск, Акжайык 1А» (closed), receipts in «Актау 30 мкр 170» (5 of 5)
) AS v(pharmacy_id, profile_id)
WHERE pharmacies.id = v.pharmacy_id;
