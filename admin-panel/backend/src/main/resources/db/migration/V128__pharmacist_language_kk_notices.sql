-- Kazakh for pharmacists (owner, 26.11.2026): «наши интерфейсы должны быть не только на русском, но и на казахском…
-- и уведомления на казахском». The cabinet keeps the language the pharmacist chose on the card; an HQ notice may carry
-- a Kazakh title and text, and tills show them to the people who chose Kazakh (Russian otherwise).

-- NULL = not chosen yet (the cabinet then saves what the pharmacist already uses there). A nullable column without a
-- default is added without rewriting the table.
ALTER TABLE pharmacists ADD COLUMN language VARCHAR(2);
ALTER TABLE pharmacists ADD CONSTRAINT ck_pharmacists_language CHECK (language IS NULL OR language IN ('ru', 'kk'));

ALTER TABLE posm_messages
    ADD COLUMN title_kk VARCHAR(120),
    ADD COLUMN body_kk  VARCHAR(1000);
-- Both or neither: a Kazakh title without a text (or the other way round) is not a notice.
ALTER TABLE posm_messages ADD CONSTRAINT ck_posm_messages_kk CHECK (
    (title_kk IS NULL AND body_kk IS NULL)
    OR (title_kk IS NOT NULL AND body_kk IS NOT NULL AND length(btrim(title_kk)) > 0 AND length(btrim(body_kk)) > 0)
);
