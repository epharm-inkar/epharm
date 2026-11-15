import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('financial_transition',Path(__file__).resolve().parents[1] / 'verify-financial-transition.py')
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class FinancialTransitionTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='financial-transition-test-')
        self.addCleanup(self.directory.cleanup)
        self.jar=Path(self.directory.name)/'app.jar'

    def artifact(self,resource=True,missing=None,obsolete=None,partial=False,
                 access=False,access_missing=None,access_obsolete=None,
                 session=False,session_missing=None,session_obsolete=None,
                 compound=False,compound_missing=None,compound_obsolete=None,
                 reward=False,reward_missing=None,reward_obsolete=None,
                 source_binding=False,source_binding_missing=None,source_binding_obsolete=None):
        with zipfile.ZipFile(self.jar,'w') as jar:
            if resource:
                jar.writestr(guard.RESOURCE,'\n'.join(f'{key}={value}' for key,value in guard.CAPABILITY.items()))
            if resource or partial:
                for name,markers in guard.WRITERS.items():
                    if name==missing or reward and name==reward_missing or source_binding and name==source_binding_missing: continue
                    body=' '.join(markers[1:] if name==obsolete else markers)
                    if reward:
                        body+=' '+' '.join(guard.REWARD_CLASSES.get(name,()))
                        if reward_obsolete and reward_obsolete[0]==name:
                            body=body.replace(reward_obsolete[1],'')
                    if source_binding:
                        body+=' '+' '.join(guard.SOURCE_BINDING_CLASSES.get(name,()))
                        if source_binding_obsolete and source_binding_obsolete[0]==name:
                            body=body.replace(source_binding_obsolete[1],'')
                    jar.writestr('BOOT-INF/classes/'+name,body)
            else:
                jar.writestr('BOOT-INF/classes/db/migration/V061__posm_staff_auth.sql','legacy fixture')
            if access:
                for name,markers in guard.ACCESS_CLASSES.items():
                    if name==access_missing: continue
                    jar.writestr('BOOT-INF/classes/'+name,' '.join(markers[1:] if name==access_obsolete else markers))
            if session:
                jar.writestr('BOOT-INF/classes/db/migration/V093__admin_password_session_version.sql','session schema')
                for name,markers in guard.SESSION_CLASSES.items():
                    if name==session_missing: continue
                    jar.writestr('BOOT-INF/classes/'+name,' '.join(
                        marker for marker in markers if session_obsolete!=(name,marker)))
            if compound:
                jar.writestr('BOOT-INF/classes/db/migration/V094__compound_recommendation_triggers.sql','compound schema')
                for name,markers in guard.COMPOUND_CLASSES.items():
                    if name==compound_missing: continue
                    jar.writestr('BOOT-INF/classes/'+name,' '.join(
                        marker for marker in markers if compound_obsolete!=(name,marker)))
            if reward:
                jar.writestr('BOOT-INF/classes/db/migration/V100__recommendation_rewards.sql','reward schema')
                for name,markers in guard.REWARD_CLASSES.items():
                    if name in guard.WRITERS or name==reward_missing or source_binding and name==source_binding_missing: continue
                    body=' '.join(marker for marker in markers if reward_obsolete!=(name,marker))
                    if source_binding:
                        body+=' '+' '.join(guard.SOURCE_BINDING_CLASSES.get(name,()))
                        if source_binding_obsolete and source_binding_obsolete[0]==name:
                            body=body.replace(source_binding_obsolete[1],'')
                    jar.writestr('BOOT-INF/classes/'+name,body)
            if source_binding:
                jar.writestr('BOOT-INF/classes/db/migration/V102__source_staff_bindings.sql','source binding schema')
                for name,markers in guard.SOURCE_BINDING_CLASSES.items():
                    if name in guard.WRITERS or reward and name in guard.REWARD_CLASSES or name==source_binding_missing: continue
                    body=' '.join(markers)
                    if source_binding_obsolete and source_binding_obsolete[0]==name:
                        body=body.replace(source_binding_obsolete[1],'')
                    jar.writestr('BOOT-INF/classes/'+name,body)
        return self.jar

    def test_legacy_artifact_only_allowed_before_protected_schema(self):
        self.artifact(resource=False)
        self.assertFalse(guard.verify(self.jar,False))
        with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_reviewed_artifact_allowed_on_both_schema_states(self):
        self.artifact()
        self.assertTrue(guard.verify(self.jar,False))
        self.assertTrue(guard.verify(self.jar,True))

    def test_wallet_capability_cannot_authorize_old_access_control_after_v066(self):
        self.artifact()  # Every financial marker is present, but managed access is absent.
        self.assertTrue(guard.verify(self.jar,True))
        with self.assertRaisesRegex(ValueError,'managed-access'):
            guard.verify(self.jar,True,True)

    def test_typed_access_artifact_supported_on_old_and_forward_schema(self):
        self.artifact(access=True)
        self.assertTrue(guard.verify(self.jar,False,False))
        self.assertTrue(guard.verify(self.jar,True,True))

    def test_every_access_component_and_scope_marker_is_required(self):
        for name in guard.ACCESS_CLASSES:
            with self.subTest(component=name):
                self.artifact(access=True,access_missing=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True,True)
                self.artifact(access=True,access_obsolete=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True,True)

    def test_new_access_migration_cannot_bypass_guard_on_a_legacy_database(self):
        self.artifact()
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V066__unified_admin_access.sql','new access schema')
        with self.assertRaisesRegex(ValueError,'managed-access'):
            guard.verify(self.jar,False)

    def test_new_session_migration_cannot_bypass_guard_on_a_legacy_database(self):
        self.artifact(access=True)
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V093__admin_password_session_version.sql','new session schema')
        with self.assertRaisesRegex(ValueError,'session-version'):
            guard.verify(self.jar,False)

    def test_session_schema_rejects_legacy_sessions_without_weakening_other_guards(self):
        self.artifact(access=True)
        self.assertTrue(guard.verify(self.jar,True,True,False))
        with self.assertRaisesRegex(ValueError,'session-version'):
            guard.verify(self.jar,True,True,True)
        self.artifact(resource=False)
        with self.assertRaisesRegex(ValueError,'session-version'):
            guard.verify(self.jar,False,False,True)
        self.artifact(access=True,session=True,missing=next(iter(guard.WRITERS)))
        with self.assertRaisesRegex(ValueError,'financial writer'):
            guard.verify(self.jar,True,True,True)
        self.artifact(session=True)
        with self.assertRaisesRegex(ValueError,'managed-access'):
            guard.verify(self.jar,True,True,True)

    def test_reviewed_session_chain_supported_before_and_after_schema_migration(self):
        self.artifact(access=True,session=True)
        self.assertTrue(guard.verify(self.jar,False,False,False))
        self.assertTrue(guard.verify(self.jar,True,True,True))
        self.artifact(resource=False)
        self.assertFalse(guard.verify(self.jar,False,False,False))

    def test_compound_schema_rejects_old_artifact_even_before_any_compound_rule_exists(self):
        self.artifact(access=True,session=True)
        self.assertTrue(guard.verify(self.jar,True,True,True,False))
        with self.assertRaisesRegex(ValueError,'compound-trigger'):
            guard.verify(self.jar,True,True,True,True)

    def test_new_compound_migration_cannot_bypass_guard_on_a_legacy_database(self):
        self.artifact(access=True,session=True)
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V094__compound_recommendation_triggers.sql','new compound schema')
        with self.assertRaisesRegex(ValueError,'compound-trigger'):
            guard.verify(self.jar,False)

    def test_reviewed_compound_chain_supported_before_and_after_schema_migration(self):
        self.artifact(access=True,session=True,compound=True)
        self.assertTrue(guard.verify(self.jar,False,False,False,False))
        self.assertTrue(guard.verify(self.jar,True,True,True,True))

    def test_every_compound_component_and_enforcement_marker_is_required(self):
        for name,markers in guard.COMPOUND_CLASSES.items():
            with self.subTest(component=name):
                self.artifact(access=True,session=True,compound=True,compound_missing=name)
                with self.assertRaisesRegex(ValueError,'compound-trigger'):
                    guard.verify(self.jar,True,True,True,True)
            for marker in markers:
                with self.subTest(component=name,marker=marker):
                    self.artifact(access=True,session=True,compound=True,compound_obsolete=(name,marker))
                    with self.assertRaisesRegex(ValueError,'compound-trigger'):
                        guard.verify(self.jar,False,False,False,False)

    def test_compound_support_cannot_weaken_financial_access_or_session_guards(self):
        self.artifact(access=True,session=True,compound=True,missing=next(iter(guard.WRITERS)))
        with self.assertRaisesRegex(ValueError,'financial writer'):
            guard.verify(self.jar,True,True,True,True)
        self.artifact(session=True,compound=True)
        with self.assertRaisesRegex(ValueError,'managed-access'):
            guard.verify(self.jar,True,True,True,True)
        self.artifact(access=True,compound=True)
        with self.assertRaisesRegex(ValueError,'session-version'):
            guard.verify(self.jar,True,True,True,True)

    def test_every_session_component_and_enforcement_marker_is_required(self):
        for name,markers in guard.SESSION_CLASSES.items():
            with self.subTest(component=name):
                self.artifact(access=True,session=True,session_missing=name)
                with self.assertRaisesRegex(ValueError,'session-version'):
                    guard.verify(self.jar,False,False,False)
            for marker in markers:
                with self.subTest(component=name,marker=marker):
                    self.artifact(access=True,session=True,session_obsolete=(name,marker))
                    with self.assertRaisesRegex(ValueError,'session-version'):
                        guard.verify(self.jar,True,True,True)

    def test_new_access_support_cannot_weaken_the_wallet_guard(self):
        self.artifact(access=True,missing=next(iter(guard.WRITERS)))
        with self.assertRaises(ValueError): guard.verify(self.jar,True,True)
        self.artifact(resource=False,access=True)
        with self.assertRaises(ValueError): guard.verify(self.jar,True,True)

    def test_descriptor_alone_cannot_hide_an_old_or_missing_writer(self):
        for name in guard.WRITERS:
            with self.subTest(writer=name):
                self.artifact(missing=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True)
                self.artifact(obsolete=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_new_financial_code_without_capability_rejected_before_migration_too(self):
        self.artifact(resource=False,partial=True)
        with self.assertRaises(ValueError): guard.verify(self.jar,False)

    def test_financial_migration_without_capability_rejected(self):
        with zipfile.ZipFile(self.jar,'w') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V064__recommendation_sale_bonus_source.sql','new')
        with self.assertRaises(ValueError): guard.verify(self.jar,False)

    def test_unknown_or_duplicate_capability_rejected(self):
        for body in ('epharm.finance.wallet=1\n','epharm.finance.wallet=2\n',
                     'epharm.finance.wallet=1\nepharm.finance.wallet=1\nepharm.finance.receipt-decisions=1\n'):
            with zipfile.ZipFile(self.jar,'w') as jar: jar.writestr(guard.RESOURCE,body)
            with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_duplicate_zip_paths_rejected_instead_of_trusting_last_writer(self):
        self.artifact()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(self.jar,'a') as jar:
                jar.writestr(guard.RESOURCE,'epharm.finance.wallet=1\n')
        with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_db_probe_uses_only_catalog_existence_not_racy_zero_counts_or_credentials(self):
        result=subprocess.CompletedProcess([],0,'protected\n','')
        with patch.object(guard.subprocess,'run',return_value=result) as run:
            self.assertTrue(guard.schema_is_protected())
        command=run.call_args.args[0]
        self.assertEqual(command[:4],['docker','exec','-i','epharm-postgres'])
        self.assertNotIn('password',' '.join(command).lower())
        sql=run.call_args.kwargs['input'].lower()
        self.assertIn('to_regclass',sql)
        self.assertNotIn('count(',sql)
        for mutation in ('insert ','update ','delete ','drop ','alter '): self.assertNotIn(mutation,sql)

    def test_unknown_schema_output_and_db_failure_fail_closed(self):
        for result in ('','0','protected\nlegacy','arbitrary private output'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,result,'')):
                with self.assertRaises(ValueError): guard.schema_is_protected()
        with patch.object(guard.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='must-not-leak-password')):
            with patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
                with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('must-not-leak',str(error.exception))

    def test_access_probe_is_read_only_catalog_presence_even_with_zero_managed_accounts(self):
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'protected\n','')) as run:
            self.assertTrue(guard.access_schema_is_protected())
        sql=run.call_args.kwargs['input'].lower()
        self.assertIn("to_regclass('public.unified_admin_profiles')",sql)
        self.assertIn("column_name='access_mode'",sql)
        self.assertNotIn('count(',sql)
        for mutation in ('insert ','update ','delete ','drop ','alter '): self.assertNotIn(mutation,sql)
        self.assertNotIn('password',' '.join(run.call_args.args[0]).lower())
        for output in ('','0','protected\nlegacy','private-unexpected-value'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,output,'')):
                with self.assertRaises(ValueError): guard.access_schema_is_protected()

    def test_session_probe_uses_catalog_presence_without_reading_admin_rows(self):
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'protected\n','')) as run:
            self.assertTrue(guard.session_schema_is_protected())
        sql=run.call_args.kwargs['input'].lower()
        self.assertIn('information_schema.columns',sql)
        self.assertIn("table_schema='public'",sql)
        self.assertIn("table_name='admin_users'",sql)
        self.assertIn("column_name='session_version'",sql)
        for forbidden in ('count(', 'from admin_users', 'max(', 'insert ', 'update ', 'delete ', 'drop ', 'alter '):
            self.assertNotIn(forbidden,sql)
        self.assertNotIn('password',' '.join(run.call_args.args[0]).lower())
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'legacy\n','')):
            self.assertFalse(guard.session_schema_is_protected())
        for output in ('','0','protected\nlegacy','private-unexpected-value'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,output,'')):
                with self.assertRaises(ValueError): guard.session_schema_is_protected()

    def test_compound_probe_uses_durable_named_check_without_reading_rule_rows(self):
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'protected\n','')) as run:
            self.assertTrue(guard.compound_schema_is_protected())
        sql=run.call_args.kwargs['input'].lower()
        self.assertIn('information_schema.table_constraints',sql)
        self.assertIn("table_schema='public'",sql)
        self.assertIn("table_name='rules'",sql)
        self.assertIn("constraint_name='rules_trigger_conditions_shape'",sql)
        self.assertIn("constraint_type='check'",sql)
        for forbidden in ('count(', 'from rules', 'jsonb', 'insert ', 'update ', 'delete ', 'drop ', 'alter '):
            self.assertNotIn(forbidden,sql)
        self.assertNotIn('password',' '.join(run.call_args.args[0]).lower())
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'legacy\n','')):
            self.assertFalse(guard.compound_schema_is_protected())
        for output in ('','0','protected\nlegacy','private-unexpected-value'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,output,'')):
                with self.assertRaises(ValueError): guard.compound_schema_is_protected()

    def test_oversized_compound_class_is_denied_without_reading_it(self):
        name=next(iter(guard.COMPOUND_CLASSES))
        self.artifact(access=True,session=True,compound=True,compound_missing=name)
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/'+name,'x'*(2*1024*1024+1))
        with self.assertRaisesRegex(ValueError,'compound-trigger'):
            guard.verify(self.jar,True,True,True,True)

    def test_generic_wallet_predecessor_is_denied_on_reward_schema_even_without_active_rewards(self):
        self.artifact(access=True,session=True,compound=True)
        self.assertTrue(guard.verify(self.jar,True,True,True,True))
        with self.assertRaisesRegex(ValueError,'recommendation-reward'):
            guard.verify(self.jar,True,True,True,True,True)

    def test_reward_artifact_retains_wallet_and_all_earlier_boundaries(self):
        self.artifact(access=True,session=True,compound=True,reward=True)
        self.assertTrue(guard.verify(self.jar,False))
        self.assertTrue(guard.verify(self.jar,True,True,True,True,True))
        self.artifact(access=True,session=True,compound=True,reward=True,missing=next(iter(guard.WRITERS)))
        with self.assertRaises(ValueError): guard.verify(self.jar,True,True,True,True,True)

    def test_packaged_reward_migration_requires_real_policy_before_first_start(self):
        self.artifact()
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V100__recommendation_rewards.sql','reward schema')
        with self.assertRaisesRegex(ValueError,'recommendation-reward'):
            guard.verify(self.jar,False)

    def test_every_reward_component_and_enforcement_marker_is_required(self):
        for name,markers in guard.REWARD_CLASSES.items():
            with self.subTest(component=name):
                self.artifact(reward=True,reward_missing=name)
                with self.assertRaisesRegex(ValueError,'recommendation-reward'):
                    guard.verify(self.jar,True,reward_protected=True)
            for marker in markers:
                with self.subTest(component=name,marker=marker):
                    self.artifact(reward=True,reward_obsolete=(name,marker))
                    with self.assertRaisesRegex(ValueError,'recommendation-reward'):
                        guard.verify(self.jar,True,reward_protected=True)

    def test_reward_probe_checks_empty_durable_schema_and_never_reads_business_rows(self):
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'protected\n','')) as run:
            self.assertTrue(guard.reward_schema_is_protected())
        sql=run.call_args.kwargs['input'].lower()
        for table in ('recommendation_reward_shows','recommendation_reward_receipts','recommendation_sale_rewards',
                      'recommendation_reward_audit','recommendation_reward_refund_holds'):
            self.assertIn(f"to_regclass('public.{table}')",sql)
        self.assertIn("column_name='reward_evidence'",sql)
        for forbidden in ('count(', 'from recommendation_', 'insert ', 'update ', 'delete ', 'drop ', 'alter '):
            self.assertNotIn(forbidden,sql)
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'legacy\n','')):
            self.assertFalse(guard.reward_schema_is_protected())
        for output in ('','0','protected\nlegacy','private-unexpected-value'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,output,'')):
                with self.assertRaises(ValueError): guard.reward_schema_is_protected()

    def test_oversized_reward_policy_is_denied(self):
        name='kz/epharm/receipts/service/RecommendationRewardCreditPolicy.class'
        self.artifact(reward=True,reward_missing=name)
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/'+name,'x'*(2*1024*1024+1))
        with self.assertRaisesRegex(ValueError,'recommendation-reward'):
            guard.verify(self.jar,True,reward_protected=True)

    def test_image_inspection_never_starts_backend_and_cleans_container_after_denial(self):
        source=self.artifact(resource=False)
        image='sha256:'+'a'*64
        container='b'*64
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=False), \
             patch.object(guard,'session_schema_is_protected',return_value=False), \
             patch.object(guard,'compound_schema_is_protected',return_value=False), \
             patch.object(guard,'reward_schema_is_protected',return_value=False), \
             patch.object(guard,'source_binding_schema_is_protected',return_value=False), \
             patch.object(guard.subprocess,'check_output',return_value=container) as create, \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaises(ValueError): guard.verify_image(image)
        self.assertEqual(create.call_args.args[0],['docker','create','--network','none',image])
        self.assertEqual(calls[-1],['docker','rm',container])
        self.assertFalse(any(command[1] in {'start','run','compose'} for command in calls))

    def test_image_live_access_probe_is_mandatory_before_a_wallet_only_target(self):
        source=self.artifact()
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True) as wallet, \
             patch.object(guard,'access_schema_is_protected',return_value=True) as access, \
             patch.object(guard,'session_schema_is_protected',return_value=False), \
             patch.object(guard,'compound_schema_is_protected',return_value=False), \
             patch.object(guard,'reward_schema_is_protected',return_value=False), \
             patch.object(guard,'source_binding_schema_is_protected',return_value=False), \
             patch.object(guard.subprocess,'check_output',return_value='b'*64), \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaisesRegex(ValueError,'managed-access'):
                guard.verify_image('sha256:'+'a'*64)
        wallet.assert_called_once(); access.assert_called_once()
        self.assertEqual(calls[-1],['docker','rm','b'*64])

    def test_image_live_session_probe_rejects_legacy_session_chain_and_cleans_container(self):
        source=self.artifact(access=True)
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True) as session, \
             patch.object(guard,'compound_schema_is_protected',return_value=False), \
             patch.object(guard,'reward_schema_is_protected',return_value=False), \
             patch.object(guard,'source_binding_schema_is_protected',return_value=False), \
             patch.object(guard.subprocess,'check_output',return_value='b'*64), \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaisesRegex(ValueError,'session-version'):
                guard.verify_image('sha256:'+'a'*64)
        session.assert_called_once()
        self.assertEqual(calls[-1],['docker','rm','b'*64])

    def test_failed_access_probe_cannot_fall_back_to_the_wallet_result_or_echo_credentials(self):
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='private-password')), \
             patch.object(guard.subprocess,'check_output') as create, \
             patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
            with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('private-password',str(error.exception))
        create.assert_not_called()

    def test_failed_session_probe_stops_before_image_inspection_without_echoing_credentials(self):
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='private-password')), \
             patch.object(guard.subprocess,'check_output') as create, \
             patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
            with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('private-password',str(error.exception))
        create.assert_not_called()

    def test_image_live_compound_probe_rejects_old_matcher_and_cleans_container(self):
        source=self.artifact(access=True,session=True)
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',return_value=True) as compound, \
             patch.object(guard,'reward_schema_is_protected',return_value=False), \
             patch.object(guard,'source_binding_schema_is_protected',return_value=False), \
             patch.object(guard.subprocess,'check_output',return_value='b'*64), \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaisesRegex(ValueError,'compound-trigger'):
                guard.verify_image('sha256:'+'a'*64)
        compound.assert_called_once()
        self.assertEqual(calls[-1],['docker','rm','b'*64])

    def test_failed_compound_probe_stops_before_image_inspection_without_echoing_credentials(self):
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='private-password')), \
             patch.object(guard.subprocess,'check_output') as create, \
             patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
            with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('private-password',str(error.exception))
        create.assert_not_called()

    def test_image_live_reward_probe_rejects_wallet_only_predecessor_and_cleans_container(self):
        source=self.artifact(access=True,session=True,compound=True)
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',return_value=True), \
             patch.object(guard,'reward_schema_is_protected',return_value=True) as reward, \
             patch.object(guard,'source_binding_schema_is_protected',return_value=False), \
             patch.object(guard.subprocess,'check_output',return_value='b'*64), \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaisesRegex(ValueError,'recommendation-reward'):
                guard.verify_image('sha256:'+'a'*64)
        reward.assert_called_once()
        self.assertEqual(calls[-1],['docker','rm','b'*64])

    def test_failed_reward_probe_stops_before_image_inspection_without_echoing_credentials(self):
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',return_value=True), \
             patch.object(guard,'reward_schema_is_protected',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='private-password')), \
             patch.object(guard.subprocess,'check_output') as create, \
             patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
            with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('private-password',str(error.exception))
        create.assert_not_called()

    def test_real_cli_reward_flag_rejects_wallet_only_and_accepts_reviewed_chain(self):
        command=['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),'--schema-protected',
                 '--access-protected','--session-protected','--compound-protected','--reward-protected']
        self.artifact(access=True,session=True,compound=True)
        self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
        self.artifact(access=True,session=True,compound=True,reward=True)
        self.assertEqual(0,subprocess.run(command,capture_output=True).returncode)

    def test_reward_capable_predecessor_is_denied_on_empty_source_binding_schema(self):
        self.artifact(access=True,session=True,compound=True,reward=True)
        self.assertTrue(guard.verify(self.jar,True,True,True,True,True))
        with self.assertRaisesRegex(ValueError,'source-binding'):
            guard.verify(self.jar,True,True,True,True,True,True)
        self.artifact(access=True,session=True,compound=True,reward=True,source_binding=True)
        self.assertTrue(guard.verify(self.jar,True,True,True,True,True,True))

    def test_source_binding_migration_cannot_be_authorized_by_reward_markers_alone(self):
        self.artifact(access=True,session=True,compound=True,reward=True)
        with zipfile.ZipFile(self.jar,'a') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V102__source_staff_bindings.sql','source binding schema')
        with self.assertRaisesRegex(ValueError,'source-binding'): guard.verify(self.jar,False)

    def test_source_binding_capable_predecessor_without_late_ack_identity_revalidation_is_denied(self):
        # v0.2.18 has source-binding authorization, but trusts a stored HQ mapping on replay.
        reward_service='kz/epharm/posm/service/RecommendationRewardService.class'
        previous_symbols=('kz/epharm/posm/staff/SourceStaffBindingService', 'authorizes',
                          'standardn_rps_binding', 'getSourceNamespace', 'getNativeSessionId', 'getWorkstationId')
        with patch.dict(guard.SOURCE_BINDING_CLASSES,{reward_service:previous_symbols}):
            self.artifact(access=True,session=True,compound=True,reward=True,source_binding=True)
        for protected in (False,True):
            with self.subTest(source_binding_protected=protected):
                with self.assertRaisesRegex(ValueError,'source-binding'):
                    guard.verify(self.jar,True,True,True,True,True,protected)
        self.artifact(access=True,session=True,compound=True,reward=True,source_binding=True)
        self.assertTrue(guard.verify(self.jar,True,True,True,True,True,True))

    def test_every_source_binding_component_and_enforcement_marker_is_required(self):
        for name,markers in guard.SOURCE_BINDING_CLASSES.items():
            with self.subTest(component=name):
                self.artifact(reward=True,source_binding=True,source_binding_missing=name)
                with self.assertRaisesRegex(ValueError,'source-binding'):
                    guard.verify(self.jar,True,source_binding_protected=True)
            for marker in markers:
                with self.subTest(component=name,marker=marker):
                    self.artifact(reward=True,source_binding=True,source_binding_obsolete=(name,marker))
                    with self.assertRaisesRegex(ValueError,'source-binding'):
                        guard.verify(self.jar,True,source_binding_protected=True)

    def test_source_binding_boundary_cannot_weaken_reward_wallet_or_access_guards(self):
        self.artifact(source_binding=True)
        with self.assertRaisesRegex(ValueError,'recommendation-reward'):
            guard.verify(self.jar,False,source_binding_protected=True)
        for missing in (next(iter(guard.WRITERS)),next(iter(guard.REWARD_CLASSES))):
            self.artifact(reward=True,source_binding=True,missing=missing,reward_missing=missing)
            with self.assertRaises(ValueError): guard.verify(self.jar,True,source_binding_protected=True)
        self.artifact(reward=True,source_binding=True)
        with self.assertRaisesRegex(ValueError,'managed-access'):
            guard.verify(self.jar,True,access_protected=True,source_binding_protected=True)

    def test_source_binding_probe_is_durable_read_only_and_unknown_state_fails_closed(self):
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'protected\n','')) as run:
            self.assertTrue(guard.source_binding_schema_is_protected())
        sql=run.call_args.kwargs['input'].lower()
        for table in ('posm_source_staff_bindings','posm_source_staff_binding_audit'):
            self.assertIn(f"to_regclass('public.{table}')",sql)
        self.assertIn("table_name='posm_device_staff_sessions'",sql)
        self.assertIn("'source_verified_at'",sql)
        for forbidden in ('count(', 'from posm_', 'insert ', 'update ', 'delete ', 'drop ', 'alter '):
            self.assertNotIn(forbidden,sql)
        with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'legacy\n','')):
            self.assertFalse(guard.source_binding_schema_is_protected())
        for output in ('','0','protected\nlegacy','private-unexpected-value'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,output,'')):
                with self.assertRaises(ValueError): guard.source_binding_schema_is_protected()

    def test_live_source_binding_probe_rejects_reward_only_target_and_cleans_container(self):
        source=self.artifact(access=True,session=True,compound=True,reward=True)
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',return_value=True), \
             patch.object(guard,'reward_schema_is_protected',return_value=True), \
             patch.object(guard,'source_binding_schema_is_protected',return_value=True) as binding, \
             patch.object(guard.subprocess,'check_output',return_value='b'*64), \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaisesRegex(ValueError,'source-binding'): guard.verify_image('sha256:'+'a'*64)
        binding.assert_called_once()
        self.assertEqual(calls[-1],['docker','rm','b'*64])

    def test_failed_source_binding_probe_stops_before_image_inspection_without_private_errors(self):
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard,'access_schema_is_protected',return_value=True), \
             patch.object(guard,'session_schema_is_protected',return_value=True), \
             patch.object(guard,'compound_schema_is_protected',return_value=True), \
             patch.object(guard,'reward_schema_is_protected',return_value=True), \
             patch.object(guard,'source_binding_schema_is_protected',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='private-password')), \
             patch.object(guard.subprocess,'check_output') as create, \
             patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
            with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('private-password',str(error.exception)); create.assert_not_called()

    def test_real_cli_source_binding_flag_rejects_reward_only_and_accepts_reviewed_chain(self):
        command=['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),'--schema-protected',
                 '--access-protected','--session-protected','--compound-protected','--reward-protected','--source-binding-protected']
        self.artifact(access=True,session=True,compound=True,reward=True)
        self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
        self.artifact(access=True,session=True,compound=True,reward=True,source_binding=True)
        self.assertEqual(0,subprocess.run(command,capture_output=True).returncode)

    def test_real_cli_access_flag_rejects_wallet_only_and_accepts_typed_scope_fixture(self):
        command=['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),'--schema-protected','--access-protected']
        self.artifact()
        self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
        self.artifact(access=True)
        self.assertEqual(0,subprocess.run(command,capture_output=True).returncode)

    def test_real_cli_session_flag_rejects_old_session_chain_and_accepts_reviewed_chain(self):
        command=['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),
                 '--schema-protected','--access-protected','--session-protected']
        self.artifact(access=True)
        self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode)
        self.artifact(access=True,session=True)
        self.assertEqual(0,subprocess.run(command,capture_output=True).returncode)

    def test_real_cli_compound_flag_rejects_old_matcher_and_accepts_reviewed_chain(self):
        command=['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),
                 '--schema-protected','--access-protected','--session-protected','--compound-protected']
        self.artifact(access=True,session=True)
        denied=subprocess.run(command,capture_output=True,text=True)
        self.assertNotEqual(0,denied.returncode)
        self.assertIn('compound',denied.stderr)
        self.artifact(access=True,session=True,compound=True)
        accepted=subprocess.run(command,capture_output=True,text=True)
        self.assertEqual(0,accepted.returncode)
        self.assertIn('compound-trigger, recommendation-reward and source-binding guards checked',accepted.stdout)

    def test_wrong_artifact_fails_closed_with_compound_flag(self):
        self.jar.write_bytes(b'not a Java archive')
        result=subprocess.run(['python3',str(Path(SPEC.origin)),'--jar',str(self.jar),'--compound-protected'],
                              capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('state unknown',result.stderr)

    def test_exact_image_required_and_live_schema_probe_cannot_be_overridden(self):
        for args in (['--image','epharm/backend:latest'],['--image','sha256:'+'a'*64,'--schema-protected'],
                     ['--image','sha256:'+'a'*64,'--access-protected'],
                     ['--image','sha256:'+'a'*64,'--session-protected'],
                     ['--image','sha256:'+'a'*64,'--compound-protected'],
                     ['--image','sha256:'+'a'*64,'--reward-protected'],
                     ['--image','sha256:'+'a'*64,'--source-binding-protected']):
            with patch('sys.argv',['guard']+args),patch.object(guard,'verify_image') as inspect:
                with self.assertRaises(SystemExit): guard.main()
                inspect.assert_not_called()


if __name__=='__main__': unittest.main()
