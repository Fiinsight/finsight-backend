"""Apply a reviewed publisher image-URL plan; dry-run is the default. Never fetch image files."""
import argparse
import ipaddress
import json
import os
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit, parse_qs
from seed_demo_personas.seed import query, rows, quote


def connection():
    url = urlsplit(os.environ.get('DB_URL', '').removeprefix('jdbc:'))
    if url.scheme != 'postgresql' or not url.hostname or not url.path.strip('/'):
        raise ValueError('DB_URL is required')
    env = os.environ.copy()
    for key in ('PGSERVICE', 'PGSERVICEFILE', 'PGOPTIONS', 'PGHOSTADDR'):
        env.pop(key, None)
    env.update(PGHOST=url.hostname, PGPORT=str(url.port or 5432), PGDATABASE=url.path[1:],
               PGUSER=os.environ['DB_USERNAME'], PGPASSWORD=os.environ['DB_PASSWORD'], PGCONNECT_TIMEOUT='5')
    env['PGSSLMODE'] = parse_qs(url.query).get('sslmode', ['prefer'])[0]
    return env


def image_url(value):
    if not isinstance(value, str) or len(value) > 2000:
        return False
    try:
        url = urlsplit(value)
        if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
            return False
        host = url.hostname.lower()
        if host == 'localhost' or host.endswith('.localhost'):
            return False
        try:
            return ipaddress.ip_address(host).is_global
        except ValueError:
            return True
    except ValueError:
        return False


def run(plan, apply=False, limit=None):
    if not isinstance(plan, list) or len(plan) > 1000 or (apply and (limit is None or not 1 <= limit <= 1000)):
        raise ValueError('Reviewed plan and --apply --limit 1..1000 required')
    if any(not isinstance(row, dict) or not isinstance(row.get('id'), int) or row['id'] <= 0 for row in plan) or len({row['id'] for row in plan}) != len(plan):
        raise ValueError('Unique positive news IDs required')
    for row in plan:
        if not isinstance(row.get('url'), str) or datetime.fromisoformat(row['publishedAt']).tzinfo is None:
            raise ValueError('Source URL and timezone-aware publication time required')
    env = connection()
    exists = query(env, "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='news' AND column_name='image_url');") == 't'
    if apply and not exists:
        raise ValueError('V21 must be applied first')
    since = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()
    selected = plan[:limit] if limit is not None else plan
    counts = dict(selected=len(selected), wouldSet=0, updated=0, noImage=0, unchanged=0, outsideOrChanged=0, concurrentChange=0, requiresV21=not exists, apply=apply)
    guards = {}
    for row in selected:
        if image_url(row.get('imageUrl')):
            guards[row['id']] = ("id=" + str(row['id']) + " AND url=" + quote(row['url'])
                + " AND published_at IS NOT DISTINCT FROM " + quote(row['publishedAt'])
                + " AND published_at>=" + quote(since) + " AND published_at<=now() AND rewrite_mode='EXCERPT'"
                + " AND coalesce(source,'') NOT LIKE 'SYNTHETIC%' AND url NOT LIKE '%example.invalid%'")
    image_column = 'image_url' if exists else 'NULL::text AS image_url'
    current_rows = rows(env, 'SELECT id,' + image_column + ' FROM news WHERE '
                        + ' OR '.join('(' + guard + ')' for guard in guards.values())) if guards else []
    current = {row['id']: row for row in current_rows}
    for row in selected:
        image = row.get('imageUrl')
        if row['id'] not in guards:
            counts['noImage'] += 1
            continue
        guard = guards[row['id']]
        if row['id'] not in current:
            counts['outsideOrChanged'] += 1
            continue
        before = row.get('beforeImageUrl')
        if current[row['id']]['image_url'] == image:
            counts['unchanged'] += 1
            continue
        if current[row['id']]['image_url'] != before:
            counts['outsideOrChanged'] += 1
            continue
        counts['wouldSet'] += 1
        if apply:
            changed = query(env, 'WITH changed AS (UPDATE news SET image_url=' + quote(image) + ' WHERE ' + guard
                            + ' AND image_url IS NOT DISTINCT FROM ' + quote(before) + ' RETURNING id) SELECT count(*) FROM changed;', write=True)
            counts['updated' if changed == '1' else 'concurrentChange'] += 1
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--limit', type=int)
    args = parser.parse_args()
    if args.limit is not None and not 1 <= args.limit <= 1000:
        parser.error('--limit must be 1..1000')
    with open(args.plan, encoding='utf-8') as source:
        print(json.dumps(run(json.load(source), args.apply, args.limit), ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError):
        raise SystemExit('Image backfill stopped; verify the reviewed plan, V21 and DB connection. Credentials are not logged.') from None
