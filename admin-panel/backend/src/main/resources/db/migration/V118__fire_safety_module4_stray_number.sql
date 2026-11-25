-- Fire-safety course, module 4 («Средства пожарной безопасности»): the lesson text began with a
-- stray «8» line, which the pharmacist cabinet showed as a separate «Введение» section holding only
-- «8» (Grigoriy, 25.11.2026). Only that leading line is removed; the rest of the text is unchanged.
update course_lessons
   set content = regexp_replace(content, '^\s*8\s*\n+', ''),
       updated_at = now()
 where id = 'cls_3c3375277f7342ff'
   and content ~ '^\s*8\s*\n';
