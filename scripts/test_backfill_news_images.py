import unittest
from unittest.mock import patch
import backfill_news_images as batch

ROW = dict(id=1, url='https://publisher.test/article', publishedAt='2026-11-03T01:00:00Z', beforeImageUrl=None, imageUrl='https://cdn.publisher.test/photo.jpg')

class ImagesTest(unittest.TestCase):
    def test_dry_run_can_preview_before_v21_and_never_writes(self):
        calls=[]
        with patch.object(batch,'connection',return_value={}),patch.object(batch,'query',side_effect=lambda *a,**k:calls.append(k) or 'f'),patch.object(batch,'rows',return_value=[{'id':1,'image_url':None}]):
            result=batch.run([ROW])
        self.assertEqual(result['wouldSet'],1)
        self.assertEqual(result['updated'],0)
        self.assertTrue(result['requiresV21'])
        self.assertEqual(calls,[{}])

    def test_apply_requires_limit_before_connecting_and_v21_before_any_write(self):
        with patch.object(batch,'connection',side_effect=AssertionError('must not connect')):
            with self.assertRaises(ValueError):batch.run([ROW],True)
        with patch.object(batch,'connection',return_value={}),patch.object(batch,'query',return_value='f') as query:
            with self.assertRaises(ValueError):batch.run([ROW],True,1)
            self.assertEqual(query.call_count,1)

    def test_updates_only_url_with_publication_and_concurrent_value_guards(self):
        sql=[]
        def query(env,text,write=False):
            sql.append((text,write));return 't' if not write else '1'
        with patch.object(batch,'connection',return_value={}),patch.object(batch,'query',side_effect=query),patch.object(batch,'rows',return_value=[{'id':1,'image_url':None}]):result=batch.run([ROW],True,1)
        self.assertEqual(result['updated'],1)
        self.assertIn('SET image_url=',sql[-1][0])
        self.assertIn('published_at IS NOT DISTINCT FROM',sql[-1][0])
        self.assertIn('image_url IS NOT DISTINCT FROM NULL',sql[-1][0])
        self.assertTrue(sql[-1][1])

    def test_invalid_and_changed_rows_are_not_written(self):
        for value in ['file:///tmp/a','data:image/png,a','http://localhost/a','http://127.0.0.1/a','http://172.16.1.1/a','https://user:pass@host.test/a']:
            self.assertFalse(batch.image_url(value))
        with patch.object(batch,'connection',return_value={}),patch.object(batch,'query',return_value='t') as query,patch.object(batch,'rows',return_value=[{'id':1,'image_url':'https://new.publisher.test/a'}]):
            result=batch.run([ROW],True,1)
            self.assertEqual(result['outsideOrChanged'],1)
            self.assertEqual(query.call_count,1)

if __name__=='__main__':unittest.main()
