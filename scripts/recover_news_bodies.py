#!/usr/bin/env python3
"""One approved sequential recovery of seven empty local articles; never retry."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import urllib.error
from remap_news_symbols import ROOT, db
from seed_demo import quote

IDS = (96,97,99,106,107,108,226)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args): return None

def request(url, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={'User-Agent':'Mozilla/5.0 (compatible; FinsightBot/1.0)', 'Content-Type':'application/json'})
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=10) as response:
        return response.read(5_000_000).decode('utf-8', errors='replace')

def main():
    if Path('/tmp/finsight-recovery-results.json').exists():
        raise RuntimeError('Recovery already attempted; no repeat requests allowed.')
    health = json.loads(request('http://127.0.0.1:8001/health'))
    if health.get('useRealLlm') is not False or health.get('realEmbeddingsEnabled') is not False:
        raise RuntimeError('Free-only AI guard failed; no publisher request made.')
    rows = json.loads(db(f"SELECT json_agg(x) FROM (SELECT id,title,url FROM news WHERE id IN ({','.join(map(str,IDS))}) AND coalesce(raw_content,'')='' ORDER BY id) x;"))
    if rows is None or tuple(row['id'] for row in rows) != IDS:
        raise RuntimeError('Recovery targets changed; review before executing.')
    jars = []
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    for group, name in [('org.jsoup','jsoup'),('com.fasterxml.jackson.core','jackson-databind'),('com.fasterxml.jackson.core','jackson-core'),('com.fasterxml.jackson.core','jackson-annotations'),('org.slf4j','slf4j-api'),('org.springframework','spring-core'),('org.springframework','spring-jcl')]:
        matches = sorted((cache/group/name).glob('*/*/*.jar'))
        if not matches: raise RuntimeError('Required installed runtime unavailable.')
        jars.append(str(matches[-1]))
    classpath = ':'.join([str(ROOT/'build/classes/java/main'), *jars])
    source = '''import com.finsight.news.collect.ArticleContentExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
class RecoveryParse { public static void main(String[] args) throws Exception {
 var extractor=new ArticleContentExtractor();var html=new String(System.in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
 var body=extractor.extractHtml(args[0],html).map(text->extractor.clean(args[1],text)).orElse("");
 System.out.print(new ObjectMapper().writeValueAsString(extractor.isUsable(args[1],body)?body:""));
}}'''
    results=[]; calls=0
    with tempfile.TemporaryDirectory(prefix='finsight-recover-') as temporary:
        driver=Path(temporary)/'RecoveryParse.java';driver.write_text(source)
        for row in rows:
            if calls: time.sleep(1.1)
            calls+=1
            try:
                html=request(row['url'])
            except urllib.error.HTTPError as error:
                results.append(dict(id=row['id'],status='실패',length=0))
                if error.code in (401,403,429,451,503): break
                continue
            except (urllib.error.URLError, TimeoutError):
                results.append(dict(id=row['id'],status='실패',length=0));continue
            if any(marker in html.lower() for marker in ('cf-chl-', 'captcha', 'access denied', 'too many requests', '서비스 이용 제한')):
                results.append(dict(id=row['id'],status='실패',length=0));break
            parsed=subprocess.run(['java','--class-path',classpath,str(driver),row['url'],row['title']],input=html,text=True,capture_output=True)
            if parsed.returncode: raise RuntimeError('Local parser failed; no retry, publisher output withheld.')
            body=json.loads(parsed.stdout)
            if not body:
                results.append(dict(id=row['id'],status='실패',length=0));continue
            rewrites={}
            for level in ('beginner','normal','analyst'):
                rewrites[level]=json.loads(request('http://127.0.0.1:8001/ai/news/rewrite',dict(title=row['title'],body=body[:20000],level=level)))
            assignments=['raw_content='+quote(body)]
            for level, rewrite in rewrites.items():
                summary=rewrite.get('summary')
                if not summary: raise RuntimeError('Free rewrite empty; no database write.')
                assignments.append('rewritten_'+level+'='+quote(summary))
                reason=(rewrite.get('importance_reasons') or {}).get(level) or rewrite.get('importance_reason') or ''
                assignments.append('importance_reason_'+level+'='+quote(reason))
            assignments.append('importance_reason='+quote(rewrites['normal'].get('importance_reason') or ''))
            db('BEGIN; UPDATE news SET '+','.join(assignments)+f" WHERE id={row['id']} AND coalesce(raw_content,'')=''; COMMIT;")
            results.append(dict(id=row['id'],status='성공',length=len(body)))
    tried={row['id'] for row in results}
    results.extend(dict(id=id,status='미실행(중단)',length=0) for id in IDS if id not in tried)
    evidence=dict(results=results,externalCalls=calls,paidLlmCalls=0)
    Path('/tmp/finsight-recovery-results.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
    print(json.dumps(evidence,ensure_ascii=False))
if __name__=='__main__': main()
