#!/usr/bin/env python3
"""Approved local demo only. Never runs on import or without explicit confirmation."""
import argparse
from datetime import datetime, timedelta
import json
import os
import re
import subprocess
from zoneinfo import ZoneInfo

KST = ZoneInfo('Asia/Seoul')

def validate(host, database, email):
    if host not in ('localhost', '127.0.0.1', '::1') or database != 'finsight_demo':
        raise ValueError('Only loopback and the separate finsight_demo database are allowed.')
    if not email.endswith('@example.invalid') or not email.startswith(('qa-', 'finsight-demo')):
        raise ValueError('Only clearly labelled QA/demo email addresses are allowed.')

def quote(value):
    return "'" + str(value).replace("'", "''") + "'"

def query(args, database, sql):
    env = os.environ.copy()
    for key in ('PGSERVICE', 'PGSERVICEFILE'):
        env.pop(key, None)
    env['PGOPTIONS'] = '-c statement_timeout=5000 -c lock_timeout=5000'
    result = subprocess.run(['psql', '-X', '-A', '-t', '--no-password', '-v', 'ON_ERROR_STOP=1',
                             '-h', args.host, '-p', '5432', '-U', args.user, '-d', database],
                            input=sql, env=env, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Local demo SQL failed; transaction rolled back. Connection details withheld.')
    return result.stdout.strip()

def check_server(args, database):
    identity = query(args, database, "SELECT current_database() || '|' || inet_server_addr()::text;")
    if identity not in (database + '|127.0.0.1', database + '|::1'):
        raise ValueError('Server must identify itself as a loopback PostgreSQL server.')

def build_sql(email, password_hash):
    if not re.fullmatch(r'\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}', password_hash):
        raise ValueError('Source must be a local QA account with a BCrypt password.')
    today = datetime.now(KST).replace(hour=10, minute=0, second=0, microsecond=0)
    statements = ['BEGIN;', "SET LOCAL TIME ZONE 'Asia/Seoul';"]
    answers = [dict(questionId='experience', question='지금 투자 여정을 어디쯤 걷고 있나요?', answer='조금씩 알아가고 있어요'),
               dict(questionId='interest', question='투자할 때 가장 알고 싶은 것은 무엇인가요?', answer='내 투자 기록을 돌아보고 싶어요'),
               dict(questionId='pace', question='나에게 맞는 투자 공부 방식은 어떤 모습인가요?', answer='짧게, 매일 조금씩'),
               dict(questionId='goal', question='오늘 핀사이트에서 어떤 습관을 시작해볼까요?', answer='기사 메모 남기고 복습하기')]
    statements.append(f"INSERT INTO app_user(email,nickname,password_hash,provider,created_at,onboarding_answers_json,learning_level,learning_pace,learning_focus,daily_goal,onboarding_completed_at) VALUES ({quote(email)},'시연용 가상 학습 계정',{quote(password_hash)},'LOCAL',now(),{quote(json.dumps(answers, ensure_ascii=False))},'normal','short','routine','기사 메모 남기고 복습하기',now());")
    scenarios = [(1, 'UP', 'UP', 1.2, '환율', False), (3, 'UP', 'DOWN', -0.8, '금리', True), (15, 'NEUTRAL', 'NEUTRAL', 0.2, '영업이익', False)]
    for index, (days, choice, actual, change, term, correct) in enumerate(scenarios, 1):
        date = (today - timedelta(days=days)).isoformat()
        url = f'https://example.invalid/finsight-demo-{index}'
        title = f'[시연용 가상 기사] {term} 이해와 판단 연습'
        body = f'실제 뉴스나 투자 자료가 아닌 시연용 가상 상황입니다. {term}과 기업의 실적을 비교하는 학습 예시이며 숫자와 결과는 실제 시세가 아닙니다.'
        statements.append(f"INSERT INTO news(title,url,source,published_at,raw_content,category,created_at) VALUES ({quote(title)},{quote(url)},'SYNTHETIC_QA',{quote(date)},{quote(body)},'시연용',now());")
        select = f"FROM app_user u CROSS JOIN news n WHERE u.email={quote(email)} AND n.url={quote(url)}"
        statements.append(f"INSERT INTO judgement(user_id,news_id,choice,reason_text,created_at,actual_direction,actual_change_percent,feedback_text,feedback_generated_at) SELECT u.id,n.id,{quote(choice)},'[시연] 가상 시세와 예측 방향을 비교합니다.',{quote(date)},{quote(actual)},{change},'[시연용 가상 결과] 실제 투자 성과가 아닙니다. 판단 근거와 가정을 다시 비교해보세요.',{quote(date)} {select};")
        statements.append(f"INSERT INTO article_note(user_id,news_id,content,created_at,updated_at) SELECT u.id,n.id,'[시연] 기사에서 사실과 가정을 구분하는 연습 메모입니다.',{quote(date)},{quote(date)} {select};")
        statements.append(f"INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) SELECT u.id,n.id,{quote(term)},'normal',{str(correct).lower()},{quote(date)} {select};")
    statements.append('COMMIT;')
    return '\n'.join(statements)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--database', default='finsight_demo')
    parser.add_argument('--user', default=os.environ.get('PGUSER', 'finsight'))
    parser.add_argument('--source-database', choices=('finsight', 'finsight_demo'), default='finsight')
    parser.add_argument('--source-email')
    parser.add_argument('--email', default='finsight-demo@example.invalid')
    parser.add_argument('--confirm-local-demo', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        validate('127.0.0.1', 'finsight_demo', 'finsight-demo@example.invalid')
        for host, database in [('db.example.com', 'finsight_demo'), ('127.0.0.1', 'finsight')]:
            try: validate(host, database, 'finsight-demo@example.invalid')
            except ValueError: pass
            else: raise AssertionError('Unsafe destination accepted')
        sql = build_sql('finsight-demo@example.invalid', '$2a$10$' + 'a' * 53)
        assert sql.startswith('BEGIN;') and sql.endswith('COMMIT;')
        assert sql.count('INSERT INTO judgement') == sql.count('INSERT INTO article_note') == sql.count('INSERT INTO learning_progress') == 3
        assert 'UPDATE ' not in sql and 'DELETE ' not in sql
        print('Local-only guards and transactional fixture generation passed; no database accessed.')
        return
    validate(args.host, args.database, args.email)
    if not args.confirm_local_demo or not args.source_email:
        parser.error('User approval and --confirm-local-demo --source-email are required.')
    validate(args.host, args.database, args.source_email)
    check_server(args, args.source_database)
    check_server(args, args.database)
    password_hash = query(args, args.source_database, f"SELECT password_hash FROM app_user WHERE provider='LOCAL' AND email={quote(args.source_email)};")
    # Unique constraints intentionally reject re-runs; existing accounts/news are never overwritten.
    query(args, args.database, build_sql(args.email, password_hash))
    print('Created one isolated demo account, three judgements, three notes and three quiz records.')

if __name__ == '__main__':
    main()
