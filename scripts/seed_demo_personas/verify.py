#!/usr/bin/env python3
"""Read-only comparison via demo logins. Tokens/passwords/responses never printed."""
import argparse
import json
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.error import HTTPError, URLError
from seed import password, PERSONAS

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(base, path, token=None, body=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=Request(base+path,headers=headers,data=json.dumps(body).encode() if body is not None else None)
    try:
        with build_opener(NoRedirect()).open(req, timeout=20) as response:
            return json.load(response)
    except (HTTPError,URLError,TimeoutError):
        raise RuntimeError('로컬 API 호출 실패. 응답·토큰·비밀번호는 출력하지 않습니다.') from None


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:8080')
    parser.add_argument('--news-id',type=int,required=True)
    args=parser.parse_args()
    parsed=urlparse(args.base_url)
    if parsed.scheme not in ('http','https') or parsed.hostname not in ('localhost','127.0.0.1','::1') or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/') or args.news_id<=0:
        raise ValueError('loopback API와 실제 기사 ID만 허용합니다.')
    base=args.base_url.rstrip('/')
    output=[]
    for name in ('demo-beginner','demo-investor'):
        auth=request(base,'/api/auth/login',body={'email':name+'@finsight.test','password':password()})
        token=auth['accessToken']
        profile=request(base,'/api/profile/onboarding',token)
        progress=request(base,'/api/learning/progress',token)
        reviews=request(base,'/api/learning/reviews',token)
        # Omit level: default must come from the account's own onboarding.
        lesson=request(base,f'/api/learning/news/{args.news_id}',token)
        output.append({'account':name,'level':profile.get('learningLevel'),'pain':profile.get('learningPainPoint'),
            'reviews':', '.join(r['term'] for r in reviews[:3]),'weak':', '.join(a['category'] for a in progress.get('weakAreas',[])) or '없음',
            'suggestion':progress.get('levelSuggestion') or '아직 없음','quiz':(lesson.get('question') or {}).get('prompt','문항 없음').split('\n')[0],
            'lessonLevel':lesson.get('level'),'streak':progress.get('activityStreakDays'),
            'concepts':len(progress.get('mastery',{}).get('strongest',[]))})
    print('| 계정 | 기본 수준 | 복습 상위 3 | 약한 분야 | 수준 제안 | 퀴즈 | 연속 활동 |')
    print('|---|---|---|---|---|---|---:|')
    for row in output:
        print('| '+' | '.join(str(row[k]).replace('|','/') for k in ('account','level','reviews','weak','suggestion','quiz','streak'))+' |')
    failures=[]
    if [r['level'] for r in output]!=['beginner','analyst']: failures.append('기본 수준')
    if [r['pain'] for r in output]!=['terms','evidence']: failures.append('어려운 점')
    if any(r['lessonLevel']!=r['level'] for r in output): failures.append('같은 기사 기본 수준')
    if output[0]['reviews']==output[1]['reviews']: failures.append('복습 순서 차이')
    if output[0]['quiz']==output[1]['quiz']: failures.append('퀴즈 유형 차이(기사 fallback 가능)')
    if output[1]['weak']=='없음': failures.append('준호 약한 분야(저장 종가 부족 시 예상되는 제한)')
    if output[0]['suggestion']!='아직 없음': failures.append('민지 승급 제안은 시연 답변 전까지 없어야 함')
    print('차이 검증:', 'PASS' if not failures else '제한: '+', '.join(failures))
    return 2 if failures else 0

if __name__=='__main__':
    try: raise SystemExit(main())
    except (OSError,RuntimeError,ValueError,KeyError):
        raise SystemExit('검증 중단: 로컬 서버·기사·계정 설정을 확인하세요. 비밀 출력 없음.')
