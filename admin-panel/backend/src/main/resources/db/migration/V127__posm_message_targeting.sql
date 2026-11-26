-- V127: notices from HQ get two more ways to choose people, and HQ can see who is at a till (Grigoriy, 26.11.2026).
--   1. «course»: the pharmacists who have not finished a chosen course. HQ presses one button and every such person
--      gets «пройдите курс» on the till they work at.
--   2. «only_at_till»: a switch on any audience, so that the notice goes only to the people who sit at a till at the
--      moment HQ presses «Отправить» (the till is online and its Standard-N user was seen in the last 20 minutes).
-- A course notice and an «only at till» notice are frozen when they are sent: posm_message_recipients keeps the people
-- who were chosen then, so people who join the audience later do not get a notice that was meant for someone else.
-- The other notices keep their rule as it was (V122), nothing here changes them.
-- pharmacist_till_seen keeps the last time each pharmacist was seen at a till, for the roster («был за кассой 26.11 14:05»).

-- The foreign keys lock pharmacists and training_programs briefly: wait at most 5 seconds, then fail and retry the
-- release instead of queueing behind a long transaction while tills write sales.
SET LOCAL lock_timeout = '5s';

ALTER TABLE posm_messages DROP CONSTRAINT IF EXISTS posm_messages_audience_check;
ALTER TABLE posm_messages
    ADD CONSTRAINT ck_posm_messages_audience CHECK (audience IN ('all', 'pharmacies', 'pharmacists', 'course'));

-- The chosen course (audience «course» only). The name is kept as it was written, so the list of sent notices still
-- says which course it was about when HQ renames the program.
ALTER TABLE posm_messages ADD COLUMN program_id UUID REFERENCES training_programs (id);
ALTER TABLE posm_messages ADD COLUMN program_name VARCHAR(255);
ALTER TABLE posm_messages ADD COLUMN only_at_till BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE posm_messages
    ADD CONSTRAINT ck_posm_messages_course CHECK ((audience = 'course') = (program_id IS NOT NULL));

-- Whether the people were chosen once, at sending, and kept in posm_message_recipients.
ALTER TABLE posm_messages
    ADD COLUMN frozen BOOLEAN GENERATED ALWAYS AS (only_at_till OR audience = 'course') STORED;

-- The people a frozen notice was sent to.
CREATE TABLE posm_message_recipients (
    message_id    UUID        NOT NULL REFERENCES posm_messages (id) ON DELETE CASCADE,
    pharmacist_id VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, pharmacist_id)
);

CREATE INDEX ix_posm_message_recipients_pharmacist ON posm_message_recipients (pharmacist_id);

-- The last time a pharmacist was seen at a till: the Standard-N user a till named in its check (every 15 minutes and
-- when the cashier changes). One row per pharmacist, written at most once in five minutes.
CREATE TABLE pharmacist_till_seen (
    pharmacist_id VARCHAR(64)  PRIMARY KEY REFERENCES pharmacists (id) ON DELETE CASCADE,
    seen_at       TIMESTAMPTZ  NOT NULL,
    pharmacy_id   VARCHAR(64)  NOT NULL,
    device_id     VARCHAR(128)
);
