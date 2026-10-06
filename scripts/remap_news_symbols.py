#!/usr/bin/env python3
"""Preview stored-news symbols without collection, reprocessing or external requests.

Default is read-only. --apply requires the explicitly reviewed preview file.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from seed_demo import validate, quote

ROOT = Path(__file__).resolve().parents[1]

def db(sql):
    values = {}
    for line in (ROOT / '.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.removeprefix('export ').split('=', 1)
            values[key.strip()] = value.strip().strip('\"\'')
    match = re.fullmatch(r'(?:jdbc:)?postgresql://([^/:]+)(?::(\d+))?/([^?]+)', values.get('DB_URL', values.get('SPRING_DATASOURCE_URL', '')))
    if not match:
        raise ValueError('Unrecognized local database URL; no query executed.')
    validate(match[1], match[3])
    env = dict(os.environ, PGHOST=match[1], PGPORT=match[2] or '5432', PGDATABASE=match[3],
               PGUSER=values.get('DB_USERNAME', 'finsight'), PGPASSWORD=values.get('DB_PASSWORD', ''),
               PGOPTIONS='-c statement_timeout=5000 -c lock_timeout=5000')
    for key in ('PGSERVICE', 'PGSERVICEFILE'):
        env.pop(key, None)
    result = subprocess.run(['psql', '-X', '-A', '-t', '--no-password', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, env=env, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Local SQL failed; connection details withheld.')
    return result.stdout.strip()

def preview():
    rows = json.loads(db("""SELECT coalesce(json_agg(x),'[]'::json) FROM (
        SELECT n.id,n.title,coalesce(n.raw_content,'') AS body,n.related_symbol AS previous,
            (SELECT count(*) FROM judgement j WHERE j.news_id=n.id) AS judgements
        FROM news n WHERE coalesce(n.source,'') NOT LIKE 'SYNTHETIC%'
        ORDER BY n.id LIMIT 1000) x;"""))
    source = '''import java.io.*; import java.util.*;
import com.finsight.news.collect.NewsSymbolMatcher;
class SymbolPreview { public static void main(String[] args) throws Exception {
 var matcher=new NewsSymbolMatcher(); var reader=new BufferedReader(new InputStreamReader(System.in));
 for(String line;(line=reader.readLine())!=null;){var cells=line.split("\\t",-1);
 var title=new String(Base64.getDecoder().decode(cells[1]),java.nio.charset.StandardCharsets.UTF_8);
 var body=new String(Base64.getDecoder().decode(cells[2]),java.nio.charset.StandardCharsets.UTF_8);
 var symbol=matcher.match(title,body);System.out.println(cells[0]+"\\t"+(symbol==null?"":symbol));}
}}'''
    with tempfile.TemporaryDirectory(prefix='finsight-symbol-') as temporary:
        driver = Path(temporary) / 'SymbolPreview.java'
        driver.write_text(source)
        encode = lambda value: base64.b64encode(value.encode()).decode()
        payload = ''.join(f"{row['id']}\t{encode(row['title'])}\t{encode(row['body'])}\n" for row in rows)
        result = subprocess.run(['java', '--class-path', str(ROOT / 'build/classes/java/main'), str(driver)],
                                input=payload, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError('Matcher execution failed; compile backend first. ' + result.stderr[:500])
    proposed = dict(line.split('\t', 1) for line in result.stdout.splitlines())
    return [dict(id=row['id'], title=row['title'], previous=row['previous'],
                 proposed=proposed[str(row['id'])] or None, judgements=row['judgements'])
            for row in rows if row['previous'] != (proposed[str(row['id'])] or None)]

def update_sql(rows):
    statements = ['BEGIN;']
    for row in rows:
        previous = 'NULL' if row['previous'] is None else quote(row['previous'])
        proposed = 'NULL' if row['proposed'] is None else quote(row['proposed'])
        if row['proposed'] is not None and not re.fullmatch(r'\d{6}|KOSPI|KOSDAQ', row['proposed']):
            raise ValueError('Invalid symbol')
        statements.append(f"UPDATE news SET related_symbol={proposed} WHERE id={int(row['id'])} AND related_symbol IS NOT DISTINCT FROM {previous};")
    return '\n'.join(statements + ['COMMIT;'])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--apply', type=Path, help='Run only after user approves this exact preview file')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        sql = update_sql([dict(id=211, previous='KOSPI', proposed='064350')])
        assert sql.count('UPDATE news SET related_symbol=') == 1
        assert 'IS NOT DISTINCT FROM' in sql and 'raw_content' not in sql
        assert update_sql([]) == 'BEGIN;\nCOMMIT;'
        for host, database in [('remote.test', 'finsight'), ('localhost', 'production')]:
            try: validate(host, database)
            except ValueError: pass
            else: raise AssertionError('Unsafe database accepted')
        print('self-test passed')
        return
    rows = preview()
    if args.apply:
        approved = json.loads(args.apply.read_text())
        if approved != rows:
            raise ValueError('Preview changed; nothing written. Review again.')
        print(db(update_sql(rows)))
    else:
        text = json.dumps(rows, ensure_ascii=False, indent=2)
        if args.output: args.output.write_text(text + '\n')
        print(text)

if __name__ == '__main__': main()
