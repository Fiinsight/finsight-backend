# 판단 피드백 읽기 호환

`feedback_reasons` JSONB를 원본 JsonNode로 읽은 뒤 Jackson deserializer로 변환합니다. 객체는 구조를 유지하고 문자열 배열은 `{title:"근거", detail:원래문자열}` 목록으로 변환합니다. 나머지 형식은 null로 반환하며 판단 ID만 경고 한 줄에 남깁니다. 본문/개인 근거를 로그에 출력하지 않습니다. 정상적인 새 저장은 계속 객체 JSONB입니다. DB 마이그레이션·기존 데이터 정정은 없습니다.

2026-10-13 로컬 DB 읽기 전용 조사: 문자열 배열 8건, 객체 1건, SQL NULL 19건. 사용자 8의 판단은 5건입니다. 운영 DB는 조사하지 않았습니다.

```sql
SELECT coalesce(jsonb_typeof(feedback_reasons), 'SQL_NULL') AS format, count(*)
FROM judgement GROUP BY 1 ORDER BY 1;

SELECT count(*) AS legacy_string_arrays FROM judgement
WHERE jsonb_typeof(feedback_reasons) = 'array'
  AND NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(
      CASE WHEN jsonb_typeof(feedback_reasons)='array' THEN feedback_reasons ELSE '[]'::jsonb END
    ) element WHERE jsonb_typeof(element) <> 'string'
  );
```

테스트: 객체/문자열 배열/깨진 값, ID 포함 단일 경고, PostgreSQL에 5개 혼합 형식 행을 넣고 인증된 MockMvc에서 history·history/page·상세 200 및 전체 5건 확인. 신규 구조화 피드백의 조건부 UPDATE가 실제 JSONB 객체로 저장되는 것도 확인합니다. 테스트 데이터는 별도 PostgreSQL 트랜잭션에서 롤백됩니다.

추가로 로컬 실제 DB에 읽기 전용 JDBC 옵션을 강제한 Hibernate 조회를 실행해 사용자 8의 5행을 모두 로드하고 피드백 2행을 변환했습니다. 나머지는 피드백 null이며, MismatchedInputException 없이 처리됐습니다. 이 조회에서 DB write는 실행하지 않았습니다.
