"""Apply a reviewed classifier output plan. Read-only by default; no content generation."""
import argparse
import json
from datetime import datetime,timedelta,timezone
from backfill_news_images import connection
from seed_demo_personas.seed import rows,query,quote

CATEGORIES={'IPO·공시','금리·채권','시장','국내증시','해외증시','기업·종목','산업·기술','금리·통화정책','환율·원자재','정책·경제','기타'}
def run(plan,apply=False,limit=None):
    if not isinstance(plan,list) or len(plan)>1000 or (apply and (limit is None or not 1<=limit<=1000)):
        raise ValueError('Reviewed plan and explicit limit required')
    ids=set()
    for row in plan:
        if not isinstance(row,dict) or type(row.get('id')) is not int or row['id']<=0 or row['id'] in ids or row.get('category') not in CATEGORIES:
            raise ValueError('Unique news ID and classifier category required')
        if not isinstance(row.get('url'),str) or datetime.fromisoformat(row['publishedAt']).tzinfo is None:
            raise ValueError('Source URL and publication time required')
        ids.add(row['id'])
    selected=plan[:limit] if limit is not None else plan
    result=dict(selected=len(selected),wouldUpdate=0,unchanged=0,outsideOrChanged=0,concurrentChange=0,updated=0,apply=apply)
    env=connection();since=(datetime.now(timezone.utc)-timedelta(days=7)).isoformat()
    for row in selected:
        guard=(f"id={row['id']} AND url="+quote(row['url'])+" AND published_at IS NOT DISTINCT FROM "+quote(row['publishedAt'])
               +" AND published_at>="+quote(since)+" AND published_at<=now() AND rewrite_mode='EXCERPT'"
               +" AND coalesce(source,'') NOT LIKE 'SYNTHETIC%' AND url NOT LIKE '%example.invalid%'")
        current=rows(env,'SELECT category FROM news WHERE '+guard)
        if not current:result['outsideOrChanged']+=1;continue
        if current[0]['category']==row['category']:result['unchanged']+=1;continue
        if current[0]['category']!=row.get('beforeCategory'):result['outsideOrChanged']+=1;continue
        result['wouldUpdate']+=1
        if apply:
            changed=query(env,'WITH changed AS (UPDATE news SET category='+quote(row['category'])+' WHERE '+guard
                          +' AND category IS NOT DISTINCT FROM '+quote(row.get('beforeCategory'))+' RETURNING id) SELECT count(*) FROM changed;',write=True)
            result['updated' if changed=='1' else 'concurrentChange']+=1
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--plan',required=True);parser.add_argument('--apply',action='store_true');parser.add_argument('--limit',type=int);args=parser.parse_args()
    if args.limit is not None and not 1<=args.limit<=1000:parser.error('--limit must be 1..1000')
    with open(args.plan,encoding='utf8') as source:print(json.dumps(run(json.load(source),args.apply,args.limit)))
if __name__=='__main__':
    try:main()
    except (OSError,ValueError,KeyError,RuntimeError):raise SystemExit('Category backfill stopped; check reviewed plan and connection. Credentials are not logged.') from None
