#!/usr/bin/env python3
"""Preview legacy UNKNOWN regrades; only --apply writes READY outcomes. No servers, prices or AI calls."""
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'seed_demo_personas'))
from seed import connection, rows, query, quote

ROOT = Path(__file__).resolve().parents[1]
ELIGIBLE = "j.actual_direction='UNKNOWN' AND j.comparison_date IS NULL AND coalesce(n.title,'') NOT LIKE '%로컬 QA%'"


def snapshot(env):
    env = dict(env, PGTZ='UTC')  # Keep JSON timestamp strings stable for the optimistic update guard.
    columns = {row['column_name'] for row in rows(env, "SELECT column_name FROM information_schema.columns WHERE table_name='judgement'")}
    target = "coalesce(j.target_symbol,n.related_symbol)" if 'target_symbol' in columns else 'n.related_symbol'
    records = rows(env, "SELECT j.id,j.user_id,j.news_id,j.choice,j.reason_text,j.created_at,to_jsonb(j) AS expected_judgement,to_jsonb(n) AS expected_news,n.title,n.url,n.source,n.raw_content,n.related_symbol,n.sentiment_hint,n.sentiment_confidence,u.learning_level FROM judgement j JOIN news n ON n.id=j.news_id JOIN app_user u ON u.id=j.user_id WHERE (" + ELIGIBLE + ") ORDER BY j.id")
    prices = rows(env, "SELECT " + target + " AS symbol,(j.created_at AT TIME ZONE 'Asia/Seoul')::date AS baseline_date,j.comparison_date,j.judgement_close,j.comparison_close FROM judgement j JOIN news n ON n.id=j.news_id WHERE j.comparison_date IS NOT NULL AND j.judgement_close>0 AND j.comparison_close>0 AND coalesce(n.source,'') NOT LIKE 'SYNTHETIC%' AND n.url NOT LIKE '%example.invalid%' AND coalesce(j.feedback_text,'') NOT LIKE '%가상%' AND coalesce(n.title,'') NOT LIKE '%로컬 QA%'")
    saved = []
    for row in prices:
        saved.extend([{'symbol': row['symbol'], 'day': row['baseline_date'], 'close': row['judgement_close']}, {'symbol': row['symbol'], 'day': row['comparison_date'], 'close': row['comparison_close']}])
    return {'judgements': records, 'prices': saved, 'terms': rows(env, "SELECT term,short_definition FROM term WHERE kind='CONCEPT'"), 'mastery': rows(env, "SELECT user_id,term,p_known,attempts,last_answered_at FROM term_mastery")}


def preview(payload):
    runtime = os.environ.copy()
    cached = sorted((Path.home()/'.gradle/jdks').glob('*21*/**/Contents/Home/bin/java'))
    if cached: runtime['JAVA_HOME'] = str(cached[0].parents[1])
    result = subprocess.run([str(ROOT/'gradlew'), '-q', 'judgementRegradePlan', '--console=plain'], cwd=ROOT,
                            input=json.dumps(payload), text=True, capture_output=True, env=runtime)
    marker = next((line[len('REGRADE_JSON:'):] for line in result.stdout.splitlines() if line.startswith('REGRADE_JSON:')), None)
    if result.returncode or marker is None: raise RuntimeError('읽기 전용 계획 생성 실패. 상세 출력은 숨깁니다.')
    return json.loads(marker)


def report(plans, records=()):
    print('DRY-RUN: DB 변경·시세/AI 호출 0건')
    print('| 판단 id | 새 대상 | 판단 기준일 → 결과일 | 예정 상태 |')
    print('|---|---|---|---|')
    for row in plans:
        print(f"| {row['id']} | {row['target'] or '대상 없음'} | {row['baselineDate']} → {row['comparisonDate']} | {row['status']} |")
    originals = {row['id']: row.get('expected_judgement', {}) for row in records}
    for row in plans:
        if row['status'] == 'READY':
            changes = {column: {'before': originals.get(row['id'], {}).get(column), 'after': value}
                       for column, value in changes_for(row).items()}
            print(f"변경 예상 #{row['id']}: " + json.dumps(changes, ensure_ascii=False, allow_nan=False))
    print(f"대상 {len(plans)}건 / 저장 종가로 재채점 가능 {sum(p['status']=='READY' for p in plans)}건 / 종가 부족 {sum(p['status']=='NO_SAVED_CLOSES' for p in plans)}건 / 대상 없음 {sum(p['status']=='NO_TARGET' for p in plans)}건 / 다음 거래일 대기 {sum(p['status']=='NOT_DUE' for p in plans)}건")


