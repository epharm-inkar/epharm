-- Fire-safety course (crs_9b5f8443e44b): every module opens with its own short video, and the general video lesson
-- gets the new summary (INK-156, Grigoriy 25.11.2026: new videos, no skipping, no speeding up). The text and the
-- self-check of each module stay as they are: a module is now video, then reading, then self-check.
-- The videos live in MinIO (bucket epharm-receipts, training/fire-safety/, uploaded 26.11.2026 by
-- knowledge/epharm/tools/upload-course-videos.sh; module videos 48-78 s, summary 90 s).
-- Lesson minutes follow the video length: the server's watch guard (TrainingService.videoGuardSeconds) accepts the
-- browser's measured length only between half and double of them, so «5 min» on a one-minute clip would never let
-- the lesson complete. 95% must be watched, as on the course's other video.
-- Rows are matched by id; nothing happens where the course does not exist.
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-1.mp4'
 where id = 'cls_77d2a685d8df48e6';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-2.mp4'
 where id = 'cls_8f30d2c5a83948c4';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-3.mp4'
 where id = 'cls_606c4fb752a04dea';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-4.mp4'
 where id = 'cls_3c3375277f7342ff';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-5.mp4'
 where id = 'cls_82dca718c01244b7';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-6.mp4'
 where id = 'cls_4f84b2f110d74b7a';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-7.mp4'
 where id = 'cls_b552ce0599cd4133';
update course_lessons set kind = 'video', duration_min = 1, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/module-8.mp4'
 where id = 'cls_a96a589e7a7c452c';
-- The general video lesson: the summary of the whole course replaces the old clip.
update course_lessons set duration_min = 2, minimum_watch_pct = 95, updated_at = now(),
    video_url = 'https://epharm.inkar.kz/s3/epharm-receipts/training/fire-safety/summary.mp4'
 where id = 'cls_ad5af10cc5b449b7';
