-- 초안 전용. 이 작업에서 실행하지 않았습니다. SELECT만 포함하며 트랜잭션도 READ ONLY입니다.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '15s';
SELECT (published_at AT TIME ZONE 'Asia/Seoul')::date AS day,
       rewrite_mode, count(*) AS articles,
       count(*) FILTER (WHERE rewrite_retry_pending) AS retry_pending
FROM news WHERE published_at >= now() - interval '14 days'
GROUP BY 1,2 ORDER BY 1 DESC,2;
SELECT sentiment_hint,
       CASE WHEN sentiment_confidence IS NULL THEN 'NULL'
            WHEN sentiment_confidence < 0.5 THEN '<0.5'
            WHEN sentiment_confidence < 0.8 THEN '0.5..0.8' ELSE '>=0.8' END AS confidence_band,
       count(*) FROM news WHERE published_at >= now() - interval '14 days'
GROUP BY 1,2 ORDER BY 1,2;
SELECT actual_direction, count(*) FROM judgement GROUP BY actual_direction;
SELECT count(*) AS older_than_two_days_pending, max(created_at) AS newest_pending
FROM judgement WHERE actual_direction IS NULL AND feedback_generated_at IS NULL
AND created_at < now() - interval '2 days';
SELECT max(feedback_generated_at) AS last_feedback_generated_at FROM judgement;
SELECT count(*) AS synthetic_demo_feedback_count FROM judgement
WHERE feedback_text LIKE '%[시연용 가상 결과]%';
COMMIT;
