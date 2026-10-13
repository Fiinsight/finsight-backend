#!/usr/bin/env python3
"""Three-persona local SQL seed. Dry-run by default; no credential-file writes."""
import argparse
from datetime import datetime, timedelta
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlparse
from zoneinfo import ZoneInfo
import sys

ROOT = Path(__file__).resolve().parents[2]
KST = ZoneInfo('Asia/Seoul')
PERSONAS = {
    'demo-beginner': ('민지', ['이제 막 시작했어요', '뉴스 내용을 쉽게 이해하고 싶어요', '짧게, 매일 조금씩', '용어가 어려워요', '뉴스 하나 읽기'], 'beginner', 'short', 'news', 'terms', 'read1'),
    'demo-investor': ('준호', ['직접 투자하고 있어요', '투자 판단 기준을 만들고 싶어요', '한 번에 깊이 있게', '판단할 근거가 부족해요', '투자 판단 돌아보기'], 'analyst', 'deep', 'judgement', 'evidence', 'reviewJudgement'),
    'demo-new': ('새 사용자', [], None, None, None, None, None),
}
TABLES = ('learning_progress', 'term_mastery', 'article_note', 'daily_note', 'judgement')
QUESTIONS = dict(zip(('experience','interest','pace','difficulty','goal'), (
    '지금 투자 여정을 어디쯤 걷고 있나요?', '투자할 때 가장 알고 싶은 것은 무엇인가요?',
    '나에게 맞는 투자 공부 방식은 어떤 모습인가요?', '투자 소식을 접할 때 가장 어려운 점은 무엇인가요?',
    '오늘 핀사이트에서 어떤 습관을 시작해볼까요?')))


def env_values(path):
    if path.is_symlink():
        raise ValueError('환경 파일 심볼릭 링크는 허용하지 않습니다.')
    return {k.strip(): v.strip().strip('\"\'') for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#') and '=' in line
            for k, v in [line.split('=', 1)]}


def password():
    value = env_values(ROOT.parent / '.env.demo.local').get('DEMO_PASSWORD', '')
    if not 8 <= len(value.encode()) <= 72:
        raise ValueError('.env.demo.local의 DEMO_PASSWORD(8~72바이트)가 필요합니다. 파일은 변경하지 않습니다.')
    return value


def connection():
    values = env_values(ROOT / '.env')
    uri = urlparse(values.get('DB_URL', '').removeprefix('jdbc:'))
    if uri.scheme != 'postgresql' or uri.hostname not in ('localhost', '127.0.0.1', '::1') or uri.path not in ('/finsight', '/finsight_demo'):
        raise ValueError('loopback finsight/finsight_demo DB만 허용합니다. 운영 DB는 실행하지 않습니다.')
    env = os.environ.copy()
    for key in ('PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS', 'PGHOSTADDR'):
        env.pop(key, None)
    env.update(PGHOST=uri.hostname, PGPORT=str(uri.port or 5432), PGDATABASE=uri.path[1:],
               PGUSER=values.get('DB_USERNAME', ''), PGPASSWORD=values.get('DB_PASSWORD', ''), PGCONNECT_TIMEOUT='3')
    return env


