import io
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

    def test_report_outputs_counts_without_user_reason_or_feedback_text(self):
        plans=[{'id':1,'target':'KOSPI','baselineDate':'2026-10-06','comparisonDate':'2026-10-07','status':'NO_SAVED_CLOSES','feedback':'private reason'}]
        with patch('sys.stdout',new_callable=io.StringIO) as output: regrade.report(plans)
        self.assertIn('DB 변경·시세/AI 호출 0건',output.getvalue());self.assertIn('종가 부족 1건',output.getvalue())
        self.assertNotIn('private reason',output.getvalue())


if __name__ == '__main__': unittest.main()
