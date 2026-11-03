import unittest
from unittest.mock import patch
import backfill_news_categories as batch
ROW=dict(id=1,url='https://publisher.test/a',publishedAt='2026-11-03T01:00:00+00:00',beforeCategory='산업·기술',category='시장')
class CategoriesTest(unittest.TestCase):
 def test_dry_run_never_writes(self):
  with patch.object(batch,'connection',return_value={}),patch.object(batch,'rows',return_value=[{'category':'산업·기술'}]),patch.object(batch,'query') as write:
   result=batch.run([ROW]);self.assertEqual(result['wouldUpdate'],1);self.assertEqual(result['updated'],0);write.assert_not_called()
 def test_apply_guards_original_value_and_scope(self):
  with patch.object(batch,'connection',return_value={}),patch.object(batch,'rows',return_value=[{'category':'산업·기술'}]),patch.object(batch,'query',return_value='1') as write:
   self.assertEqual(batch.run([ROW],True,1)['updated'],1);sql=write.call_args.args[1];self.assertIn('category IS NOT DISTINCT FROM',sql);self.assertIn('published_at IS NOT DISTINCT FROM',sql);self.assertTrue(write.call_args.kwargs['write'])
 def test_unchanged_and_changed_rows_skip_without_write(self):
  for value,key in [('시장','unchanged'),('정책·경제','outsideOrChanged')]:
   with patch.object(batch,'connection',return_value={}),patch.object(batch,'rows',return_value=[{'category':value}]),patch.object(batch,'query') as write:
    self.assertEqual(batch.run([ROW],True,1)[key],1);write.assert_not_called()
 def test_explicit_limit_and_unique_valid_ids_required(self):
  with patch.object(batch,'connection',side_effect=AssertionError('must not connect')):
   for plan,apply in [([ROW],True),([ROW,ROW],False),([ROW|{'category':'unknown'}],False)]:
    with self.assertRaises(ValueError):batch.run(plan,apply)
if __name__=='__main__':unittest.main()