def query(env, sql, write=False):
    safe = dict(env, PGOPTIONS='-c statement_timeout=30000 -c lock_timeout=5000' + ('' if write else ' -c default_transaction_read_only=on'))
    result = subprocess.run(['psql', '-X', '-q', '-t', '-A', '--no-password', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, env=safe, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('SQL 실행 실패: 트랜잭션 롤백. DB/비밀번호/SQL 출력은 숨깁니다.')
    return result.stdout.strip()


def quote(value):
    return 'NULL' if value is None else "'" + str(value).replace("'", "''") + "'"


def owner(name):
    if name not in PERSONAS:
        raise ValueError('시연 계정 외의 소유자는 허용하지 않습니다.')
    return f"email={quote(name + '@finsight.test')} AND provider='LOCAL'"


def rows(env, sql):
    return json.loads(query(env, 'SELECT coalesce(json_agg(x),\'[]\'::json)::text FROM (' + sql + ') x;'))


def catalog(payload):
    # No Spring context: collection, TermSeeder, and FeedbackScheduler cannot execute.
    runtime = os.environ.copy()
    cached = sorted((Path.home()/'.gradle/jdks').glob('*21*/**/Contents/Home/bin/java'))
    if cached:
        runtime['JAVA_HOME'] = str(cached[0].parents[1])
    result = subprocess.run([str(ROOT / 'gradlew'), '-q', 'demoQuizCatalog', '--console=plain'], cwd=ROOT,
                            input=json.dumps(payload), text=True, capture_output=True, env=runtime)
    marker = next((line[10:] for line in result.stdout.splitlines() if line.startswith('DEMO_JSON:')), None)
    if result.returncode or marker is None:
        raise RuntimeError('실제 퀴즈/BKT 카탈로그 생성 실패. 빌드 상세 출력은 숨깁니다.')
    return json.loads(marker)


def snapshot(env, now):
    news = rows(env, "SELECT id,title,url,source,published_at,raw_content,related_symbol FROM news WHERE url ~ '^https?://' AND coalesce(source,'') NOT LIKE 'SYNTHETIC%' AND url NOT LIKE '%example.invalid%' AND length(coalesce(raw_content,''))>=120 ORDER BY id")
    terms = rows(env, "SELECT term,short_definition FROM term WHERE coalesce(short_definition,'')<>'' ORDER BY term")
    prices = rows(env, "SELECT DISTINCT j.news_id,(j.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,j.comparison_date,j.judgement_close,j.comparison_close FROM judgement j JOIN news n ON n.id=j.news_id WHERE j.comparison_date IS NOT NULL AND j.judgement_close>0 AND j.comparison_close>0 AND coalesce(n.source,'') NOT LIKE 'SYNTHETIC%' AND n.url NOT LIKE '%example.invalid%' AND j.feedback_text NOT LIKE '%가상%' AND j.created_at>=" + quote((now-timedelta(days=13)).replace(hour=0,minute=0,second=0,microsecond=0).isoformat()) + " AND j.feedback_generated_at <= " + quote(now.isoformat()))
    # Price snapshots remain evidence, even when the source happens to be a demo record reset below.
    return {'news': news, 'terms': terms, 'prices': prices}


def plan(data, generated, now):
    articles = {a['id']: a for a in data['news']}
    metadata = {a['id']: a for a in generated['articles']}
    questions = generated['questions']
    output = {name: {'quizzes': [], 'notes': [], 'judgements': []} for name in PERSONAS}
    missing = []
    used = set()
    # Each source article must already exist at the simulated time. No fake news IDs.
    def add(name, term, count, correct, requested):
        candidates = [q for q in questions if q['term']==term and q['requestedLevel']==requested and (name,q['newsId'],term) not in used]
        if name=='demo-investor':
            candidates = [q for q in candidates if q['level']==requested]
        candidates.sort(key=lambda q:(q['level']!=requested,q['newsId']))
        added = 0
        for q in candidates:
            index = len(output[name]['quizzes'])
            span = 5 if name=='demo-beginner' else 10
            older = list(range(span+1,14))
            offset = index if index < span else older[(index-span) % len(older)]
            at = now.replace(hour=min(now.hour,12),minute=0,second=0)-timedelta(days=offset)
            published = datetime.fromisoformat(articles[q['newsId']]['published_at'].replace('Z','+00:00'))
            if published >= at:
                continue
            used.add((name,q['newsId'],term))
            output[name]['quizzes'].append({**q,'owner':name,'correct':correct,'at':at.isoformat()})
            added += 1
            if added==count:
                break
        if added<count:
            if name=='demo-investor' and requested=='normal':
                add(name,term,count-added,correct,'analyst')
            else:
                missing.append(f'{name} {term}/{requested}: 문항 {count-added}개 부족')
    # Five promotable concepts: three mastered, two with exactly two correct answers.
    # Each of the latter reaches >=.85 with one further correct beginner answer.
    for term in ('금리','환율','기준금리'):
        add('demo-beginner',term,3,False,'beginner')
    for term in ('코스피','매출','영업이익'):
        add('demo-beginner',term,4,True,'beginner')
    for term in ('AI','반도체'):
        add('demo-beginner',term,2,True,'beginner')
    for term in ('밸류에이션','수급'):
        add('demo-investor',term,3,False,'analyst')
    for term in ('매출','금리','코스피','영업이익','환율','반도체'):
        add('demo-investor',term,2,True,'normal')
        add('demo-investor',term,2,True,'analyst')
    for name in ('demo-beginner','demo-investor'):
        entries = output[name]['quizzes']
        entries.sort(key=lambda q:q['at'])
        # Guarantee the requested uninterrupted span using real valid quiz records, without adding phantom activities.
        days = 5 if name=='demo-beginner' else 10
        for offset in range(days):
            day = (now-timedelta(days=offset)).date()
            if not any(datetime.fromisoformat(q['at']).date()==day for q in entries):
                missing.append(f'{name}: 최근 {days}일 연속 활동에 {day} 기록 부족')
        note_count = 1 if name=='demo-beginner' else 5
        seen = set()
        for q in reversed(entries):
            if q['newsId'] in seen:
                continue
            seen.add(q['newsId'])
            output[name]['notes'].append({'newsId':q['newsId'],'at':q['at'],'content':f"[시연 메모] {q['term']}을 설명하는 기사 문장을 다시 확인하고 판단 근거와 비교해요."})
            if len(seen)==note_count:
                break
        desired = [('금리·채권',False,6),('기업·종목',True,6)] if name=='demo-investor' else [(None,True,2)]
        seen_prices=set()
        for category, aligned, count in desired:
            selected=0
            for p in generated['prices']:
                article=articles.get(p['news_id'])
                if not article or p['news_id'] in seen_prices or not metadata[p['news_id']]['validSymbol']:
                    continue
                if category and metadata[p['news_id']]['category']!=category:
                    continue
                at=datetime.fromisoformat(p['day']+'T14:00:00+09:00')
                if datetime.fromisoformat(article['published_at'].replace('Z','+00:00')) >= at:
                    continue
                before,after=Decimal(str(p['judgement_close'])),Decimal(str(p['comparison_close']))
                change=float((after-before)/before*100)
                direction='UP' if change>.5 else 'DOWN' if change<-.5 else 'NEUTRAL'
                choice=direction if aligned else ('DOWN' if direction=='UP' else 'UP')
                output[name]['judgements'].append({**p,'at':at.isoformat(),'change':change,'direction':direction,'choice':choice,'symbol':article['related_symbol'],'title':article['title']})
                seen_prices.add(p['news_id']); selected+=1
                if selected==count: break
            if selected<count:
                missing.append(f'{name} {category or "판단"}: 저장된 정상 다음 거래일 종가/기사 {count-selected}건 부족')
    return output, missing


def sql_for(plan, mastery, password_hash, now, tables):
    if set(plan)!=set(PERSONAS):
        raise ValueError('정확히 세 시연 계정만 허용합니다.')
    statements=['BEGIN;', "SET LOCAL TIME ZONE 'Asia/Seoul';", "SELECT pg_advisory_xact_lock(10720261013);"]
    for name,(nickname,answers,level,pace,focus,pain,goal) in PERSONAS.items():
        scope=owner(name)
        created = now if name=='demo-new' else (now-timedelta(days=13)).replace(hour=0,minute=0,second=0)
        # A different provider must never be taken over, including a race between dry-run and apply.
        statements.append(f"DO $$ BEGIN IF EXISTS(SELECT 1 FROM app_user WHERE email={quote(name+'@finsight.test')} AND provider<>'LOCAL') THEN RAISE EXCEPTION 'demo email ownership conflict'; END IF; END $$;")
        statements.append(f"INSERT INTO app_user(email,nickname,password_hash,provider,created_at) VALUES({quote(name+'@finsight.test')},{quote(nickname)},{quote(password_hash)},'LOCAL',{quote(created.isoformat())}) ON CONFLICT(email) DO NOTHING;")
        statements.append(f'SELECT id FROM app_user WHERE email={quote(name+"@finsight.test")} FOR UPDATE;')
        statements.append(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM app_user WHERE {scope}) THEN RAISE EXCEPTION 'demo email ownership conflict'; END IF; END $$;")
        for table in TABLES:
            if table in tables:
                statements.append(f'DELETE FROM {table} WHERE user_id IN(SELECT id FROM app_user WHERE {scope});')
        answer_json=json.dumps([{'questionId':key,'question':QUESTIONS[key],'answer':value} for key,value in zip(QUESTIONS,answers)],ensure_ascii=False) if answers else None
        values={'nickname':nickname,'password_hash':password_hash,'created_at':created.isoformat(),'onboarding_answers_json':answer_json,'learning_level':level,'learning_pace':pace,'learning_focus':focus,'learning_pain_point':pain,'goal_type':goal,'daily_goal':answers[-1] if answers else None,'onboarding_completed_at':created.isoformat() if answers else None}
        statements.append('UPDATE app_user SET '+','.join(k+'='+quote(v) for k,v in values.items())+f' WHERE {scope};')
        select=f'FROM app_user WHERE {scope}'
        for q in plan[name]['quizzes']:
            statements.append(f"INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) SELECT id,{int(q['newsId'])},{quote(q['term'])},{quote(q['level'])},{str(q['correct']).lower()},{quote(q['at'])} {select};")
        if 'term_mastery' in tables:
            for term in sorted({q['term'] for q in plan[name]['quizzes']}):
                key=name+':'+term
                last=max(q['at'] for q in plan[name]['quizzes'] if q['term']==term)
                statements.append(f"INSERT INTO term_mastery(user_id,term,p_known,attempts,last_answered_at) SELECT id,{quote(term)},{mastery['probabilities'][key]},{mastery['attempts'][key]},{quote(last)} {select};")
        for note in plan[name]['notes']:
            statements.append(f"INSERT INTO article_note(user_id,news_id,content,created_at,updated_at) SELECT id,{int(note['newsId'])},{quote(note['content'])},{quote(note['at'])},{quote(note['at'])} {select};")
        for j in plan[name]['judgements']:
            verdict='CORRECT' if j['choice']==j['direction'] else 'WRONG'
            if verdict=='CORRECT' and abs(j['change'])<=1: verdict='PARTIAL'
            reason='기사의 사실과 다음 거래일 시장 움직임을 비교해 볼 수 있다고 판단했어요.'
            summary=f"{j['symbol']} {j['comparison_date']} 종가 기준 {j['change']:+.2f}%. 뉴스가 원인이라고 단정할 수 없어요."
            feedback={'verdict':verdict,'reasons':[{'title':'기사 확인','detail':j['title']},{'title':'판단 근거','detail':reason},{'title':'종가 비교','detail':summary}],'nextTip':'판단 당시 근거와 결과를 구분해서 다시 확인해보세요.'}
            headline='다르게 움직였어요' if verdict=='WRONG' else '방향은 맞았지만 변동이 작았어요' if verdict=='PARTIAL' else '정확한 판단이에요!'
            statements.append(f"INSERT INTO judgement(user_id,news_id,choice,reason_text,created_at,actual_direction,actual_change_percent,feedback_text,feedback_generated_at,comparison_date,judgement_close,comparison_close,feedback_headline,feedback_reasons) SELECT id,{int(j['news_id'])},{quote(j['choice'])},{quote(reason)},{quote(j['at'])},{quote(j['direction'])},{j['change']},{quote(summary)},{quote(j['comparison_date']+'T16:00:00+09:00')},{quote(j['comparison_date'])},{j['judgement_close']},{j['comparison_close']},{quote(headline)},{quote(json.dumps(feedback,ensure_ascii=False))}::jsonb {select};")
    statements.append('COMMIT;')
    return '\n'.join(statements)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--report',type=Path,help='비밀이 없는 JSON 결과 경로(선택)')
    args=parser.parse_args()
    now=datetime.now(KST).replace(microsecond=0)
    env=connection()
    if query(env,"SELECT current_database() IN ('finsight','finsight_demo') AND inet_server_addr() IN ('127.0.0.1'::inet,'::1'::inet);")!='t':
        raise ValueError('실제 접속 서버의 로컬 DB 확인 실패')
    existing=rows(env,"SELECT id,email,provider FROM app_user WHERE email IN ("+','.join(quote(n+'@finsight.test') for n in PERSONAS)+')')
    if any(row['provider']!='LOCAL' for row in existing):
        raise ValueError('시연 이메일 소유권 충돌. 아무것도 변경하지 않습니다.')
    tables=set(query(env,"SELECT tablename FROM pg_tables WHERE schemaname='public';").splitlines())
    data=snapshot(env,now)
    generated=catalog(data)
    planned,missing=plan(data,generated,now)
    attempts=[q for name in PERSONAS for q in planned[name]['quizzes']]
    mastery=catalog({**data,'attempts':attempts})
    from importlib.util import spec_from_file_location,module_from_spec
    spec=spec_from_file_location('old_seed',ROOT/'scripts/seed_demo.py'); old=module_from_spec(spec); spec.loader.exec_module(old)
    counts={name:{key:len(value) for key,value in planned[name].items()} for name in PERSONAS}
    for name in PERSONAS:
        counts[name]['masteryTerms']=len({q['term'] for q in planned[name]['quizzes']}) if 'term_mastery' in tables else 0
        counts[name]['accountsCreated']=int(not any(row['email']==name+'@finsight.test' for row in existing))
    deletion={name:{table:int(query(env,f'SELECT count(*) FROM {table} WHERE user_id IN(SELECT id FROM app_user WHERE {owner(name)});')) for table in TABLES if table in tables} for name in PERSONAS}
    if 'term_mastery' not in tables: missing.append('term_mastery 미구현 DB: 숙련도 저장 생략')
    promotion=[]
    for term in ('AI','반도체'):
        candidates=[s for s in mastery.get('selections',[]) if s['owner']=='demo-beginner' and s['term']==term and s['pKnown']<.85 and s['afterCorrect']>=.85 and datetime.fromisoformat(next(n['published_at'] for n in data['news'] if n['id']==s['newsId']).replace('Z','+00:00'))<=now]
        if candidates:
            promotion.append(candidates[0])
        else:
            missing.append(f'민지 {term}: 실제 자동 선택 문항으로 승급 시연 불가')
    public={'mode':'APPLIED' if args.apply else 'DRY_RUN','date':now.date().isoformat(),'counts':counts,'deletionCounts':deletion,'shortfalls':missing,'mastery':mastery['probabilities'],'newsIds':sorted({q['newsId'] for q in attempts}),'promotionQuizzes':promotion}
    if args.apply:
        query(env,sql_for(planned,mastery,old.bcrypt(password()),now,tables),write=True)
        public['userIds']={name:int(query(env,f'SELECT id FROM app_user WHERE {owner(name)};')) for name in PERSONAS}
    print('| 계정 | 퀴즈 | 판단 | 메모 | 숙련도 용어 | 계정 신규 | 삭제 예정 총수 |')
    print('|---|---:|---:|---:|---:|---:|---:|')
    for name in PERSONAS:
        c=counts[name]; print(f"| {name} | {c['quizzes']} | {c['judgements']} | {c['notes']} | {c['masteryTerms']} | {c['accountsCreated']} | {sum(deletion[name].values())} |")
    print('모드:',public['mode'])
    for item in missing: print('부족:',item)
    if args.report:
        args.report.write_text(json.dumps(public,ensure_ascii=False,indent=2))


if __name__=='__main__':
    try: main()
    except (OSError,ValueError,RuntimeError,KeyError) as error:
        # Do not emit SQL, HTTP payloads, tokens, environment values, or subprocess stderr.
        print('시드 중단:',str(error) if isinstance(error,(ValueError,RuntimeError)) else '입력/설정 확인이 필요합니다. 비밀 출력 없음.',file=sys.stderr)
        raise SystemExit(1)
