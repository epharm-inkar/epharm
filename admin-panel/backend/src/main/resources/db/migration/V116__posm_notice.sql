-- ePharm on the tills can be put «на технические работы» from HQ (decision of 25.11.2026): the ePharm icon then
-- opens a notice instead of the personal window and no recommendation pops up. Tills of the POSM pilot channel
-- (POSM_UPDATE_PILOT_DEVICES) stay live while pilot_live is on, so a pilot can run during the works.
-- When maintenance is switched off, every till (POSM 1.0.95+) shows «ePharm снова работает» once, also a till that
-- was off at that moment. One row; the change is visible on the tills within about two minutes.
CREATE TABLE posm_notice (
    id          SMALLINT     PRIMARY KEY CHECK (id = 1),
    maintenance BOOLEAN      NOT NULL,
    pilot_live  BOOLEAN      NOT NULL,
    title       VARCHAR(120) NOT NULL CHECK (length(btrim(title)) > 0),
    message     VARCHAR(600) NOT NULL CHECK (length(btrim(message)) > 0),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(255)
);

INSERT INTO posm_notice (id, maintenance, pilot_live, title, message)
VALUES (1, true, true, 'ePharm на технических работах',
        'Сейчас ePharm недоступен: идут технические работы. Пожалуйста, подождите. Когда ePharm снова заработает, на этом компьютере появится уведомление.');
