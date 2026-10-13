#!/usr/bin/env python3
"""Read-only regrade preview. Never starts servers, fetches prices, or writes records."""
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'seed_demo_personas'))
from seed import connection, rows

ROOT = Path(__file__).resolve().parents[1]
ELIGIBLE = "j.feedback_text IS NULL OR trim(j.feedback_text)='' OR j.actual_direction='UNKNOWN' OR j.comparison_date IS NULL"


def snapshot(env):
    columns = {row['column_name'] for row in rows(env, "SELECT column_name FROM information_schema.columns WHERE table_name='judgement'")}
    target = "coalesce(j.target_symbol,n.related_symbol)" if 'target_symbol' in columns else 'n.related_symbol'
    records = rows(env, "SELECT j.id,j.user_id,j.news_id,j.choice,j.reason_text,j.created_at,n.title,n.url,n.source,n.raw_content,n.related_symbol,n.sentiment_hint,n.sentiment_confidence,u.learning_level FROM judgement j JOIN news n ON n.id=j.news_id JOIN app_user u ON u.id=j.user_id WHERE (" + ELIGIBLE + ") ORDER BY j.id")
    prices = rows(env, "SELECT " + target + " AS symbol,(j.created_at AT TIME ZONE 'Asia/Seoul')::date AS baseline_date,j.comparison_date,j.judgement_close,j.comparison_close FROM judgement j JOIN news n ON n.id=j.news_id WHERE j.comparison_date IS NOT NULL AND j.judgement_close>0 AND j.comparison_close>0 AND coalesce(n.source,'') NOT LIKE 'SYNTHETIC%' AND n.url NOT LIKE '%example.invalid%' AND coalesce(j.feedback_text,'') NOT LIKE '%가상%'")
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


def report(plans):
    print('DRY-RUN: DB 변경·시세/AI 호출 0건')
    print('| 판단 id | 새 대상 | 판단 기준일 → 결과일 | 예정 상태 |')
    print('|---|---|---|---|')
    for row in plans:
        print(f"| {row['id']} | {row['target'] or '대상 없음'} | {row['baselineDate']} → {row['comparisonDate']} | {row['status']} |")
    print(f"대상 {len(plans)}건 / 저장 종가로 재채점 가능 {sum(p['status']=='READY' for p in plans)}건 / 종가 부족 {sum(p['status']=='NO_SAVED_CLOSES' for p in plans)}건 / 대상 없음 {sum(p['status']=='NO_TARGET' for p in plans)}건 / 다음 거래일 대기 {sum(p['status']=='NOT_DUE' for p in plans)}건")


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true', help='기본 동작. 기존 데이터는 변경하지 않음')
    parser.parse_args()  # --apply is deliberately unavailable until a separate approval.
    try: report(preview(snapshot(connection())))
    except Exception as error:
        print(type(error).__name__ + ': dry-run을 완료하지 못했습니다. 연결 정보는 출력하지 않습니다.', file=sys.stderr)
        sys.exit(1)
