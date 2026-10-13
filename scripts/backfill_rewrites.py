#!/usr/bin/env python3
"""Local rewrite backfill: dry-run by default, successful results only, no regeneration."""
import argparse
import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler
from seed_demo_personas.seed import connection, query, quote, rows

ELIGIBLE = """(rewrite_mode IS NULL OR rewrite_mode='EXCERPT')
 AND char_length(btrim(raw_content))>=200
 AND (rewrite_mode='EXCERPT' OR
      (coalesce(btrim(rewritten_beginner),'')='' AND coalesce(btrim(rewritten_normal),'')=''
       AND coalesce(btrim(rewritten_analyst),'')=''))"""
AI_URL = 'http://127.0.0.1:8001'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(path, body=None):
    req = Request(AI_URL + path, data=json.dumps(body).encode() if body is not None else None,
                  headers={'Content-Type': 'application/json'})
    try:
        with build_opener(NoRedirect()).open(req, timeout=60) as response:
            return json.load(response)
    except (HTTPError, URLError, TimeoutError, ValueError):
        return None


def save(env, article, result):
    if result.get('mode') != 'REWRITTEN' or any(not isinstance(result.get(k), str) or not result[k].strip()
                                             for k in ('beginner', 'normal', 'analyst')):
        return False
    assignments = ','.join('rewritten_' + level + '=' + quote(result[level])
                           for level in ('beginner', 'normal', 'analyst'))
    unchanged = ' AND '.join(key + ' IS NOT DISTINCT FROM ' + quote(article[key])
                             for key in ('title', 'url', 'raw_content', 'rewrite_mode',
                                         'rewritten_beginner', 'rewritten_normal', 'rewritten_analyst'))
    sql = ("WITH changed AS (UPDATE news SET " + assignments
           + ",rewrite_mode='REWRITTEN',rewrite_retry_pending=false WHERE id=" + str(int(article['id']))
           + ' AND ' + ELIGIBLE + ' AND ' + unchanged + ' RETURNING id) SELECT count(*) FROM changed;')
    return query(env, sql, write=True) == '1'


def run(limit=None, apply=False):
    if apply and (limit is None or limit <= 0):
        raise ValueError('--apply에는 양수 --limit N이 필요합니다.')
    env = connection()
    count = int(query(env, 'SELECT count(*) FROM news WHERE ' + ELIGIBLE + ';'))
    selected = min(count, limit) if limit is not None else count
    summary = {'targetCount': count, 'selectedCount': selected, 'processed': 0,
               'success': 0, 'excerpt': 0, 'quota429': 0, 'skippedConcurrentChange': 0,
               'stopReason': None, 'successfulNewsIds': []}
    if not apply:
        # Generation latency is not included in this lower bound.
        print(json.dumps({'dryRun': True, 'targetCount': count, 'selectedCount': selected,
                          'minimumSeconds': max(0, selected - 1) * 6,
                          'estimate': '호출 시작 6초 간격의 최소 시간이며 생성·DB 시간은 추가됩니다.'}, ensure_ascii=False))
        return summary
    articles = rows(env, 'SELECT id,title,url,raw_content,rewrite_mode,rewritten_beginner,rewritten_normal,'
                    'rewritten_analyst FROM news WHERE ' + ELIGIBLE
                    + ' ORDER BY published_at DESC NULLS LAST,id DESC LIMIT ' + str(limit))
    health = request('/health')
    if not health or not health.get('rewriteEnabled') or health.get('provider') != 'gemini' or health.get('useRealLlm') is not False:
        raise ValueError('Gemini 수집 전용 규칙 모드를 확인하지 못했습니다. DB 변경 없음.')
    initial_quota = health['rewrite429Count']
    for article in articles:
        if health.get('rewriteStoppedToday'):
            summary['stopReason'] = '429 하루 중단'; break
        if health['rewriteUsedCalls'] >= health['rewriteDailyLimit']:
            summary['stopReason'] = '하루 상한'; break
        result = request('/ai/news/rewrite', {'title': article['title'], 'body': article['raw_content'][:20000],
                                            # A stable one-time manual attempt, separate from a collected excerpt.
                                            'article_key': 'backfill-rewrites:' + str(article['id']), 'allow_llm': True}) or {}
        summary['processed'] += 1
        if result.get('mode') == 'REWRITTEN':
            if save(env, article, result):
                summary['success'] += 1; summary['successfulNewsIds'].append(article['id'])
            else:
                summary['skippedConcurrentChange'] += 1
        else:
            summary['excerpt'] += 1
        health = request('/health')
        if health:
            summary['quota429'] = max(0, health['rewrite429Count'] - initial_quota)
        print('처리 ' + str(summary['processed']) + '/' + str(selected) + ' · 성공 ' + str(summary['success'])
              + ' · 발췌 유지 ' + str(summary['excerpt']) + ' · 429 ' + str(summary['quota429']), flush=True)
        if not health:
            summary['stopReason'] = 'AI 상태 확인 실패'; break
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--limit', type=int)
    args = parser.parse_args()
    if args.limit is not None and args.limit <= 0:
        parser.error('--limit은 양수여야 합니다.')
    if args.apply and args.limit is None:
        parser.error('--apply --limit N을 함께 지정하세요.')
    run(args.limit, args.apply)


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError):
        raise SystemExit('백필 중단: 로컬 DB·AI 상태를 확인하세요. 비밀 값과 SQL은 출력하지 않습니다.')
