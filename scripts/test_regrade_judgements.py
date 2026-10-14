import io
import copy
import unittest
from unittest.mock import patch
import regrade_judgements as regrade


class RegradeDryRunTest(unittest.TestCase):
    def test_snapshot_only_reads_selects_and_can_read_pre_migration_rows(self):
        sql=[]
        def read(env, statement):
            sql.append(statement)
            return []
        with patch.object(regrade,'rows',side_effect=read): payload=regrade.snapshot({})
        self.assertTrue(all(statement.startswith('SELECT ') for statement in sql))
        self.assertFalse(any('UPDATE ' in statement or 'DELETE ' in statement for statement in sql))
        self.assertEqual(payload['judgements'],[])
        self.assertIn("j.comparison_date IS NULL",sql[1])
        self.assertIn("j.actual_direction='UNKNOWN'",sql[1])
        self.assertIn("NOT LIKE '%로컬 QA%'",sql[1])

    def test_report_outputs_counts_without_user_reason_or_feedback_text(self):
        plans=[{'id':1,'target':'KOSPI','baselineDate':'2026-10-06','comparisonDate':'2026-10-07','status':'NO_SAVED_CLOSES','feedback':'private reason'}]
        with patch('sys.stdout',new_callable=io.StringIO) as output: regrade.report(plans)
        self.assertIn('DB 변경·시세/AI 호출 0건',output.getvalue());self.assertIn('종가 부족 1건',output.getvalue())
        self.assertNotIn('private reason',output.getvalue())

    def fixture(self):
        saved={'id':3,'actual_direction':'UNKNOWN','comparison_date':None,'feedback_text':"이전 '근거'",'feedback_generated_at':'2026-10-06T07:00:00+00:00'}
        record={'id':3,'expected_judgement':saved,'expected_news':{'id':1,'title':'기업 정책 안내'}}
        plan={'id':3,'target':'KOSPI','baselineDate':'2026-10-06','comparisonDate':'2026-10-07','status':'READY',
              'actualDirection':'UP','actualChangePercent':2,'judgementClose':100,'comparisonClose':102,
              'feedback':{'verdict':'CORRECT','headline':'예측 방향이 같았어요','summary':"새 '근거' 피드백",'reasons':[{'title':t,'detail':'설명'} for t in ['기사 신호와 결과','내 근거 점검','시장 비교']],'nextTip':'시장 흐름 확인'}}
        return {'judgements':[record]},[plan]

    def test_default_and_explicit_dry_run_never_call_writer(self):
        payload,plans=self.fixture()
        for argv in [[],['--dry-run']]:
            with patch.object(regrade,'connection',return_value={}),patch.object(regrade,'snapshot',return_value=payload),patch.object(regrade,'preview',return_value=plans),patch.object(regrade,'query') as writer,patch('sys.stdout',new_callable=io.StringIO) as output:
                self.assertEqual(regrade.main(argv),0)
            writer.assert_not_called()
            self.assertIn('변경 예상 #3',output.getvalue())
            self.assertIn('"before": "UNKNOWN", "after": "UP"',output.getvalue())

    def test_apply_only_ready_updates_exact_columns_and_guards_the_snapshot_atomically(self):
        payload,plans=self.fixture()
        plans.append({'id':4,'status':'NO_SAVED_CLOSES'})
        with patch.object(regrade,'query') as writer:
            self.assertEqual(regrade.apply_ready({},payload,plans),1)
        args,kwargs=writer.call_args
        self.assertTrue(kwargs['write'])
        sql=args[1]
        self.assertTrue(sql.startswith('BEGIN;'))
        self.assertTrue(sql.endswith('COMMIT;'))
        self.assertIn("j.actual_direction='UNKNOWN' AND j.comparison_date IS NULL",sql)
        self.assertIn("NOT LIKE '%로컬 QA%'",sql)
        self.assertIn('to_jsonb(j)=',sql); self.assertIn('to_jsonb(n)=',sql)
        self.assertIn('CASE WHEN count(*)=1 THEN 1 ELSE 0 END',sql)
        self.assertIn("새 ''근거'' 피드백",sql)
        self.assertNotIn('j.id=4',sql)
        self.assertEqual(set(regrade.changes_for(plans[0])),{'target_symbol','actual_direction','actual_change_percent','comparison_date','judgement_close','comparison_close','feedback_text','feedback_headline','feedback_reasons'})
        self.assertNotIn('feedback_generated_at=',sql)

    def test_non_unknown_compared_and_local_qa_records_cannot_enter_writer(self):
        payload,plans=self.fixture()
        for change in ['direction','comparison','qa']:
            altered=copy.deepcopy(payload)
            row=altered['judgements'][0]
            if change=='direction': row['expected_judgement']['actual_direction']='UP'
            if change=='comparison': row['expected_judgement']['comparison_date']='2026-10-07'
            if change=='qa': row['expected_news']['title']='로컬 QA 기록 화면 검증'
            with patch.object(regrade,'query') as writer:
                with self.assertRaises(ValueError): regrade.apply_ready({},altered,plans)
            writer.assert_not_called()

    def test_apply_requires_explicit_flag_and_does_not_write_without_ready_prices(self):
        payload,plans=self.fixture()
        with patch.object(regrade,'connection',return_value={}),patch.object(regrade,'snapshot',return_value=payload),patch.object(regrade,'preview',return_value=plans),patch.object(regrade,'query') as writer,patch('sys.stdout',new_callable=io.StringIO) as output:
            self.assertEqual(regrade.main(['--apply']),0)
        self.assertTrue(writer.call_args.kwargs['write'])
        self.assertIn('APPLIED: 1건',output.getvalue())
        with patch.object(regrade,'query') as writer:
            self.assertEqual(regrade.apply_ready({},payload,[{'id':3,'status':'NO_SAVED_CLOSES'}]),0)
        writer.assert_not_called()

    def test_transaction_failure_is_not_retried_or_reported_as_applied(self):
        payload,plans=self.fixture()
        with patch.object(regrade,'connection',return_value={}),patch.object(regrade,'snapshot',return_value=payload),patch.object(regrade,'preview',return_value=plans),patch.object(regrade,'query',side_effect=RuntimeError('private SQL')) as writer,patch('sys.stdout',new_callable=io.StringIO) as output,patch('sys.stderr',new_callable=io.StringIO) as error:
            self.assertEqual(regrade.main(['--apply']),1)
        writer.assert_called_once()
        self.assertNotIn('APPLIED:',output.getvalue())
        self.assertNotIn('private SQL',error.getvalue())


if __name__ == '__main__': unittest.main()
