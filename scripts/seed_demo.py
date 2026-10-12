#!/usr/bin/env python3
"""Local-only demo seed. Execution and cleanup require separate user approval."""
import argparse
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from zoneinfo import ZoneInfo

EMAIL = 'finsight-demo@finsight.test'
NICKNAME = '시연용 가상 학습 계정'
ROOT = Path(__file__).resolve().parents[2]
CREDENTIALS = ROOT / '.env.demo.local'
KST = ZoneInfo('Asia/Seoul')
SCENARIOS = [(1,'UP','UP',1.2,False), (3,'UP','DOWN',-0.8,True), (14,'NEUTRAL','NEUTRAL',0.2,False)]

def validate(host, database):
    if host not in ('localhost', '127.0.0.1', '::1') or database != 'finsight':
        raise ValueError('Only the loopback finsight development database is allowed.')

def quote(value):
    return "'" + str(value).replace("'", "''") + "'"

def query(args, sql):
    env = os.environ.copy()
    for key in ('PGSERVICE', 'PGSERVICEFILE'):
        env.pop(key, None)
    env['PGOPTIONS'] = '-c statement_timeout=5000 -c lock_timeout=5000'
    result = subprocess.run(['psql', '-X', '-A', '-t', '--no-password', '-v', 'ON_ERROR_STOP=1',
                             '-h', args.host, '-p', '5432', '-U', args.user, '-d', args.database],
                            input=sql, env=env, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Local demo SQL failed. Database output and connection details withheld.')
    return result.stdout.strip()

def bcrypt(password):
    jars = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1/org.springframework.security/spring-security-crypto').glob('*/*/*.jar'))
    if not jars:
        raise RuntimeError('The already-installed Spring Security BCrypt jar is required.')
    env = dict(os.environ, FINSIGHT_DEMO_PASSWORD=password)
    result = subprocess.run(['jshell', '--feedback', 'silent', '--class-path', str(jars[-1])],
        input='System.out.println("HASH:" + org.springframework.security.crypto.bcrypt.BCrypt.hashpw(System.getenv("FINSIGHT_DEMO_PASSWORD"), org.springframework.security.crypto.bcrypt.BCrypt.gensalt(10)));\n/exit\n',
        text=True, capture_output=True, env=env)
    match = re.search(r'HASH:(\$2[aby]\$10\$[./A-Za-z0-9]{53})', result.stdout)
    if result.returncode or not match:
        raise RuntimeError('BCrypt hashing failed; subprocess output withheld.')
    return match.group(1)

def credential_password():
    # Called only after approval; never overwrite an existing credential file.
    ignore = (ROOT / '.gitignore').read_text().splitlines()
    if '.env.*' not in ignore and '.env.demo.local' not in ignore:
        raise ValueError('Demo credential file must be gitignored.')
    if CREDENTIALS.exists() or CREDENTIALS.is_symlink():
        if CREDENTIALS.is_symlink() or stat.S_IMODE(CREDENTIALS.stat().st_mode) != 0o600:
            raise ValueError('Existing demo credentials must be a regular private 0600 file.')
        values = dict(line.split('=', 1) for line in CREDENTIALS.read_text().splitlines() if '=' in line)
        password = values.get('DEMO_PASSWORD', '')
        if values.get('DEMO_EMAIL') != EMAIL or not re.fullmatch(r'[A-Za-z0-9_-]{32}', password):
            raise ValueError('Existing demo credentials are not recognized; file left unchanged.')
        return password
    password = secrets.token_urlsafe(24)
    fd = os.open(CREDENTIALS, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as target:
        target.write(f'DEMO_EMAIL={EMAIL}\nDEMO_PASSWORD={password}\n')
    return password

def account_predicate():
    return f"email={quote(EMAIL)} AND nickname={quote(NICKNAME)} AND provider='LOCAL'"

def scenario_dates(today=None):
    today = (today or datetime.now(KST)).astimezone(KST).replace(hour=23, minute=59, second=59, microsecond=0)
    return [today - timedelta(days=scenario[0]) for scenario in SCENARIOS]


def select_articles(args, dates):
    articles = []
    for date in dates:
        excluded = ','.join(str(int(article['id'])) for article in articles) or '0'
        raw = query(args, f"""SELECT coalesce(json_agg(a), '[]'::json)::text FROM (
            SELECT n.id, n.published_at AS "publishedAt", (SELECT t.term FROM term t
                WHERE position(t.term in coalesce(n.title,'') || ' ' || coalesce(n.raw_content,'')) > 0
                  AND coalesce(t.short_definition,'') <> ''
                ORDER BY (position(t.term in n.title) > 0) DESC, length(t.term) DESC, t.term LIMIT 1) AS term
            FROM news n WHERE coalesce(n.source,'') NOT LIKE 'SYNTHETIC%' AND n.url ~ '^https?://'
                AND n.url NOT LIKE '%example.invalid%' AND length(coalesce(n.raw_content,'')) >= 120
                AND n.published_at < {quote(date.isoformat())} AND n.id NOT IN ({excluded})
                AND EXISTS (SELECT 1 FROM term t WHERE coalesce(t.short_definition,'') <> ''
                    AND position(t.term in coalesce(n.title,'') || ' ' || coalesce(n.raw_content,'')) > 0)
            ORDER BY n.published_at DESC, n.id DESC LIMIT 1) a;""")
        rows = json.loads(raw)
        if len(rows) != 1:
            raise ValueError('No eligible article published before a scenario date; nothing written.')
        articles.extend(rows)
    return articles


def build_sql(password_hash, articles, dates=None):
    if not re.fullmatch(r'\$2[aby]\$10\$[./A-Za-z0-9]{53}', password_hash):
        raise ValueError('A generated BCrypt hash is required.')
    if len(articles) != 3 or len({int(a['id']) for a in articles}) != 3 or any(not a['term'] for a in articles):
        raise ValueError('Three distinct existing articles and dictionary terms are required.')
    dates = dates or scenario_dates()
    if len(dates) != 3:
        raise ValueError('Three scenario dates are required.')
    for article, date in zip(articles, dates):
        published = datetime.fromisoformat(article['publishedAt'].replace('Z', '+00:00'))
        if published.tzinfo is None or date.tzinfo is None or published >= date:
            raise ValueError('Every judgement must be strictly after article publication.')
    answers = [dict(questionId='experience', question='지금 투자 여정을 어디쯤 걷고 있나요?', answer='조금씩 알아가고 있어요'),
               dict(questionId='interest', question='투자할 때 가장 알고 싶은 것은 무엇인가요?', answer='내 투자 기록을 돌아보고 싶어요'),
               dict(questionId='pace', question='나에게 맞는 투자 공부 방식은 어떤 모습인가요?', answer='짧게, 매일 조금씩'),
               dict(questionId='goal', question='오늘 핀사이트에서 어떤 습관을 시작해볼까요?', answer='기사 메모 남기고 복습하기')]
    statements = ['BEGIN;', "SET LOCAL TIME ZONE 'Asia/Seoul';"]
    statements.append(f"INSERT INTO app_user(email,nickname,password_hash,provider,created_at,onboarding_answers_json,learning_level,learning_pace,learning_focus,daily_goal,onboarding_completed_at) VALUES ({quote(EMAIL)},{quote(NICKNAME)},{quote(password_hash)},'LOCAL',now(),{quote(json.dumps(answers, ensure_ascii=False))},'normal','short','routine','기사 메모 남기고 복습하기',now());")
    for article, created, (_, choice, actual, change, correct) in zip(articles, dates, SCENARIOS):
        date = quote(created.isoformat())
        news_id = int(article['id'])
        select = f"FROM app_user u WHERE {account_predicate()}"
        statements.append(f"INSERT INTO judgement(user_id,news_id,choice,reason_text,created_at,actual_direction,actual_change_percent,feedback_text,feedback_generated_at) SELECT u.id,{news_id},{quote(choice)},'[시연용 가상 결과] 실제 시세가 아닌 판단 비교 연습입니다.',{date},{quote(actual)},{change},'[시연용 가상 결과] 실제 투자 성과가 아닙니다. 판단 근거와 가정을 다시 비교해보세요.',{date} {select};")
        statements.append(f"INSERT INTO article_note(user_id,news_id,content,created_at,updated_at) SELECT u.id,{news_id},'[시연] 기사에서 사실과 가정을 구분하는 연습 메모입니다.',{date},{date} {select};")
        statements.append(f"INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) SELECT u.id,{news_id},{quote(article['term'])},'normal',{str(correct).lower()},{date} {select};")
    return '\n'.join(statements + ['COMMIT;'])

def cleanup_sql(user_id):
    user_id = int(user_id)
    # Every DELETE is scoped by the marked demo account, including records added after seeding.
    scoped = f'user_id IN (SELECT id FROM app_user WHERE id={user_id} AND {account_predicate()})'
    return '\n'.join(['BEGIN;'] + [f'DELETE FROM {table} WHERE {scoped};' for table in ('learning_progress','article_note','daily_note','judgement')]
                     + [f'DELETE FROM app_user WHERE id={user_id} AND {account_predicate()};', 'COMMIT;'])

def self_test():
    validate('localhost', 'finsight')
    for host, database in [('db.example.com','finsight'), ('127.0.0.1','production'), ('127.0.0.1','finsight_demo')]:
        try: validate(host, database)
        except ValueError: pass
        else: raise AssertionError('Unsafe destination accepted')
    password_hash = bcrypt('self-test-only-not-an-account-secret')
    reference = datetime(2026, 10, 6, 12, tzinfo=KST)
    dates = scenario_dates(reference)
    articles = [{'id': i, 'term': '금리', 'publishedAt': (date - timedelta(hours=2)).isoformat()}
                for i, date in zip((1,2,3), dates)]
    sql = build_sql(password_hash, articles, dates)
    assert [date.date().isoformat() for date in dates] == ['2026-10-05', '2026-10-03', '2026-09-22']
    assert all(not ('2026-09-23' <= date.date().isoformat() < '2026-09-30') for date in dates)
    for publication in (dates[0], dates[0] + timedelta(seconds=1)):
        invalid = [{**articles[0], 'publishedAt': publication.isoformat()}, *articles[1:]]
        try: build_sql(password_hash, invalid, dates)
        except ValueError: pass
        else: raise AssertionError('Judgement before/equal publication was accepted')
    assert sql.startswith('BEGIN;') and sql.endswith('COMMIT;')
    assert sql.count('INSERT INTO app_user') == 1
    for table in ('judgement','article_note','learning_progress'):
        assert sql.count('INSERT INTO ' + table) == 3
    assert 'INSERT INTO news' not in sql and 'UPDATE ' not in sql and 'DELETE ' not in sql
    for date in dates:
        assert date.isoformat() in sql
    cleanup = cleanup_sql(42)
    assert cleanup.count('DELETE FROM') == 5 and 'DELETE FROM news' not in cleanup
    assert all(account_predicate() in line and 'id=42' in line for line in cleanup.splitlines() if line.startswith('DELETE'))
    assert quote("a'b") == "'a''b'"
    assert '.env.*' in (ROOT / '.gitignore').read_text().splitlines()
    from unittest.mock import patch
    with patch(__name__ + '.query', side_effect=[json.dumps([article]) for article in articles]) as db:
        assert select_articles(None, dates) == articles
        assert db.call_count == 3
        for call, date in zip(db.call_args_list, dates):
            assert 'n.published_at < ' + quote(date.isoformat()) in call.args[1]
            assert 'LIMIT 1' in call.args[1]
    with patch('sys.argv', ['seed_demo.py', '--confirm-local-demo']), \
         patch(__name__ + '.query', side_effect=['finsight|127.0.0.1', '42|true']) as db, \
         patch(__name__ + '.credential_password') as credentials, patch('builtins.print'):
        main()
        assert db.call_count == 2  # Only identity/account SELECTs on a repeat run.
        assert 'host(inet_server_addr())' in db.call_args_list[0].args[1]
        credentials.assert_not_called()
    with patch('sys.argv', ['seed_demo.py', '--cleanup', '--confirm-local-demo']), \
         patch(__name__ + '.query', side_effect=['finsight|127.0.0.1', '42|true', '']) as db, \
         patch(__name__ + '.credential_password') as credentials, patch('builtins.print'):
        main()
        assert db.call_args.args[1] == cleanup_sql(42)
        credentials.assert_not_called()
    print('PASS: loopback/DB guards, BCrypt, seed counts, 1/3/14-day scenarios, strict publication ordering, empty-week gap, idempotent no-op, account-scoped cleanup. No DB access or credential file writes.')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--database', default='finsight')
    parser.add_argument('--user', default=os.environ.get('PGUSER', 'finsight'))
    parser.add_argument('--confirm-local-demo', action='store_true')
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    validate(args.host, args.database)
    if not args.confirm_local_demo:
        parser.error('Separate user approval and --confirm-local-demo are required for seed or cleanup.')
    identity = query(args, "SELECT current_database() || '|' || host(inet_server_addr());")
    if identity not in ('finsight|127.0.0.1', 'finsight|::1'):
        raise ValueError('Server must identify itself as loopback finsight.')
    existing = query(args, f"SELECT id || '|' || ({account_predicate()})::text FROM app_user WHERE email={quote(EMAIL)};")
    if existing:
        user_id, marked = existing.split('|')
        if marked != 'true':
            raise ValueError('Email belongs to an unmarked account; nothing changed.')
        if args.cleanup:
            query(args, cleanup_sql(user_id))
            print('Removed only the marked demo account and its records. Articles and credential file preserved.')
        else:
            print('Demo account already exists; no changes made, including credential file.')
        return
    if args.cleanup:
        print('No demo account exists; nothing changed.')
        return
    dates = scenario_dates()
    articles = select_articles(args, dates)
    password_hash = bcrypt(credential_password())
    query(args, build_sql(password_hash, articles, dates))
    print('Created one demo account and 3 judgements, 3 notes, 3 quiz records linked to existing news. Credentials withheld.')

if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, json.JSONDecodeError) as error:
        raise SystemExit(str(error))