def changes_for(plan):
    feedback = plan['feedback']
    return dict(target_symbol=plan['target'], actual_direction=plan['actualDirection'],
                actual_change_percent=plan['actualChangePercent'], comparison_date=plan['comparisonDate'],
                judgement_close=plan['judgementClose'], comparison_close=plan['comparisonClose'],
                feedback_text=feedback['summary'], feedback_headline=feedback['headline'],
                feedback_reasons=dict(verdict=feedback['verdict'], reasons=feedback['reasons'], nextTip=feedback['nextTip'],
                                     conceptTerm=feedback.get('conceptTerm'), conceptDefinition=feedback.get('conceptDefinition'),
                                     indexName=plan.get('indexName'), indexChangePercent=plan.get('indexChangePercent')))


def apply_ready(env, payload, plans):
    records = {row['id']: row for row in payload['judgements']}
    ready = [row for row in plans if row['status'] == 'READY']
    if not ready: return 0
    if len({row['id'] for row in ready}) != len(ready): raise ValueError('Duplicate judgement IDs')
    statements = ["BEGIN;", "SET LOCAL standard_conforming_strings=on;", "SET LOCAL TIME ZONE 'UTC';"]
    for plan in ready:
        original = records[plan['id']]
        saved = original['expected_judgement']
        article = original['expected_news']
        if saved.get('actual_direction') != 'UNKNOWN' or saved.get('comparison_date') is not None or '로컬 QA' in article['title']:
            raise ValueError('Only legacy UNKNOWN, non-QA records may be applied')
        changes = changes_for(plan)
        values = []
        for column, value in changes.items():
            if column == 'feedback_reasons': value = json.dumps(value, ensure_ascii=False, allow_nan=False)
            values.append(column + '=' + quote(value) + ('::jsonb' if column == 'feedback_reasons' else ''))
        expected = quote(json.dumps(saved, ensure_ascii=False, allow_nan=False))
        expected_news = quote(json.dumps(article, ensure_ascii=False, allow_nan=False))
        # A concurrent edit or another apply must abort the entire batch, including earlier updates.
        statements.append("WITH updated AS (UPDATE judgement j SET " + ','.join(values)
                          + f" FROM news n WHERE j.id={int(plan['id'])} AND j.news_id=n.id AND ({ELIGIBLE})"
                          + f" AND to_jsonb(j)={expected}::jsonb AND to_jsonb(n)={expected_news}::jsonb RETURNING j.id)"
                          + " SELECT 1 / CASE WHEN count(*)=1 THEN 1 ELSE 0 END FROM updated;")
    statements.append('COMMIT;')
    query(dict(env, PGTZ='UTC'), '\n'.join(statements), write=True)
    return len(ready)


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true', help='기본 동작. 변경 전후만 출력하며 DB 쓰기 없음')
    mode.add_argument('--apply', action='store_true', help='로컬 QA 제외, UNKNOWN+비교일 없음+READY만 조건부 적용')
    args = parser.parse_args(argv)
    try:
        env = connection()
        payload = snapshot(env)
        plans = preview(payload)
        report(plans, payload['judgements'])
        if args.apply: print(f"APPLIED: {apply_ready(env, payload, plans)}건")
        return 0
    except Exception as error:
        print(type(error).__name__ + ': 재채점을 완료하지 못했습니다. 연결 정보와 SQL은 출력하지 않습니다.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
