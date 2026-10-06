#!/usr/bin/env python3
"""Read-only preview by default. --apply requires explicit user approval."""
import argparse
import json
import os
from pathlib import Path
from types import SimpleNamespace
from seed_demo import query, validate

JUDGEMENTS = (3,4,5,6,7,8,9,10,12,16,17)
USERS = (9,19,20,25,26,29)
TABLES = ('learning_progress','article_note','daily_note','judgement')

def reset_sql():
    return 'BEGIN; UPDATE judgement SET actual_direction=NULL, actual_change_percent=NULL, feedback_text=NULL, feedback_generated_at=NULL, comparison_date=NULL, judgement_close=NULL, comparison_close=NULL WHERE id IN (' + ','.join(map(str,JUDGEMENTS)) + '); COMMIT;'

def cleanup_sql():
    ids=','.join(map(str,USERS))
    return '\n'.join(['BEGIN;']+[f'DELETE FROM {table} WHERE user_id IN ({ids});' for table in TABLES]+[
        "DO $$ BEGIN IF EXISTS (SELECT 1 FROM judgement WHERE news_id=231) OR EXISTS (SELECT 1 FROM article_note WHERE news_id=231) OR EXISTS (SELECT 1 FROM learning_progress WHERE news_id=231) THEN RAISE EXCEPTION 'Article 231 still has linked records; rollback'; END IF; END $$;",
        "DELETE FROM news WHERE id=231 AND source='SYNTHETIC_QA';",f'DELETE FROM app_user WHERE id IN ({ids});','COMMIT;'])

def self_test():
    reset=reset_sql(); cleanup=cleanup_sql()
    assert all(f'{column}=NULL' in reset for column in ('actual_direction','actual_change_percent','feedback_text','feedback_generated_at','comparison_date','judgement_close','comparison_close'))
    assert 'WHERE id IN (3,4,5,6,7,8,9,10,12,16,17)' in reset
    assert cleanup.count('DELETE FROM')==6
    assert all('WHERE' in line for line in cleanup.splitlines() if line.startswith('DELETE'))
    assert set(USERS).isdisjoint({8,27,31})
    assert 'INSERT' not in reset+cleanup
    print('PASS: exact reset IDs, null fields, account-scoped deletes, protected users, linked-news rollback. No DB changes.')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mode',choices=('reset','cleanup'),default='reset')
    p.add_argument('--apply',action='store_true');p.add_argument('--self-test',action='store_true')
    opts=p.parse_args()
    if opts.self_test: self_test();return
    cfg={}
    for line in (Path(__file__).resolve().parents[1]/'.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            k,v=line.removeprefix('export ').split('=',1);cfg[k.strip()]=v.strip().strip('\"\'')
    import re
    url=cfg.get('DB_URL',cfg.get('SPRING_DATASOURCE_URL','jdbc:postgresql://localhost:5432/finsight'))
    m=re.fullmatch(r'(?:jdbc:)?postgresql://([^/:]+)(?::5432)?/([^?]+)',url)
    if not m: raise ValueError('Unrecognized local DB destination')
    validate(m[1],m[2]);args=SimpleNamespace(host=m[1],database=m[2],user=cfg.get('DB_USERNAME','finsight'))
    os.environ['PGPASSWORD']=cfg.get('DB_PASSWORD','')
    if query(args,"SELECT current_database() || '|' || host(inet_server_addr());") not in ('finsight|127.0.0.1','finsight|::1'): raise ValueError('Not loopback development DB')
    if opts.mode=='reset':
        preview=query(args,'SELECT coalesce(json_agg(x),\'[]\')::text FROM (SELECT id,user_id,news_id,created_at,actual_direction,actual_change_percent,feedback_generated_at,comparison_date,judgement_close,comparison_close FROM judgement WHERE id IN ('+','.join(map(str,JUDGEMENTS))+') ORDER BY id) x;')
    else:
        preview=query(args,'SELECT coalesce(json_agg(x),\'[]\')::text FROM (SELECT id,nickname,(SELECT count(*) FROM judgement j WHERE j.user_id=u.id) AS judgements,(SELECT count(*) FROM article_note n WHERE n.user_id=u.id) AS notes,(SELECT count(*) FROM learning_progress l WHERE l.user_id=u.id) AS quizzes FROM app_user u WHERE id IN ('+','.join(map(str,USERS))+') ORDER BY id) x;')
    if not opts.apply: print(preview);return
    # Refuse changes when new tables reference the cleanup targets; preserve their data.
    if opts.mode=='cleanup':
        refs=query(args,"SELECT DISTINCT tc.table_name FROM information_schema.table_constraints tc JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name=ccu.constraint_name WHERE tc.constraint_type='FOREIGN KEY' AND ccu.table_name IN ('app_user','news');")
        if not set(refs.splitlines()).issubset(set(TABLES)):raise ValueError('Unexpected dependent table; review cleanup first')
    query(args,reset_sql() if opts.mode=='reset' else cleanup_sql())
    print('Completed approved local '+opts.mode+'. No external API calls. Recalculation must be requested separately.')

if __name__=='__main__': main()
