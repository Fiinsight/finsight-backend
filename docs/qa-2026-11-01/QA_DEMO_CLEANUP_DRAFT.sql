-- 검토용 SQL 초안. 실행 금지: 운영 DB 쓰기·삭제·백필은 이번 범위 밖입니다.
-- 먼저 사용자별 개수만 확인하고, 실제 정리 대상 ID와 보존 정책은 사용자가 결정합니다.
BEGIN TRANSACTION READ ONLY;
SELECT user_id, count(*) AS example_records FROM judgement
WHERE feedback_text LIKE '%[시연용 가상 결과]%' GROUP BY user_id;
COMMIT;
-- 삭제는 계정 소유자·레코드 ID를 확정한 별도 승인 후에만 작성/실행합니다.
