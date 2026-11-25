-- Question bank for course tests (Grigoriy, 25.11.2026: «разные вопросы, перемешанный порядок»):
-- how many questions one attempt draws from the lesson's questions; null asks every question.
alter table course_lessons
    add column quiz_draw_count integer,
    add constraint ck_course_lessons_quiz_draw_count check (quiz_draw_count is null or quiz_draw_count > 0);
