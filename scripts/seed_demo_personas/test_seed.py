import unittest
from unittest.mock import patch
from datetime import datetime
import seed
import verify

class DemoSeedTest(unittest.TestCase):
    def test_scope_and_no_news_or_other_account_mutation(self):
        plan={name:{'quizzes':[],'notes':[],'judgements':[]} for name in seed.PERSONAS}
        sql=seed.sql_for(plan,{},'synthetic-hash',datetime(2026,10,13,tzinfo=seed.KST),set(seed.TABLES))
        self.assertTrue(sql.startswith('BEGIN;'))
        self.assertTrue(sql.endswith('COMMIT;'))
        self.assertEqual(sql.count('DELETE FROM'),15)
        for line in sql.splitlines():
            if line.startswith(('UPDATE','DELETE')):
                self.assertIn('@finsight.test',line)
                self.assertIn("provider='LOCAL'",line)
        self.assertNotIn('UPDATE news',sql)
        self.assertNotIn('DELETE FROM app_user',sql)
        with self.assertRaises(ValueError): seed.owner('ordinary-user')
        with self.assertRaises(ValueError): seed.sql_for({'other':{}},{},'',datetime.now(seed.KST),set())

    def test_remote_database_rejected(self):
        for url in ['jdbc:postgresql://remote/finsight','jdbc:postgresql://localhost/production']:
            with patch.object(seed,'env_values',return_value={'DB_URL':url}):
                with self.assertRaises(ValueError): seed.connection()

    def test_verify_omits_level_and_rejects_remote_before_login(self):
        with patch('sys.argv',['verify.py','--news-id','1','--base-url','https://remote.example']),patch.object(verify,'request') as request:
            with self.assertRaises(ValueError): verify.main()
            request.assert_not_called()
        def api(base,path,token=None,body=None):
            if path=='/api/auth/login': return {'accessToken':body['email']}
            novice=token.startswith('demo-beginner')
            if path=='/api/profile/onboarding': return {'learningLevel':'beginner' if novice else 'analyst','learningPainPoint':'terms' if novice else 'evidence'}
            if path=='/api/learning/progress': return {'weakAreas':[] if novice else [{'category':'금리·채권'}],'levelSuggestion':None,'activityStreakDays':5 if novice else 10}
            if path=='/api/learning/reviews': return [{'term':'금리' if novice else '수급'}]
            if path=='/api/learning/news/1': return {'level':'beginner' if novice else 'analyst','question':{'prompt':'정의' if novice else '사실'}}
            raise AssertionError('Unexpected path or explicit level')
        with patch('sys.argv',['verify.py','--news-id','1']),patch.object(verify,'request',side_effect=api),patch.object(verify,'password',return_value='mock-only'),patch('builtins.print'):
            self.assertEqual(verify.main(),0)

    def test_dry_run_never_hashes_password_or_writes_database(self):
        cat={'questions':[],'articles':[],'prices':[],'probabilities':{},'attempts':{}}
        with patch('sys.argv',['seed.py']), patch.object(seed,'connection',return_value={}), \
             patch.object(seed,'query',side_effect=lambda env,sql,write=False: 't' if 'current_database' in sql else ''), \
             patch.object(seed,'rows',return_value=[]), patch.object(seed,'snapshot',return_value={'news':[]}), \
             patch.object(seed,'catalog',return_value=cat), patch.object(seed,'password') as password,patch('builtins.print'):
            seed.main()
            password.assert_not_called()

    def test_insufficient_articles_do_not_generate_fiction(self):
        output,missing=seed.plan({'news':[]},{'questions':[],'articles':[],'prices':[]},datetime(2026,10,13,tzinfo=seed.KST))
        self.assertTrue(missing)
        self.assertTrue(all(not rows for persona in output.values() for rows in persona.values()))

    def test_price_evidence_is_recomputed_and_wrong_symbol_excluded(self):
        news=[{'id':i,'published_at':'2026-09-01T00:00:00Z','related_symbol':'005930','title':'실적 기사'} for i in (1,2)]
        prices=[{'news_id':i,'day':'2026-10-06','comparison_date':'2026-10-07','judgement_close':100,'comparison_close':98.24} for i in (1,2)]
        output,_=seed.plan({'news':news},{'questions':[],'articles':[{'id':1,'validSymbol':True,'category':'기업·종목'},{'id':2,'validSymbol':False,'category':'기업·종목'}],'prices':prices},datetime(2026,10,13,tzinfo=seed.KST))
        for name in ('demo-beginner','demo-investor'):
            judgments=output[name]['judgements']
            self.assertEqual(len(judgments),1)
            self.assertEqual(judgments[0]['news_id'],1)
            self.assertEqual(judgments[0]['change'],-1.76)
            self.assertEqual(judgments[0]['choice'],'DOWN')

    def test_early_midnight_stays_within_today_and_latest14days(self):
        news=[{'id':i,'published_at':'2026-09-01T00:00:00Z'} for i in range(1,301)]
        questions=[{'newsId':n['id'],'term':term,'requestedLevel':level,'level':level,'prompt':'실제 규칙','options':[],'correctIndex':0}
                   for n in news for term in ['금리','환율','기준금리','코스피','매출','영업이익','AI','반도체','밸류에이션','수급'] for level in ['beginner','normal','analyst']]
        now=datetime(2026,10,13,0,0,1,tzinfo=seed.KST)
        output,_=seed.plan({'news':news},{'questions':questions,'articles':[{'id':n['id'],'validSymbol':True,'category':'기업·종목'} for n in news],'prices':[]},now)
        for name,count,span in [('demo-beginner',25,5),('demo-investor',30,10)]:
            quizzes=output[name]['quizzes']
            self.assertEqual(len(quizzes),count)
            self.assertEqual(len({(q['newsId'],q['term']) for q in quizzes}),count)
            offsets={(now.date()-datetime.fromisoformat(q['at']).date()).days for q in quizzes}
            self.assertTrue(set(range(span))<=offsets)
            self.assertEqual(max(offsets),13)
            self.assertNotIn(span,offsets)
            self.assertTrue(all(datetime.fromisoformat(q['at'])<=now for q in quizzes))

if __name__=='__main__': unittest.main()
