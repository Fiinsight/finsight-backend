import unittest
from unittest.mock import patch
import backfill_rewrites as batch


def article(id):
    return {'id': id, 'title': '수출 증가', 'url': 'https://fixture.test/' + str(id),
            'raw_content': '수출 증가. ' * 50, 'rewrite_mode': 'EXCERPT',
            'rewritten_beginner': '기존 발췌', 'rewritten_normal': '기존 발췌', 'rewritten_analyst': '기존 발췌'}


HEALTH = {'rewriteEnabled': True, 'provider': 'gemini', 'useRealLlm': False,
          'rewriteUsedCalls': 0, 'rewriteDailyLimit': 400, 'rewrite429Count': 0, 'rewriteStoppedToday': False}
SUCCESS = {'mode': 'REWRITTEN', 'beginner': '초보자예요.', 'normal': '일반 사실.', 'analyst': '분석 사실.'}


class BackfillTest(unittest.TestCase):
    def test_dry_run_has_no_ai_or_database_writes(self):
        with patch.object(batch, 'connection', return_value={}), patch.object(batch, 'query', return_value='313') as query, \
             patch.object(batch, 'request') as request, patch('builtins.print') as output:
            result = batch.run()
        self.assertEqual(result['targetCount'], 313)
        request.assert_not_called()
        self.assertNotIn('write', query.call_args.kwargs)
        self.assertIn('1872', output.call_args.args[0])

    def test_apply_limit_and_fallback_keep_existing_values(self):
        def request(path, body=None):
            if path == '/health': return dict(HEALTH)
            return SUCCESS if body['article_key'] == 'backfill-rewrites:1' else {'mode': 'EXCERPT', 'retryable': True}
        with patch.object(batch, 'connection', return_value={}), patch.object(batch, 'query', return_value='2'), \
             patch.object(batch, 'rows', return_value=[article(1), article(2)]) as rows, \
             patch.object(batch, 'request', side_effect=request), patch.object(batch, 'save', return_value=True) as save, patch('builtins.print'):
            result = batch.run(2, True)
        self.assertEqual((result['processed'], result['success'], result['excerpt']), (2, 1, 1))
        save.assert_called_once()
        self.assertIn('LIMIT 2', rows.call_args.args[1])
        self.assertEqual(result['successfulNewsIds'], [1])

    def test_429_circuit_stops_before_next_article(self):
        health = dict(HEALTH)
        def request(path, body=None):
            if path == '/health': return dict(health)
            health.update(rewrite429Count=3, rewriteStoppedToday=True)
            return {'mode': 'EXCERPT', 'retryable': True}
        with patch.object(batch, 'connection', return_value={}), patch.object(batch, 'query', return_value='5'), \
             patch.object(batch, 'rows', return_value=[article(1), article(2)]), patch.object(batch, 'request', side_effect=request), patch('builtins.print'):
            result = batch.run(5, True)
        self.assertEqual((result['processed'], result['quota429']), (1, 3))
        self.assertEqual(result['stopReason'], '429 하루 중단')

    def test_save_is_success_only_and_guards_existing_values(self):
        with patch.object(batch, 'query', return_value='1') as query:
            self.assertFalse(batch.save({}, article(1), {'mode': 'EXCERPT'}))
            query.assert_not_called()
            self.assertTrue(batch.save({}, article(1), SUCCESS))
        sql = query.call_args.args[1]
        self.assertIn("rewrite_mode='EXCERPT'", sql)
        self.assertIn("rewritten_beginner IS NOT DISTINCT FROM '기존 발췌'", sql)
        self.assertIn('WHERE id=1', sql)
        self.assertTrue(query.call_args.kwargs['write'])

    def test_apply_requires_a_limit_before_opening_database(self):
        with patch.object(batch, 'connection') as connect, self.assertRaises(ValueError):
            batch.run(apply=True)
        connect.assert_not_called()


if __name__ == '__main__': unittest.main()
