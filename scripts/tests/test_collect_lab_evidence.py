import copy
import os
import sys
import json
import shutil
import tempfile
import unittest
from unittest import mock

# Ensure scripts dir is on sys.path
SCRIPTS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from collect_lab_evidence import EvidenceCollector, ValidationError

class TestEvidenceCollector(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="test-collector-")
        self.collector = EvidenceCollector(repo_root=self.temp_dir, prompt="test-prompt")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_missing_reports_dir_raises(self):
        non_existent = os.path.join(self.temp_dir, "non-existent")
        with self.assertRaises(ValidationError) as ctx:
            self.collector.parse_surefire_reports(non_existent)
        self.assertIn("No Surefire XML reports found", str(ctx.exception))

    def test_malformed_xml_raises(self):
        reports_dir = os.path.join(self.temp_dir, "reports")
        os.makedirs(reports_dir, exist_ok=True)
        bad_xml = os.path.join(reports_dir, "TEST-bad.xml")
        with open(bad_xml, "w", encoding="utf-8") as f:
            f.write("<testsuite name='Bad'>unclosed tag")

        with self.assertRaises(ValidationError) as ctx:
            self.collector.parse_surefire_reports(reports_dir)
        self.assertIn("Malformed Surefire XML", str(ctx.exception))

    def test_xml_with_failure_or_error_detected(self):
        reports_dir = os.path.join(self.temp_dir, "reports")
        os.makedirs(reports_dir, exist_ok=True)
        fail_xml = os.path.join(reports_dir, "TEST-fail.xml")
        with open(fail_xml, "w", encoding="utf-8") as f:
            f.write("""<testsuite name="com.example.FailTest" tests="2" failures="1" errors="0" skipped="0" time="0.1">
                <testcase name="testPass" time="0.05"/>
                <testcase name="testFail" time="0.05">
                    <failure message="assertion failed">stacktrace</failure>
                </testcase>
            </testsuite>""")

        res = self.collector.parse_surefire_reports(reports_dir)
        self.assertEqual(res['total_tests'], 2)
        self.assertEqual(res['total_failures'], 1)
        self.assertEqual(res['classes']['com.example.FailTest']['status'], 'FAIL')

    def test_xml_with_skipped_detected(self):
        reports_dir = os.path.join(self.temp_dir, "reports")
        os.makedirs(reports_dir, exist_ok=True)
        skip_xml = os.path.join(reports_dir, "TEST-skip.xml")
        with open(skip_xml, "w", encoding="utf-8") as f:
            f.write("""<testsuite name="com.example.SkipTest" tests="1" failures="0" errors="0" skipped="1" time="0.01">
                <testcase name="testSkip" time="0.01">
                    <skipped/>
                </testcase>
            </testsuite>""")

        res = self.collector.parse_surefire_reports(reports_dir)
        self.assertEqual(res['total_skipped'], 1)
        self.assertEqual(res['classes']['com.example.SkipTest']['status'], 'FAIL')

    def test_nonzero_exit_code_in_manifest(self):
        meta = {
            "runId": "run-fail",
            "exitCode": 1,
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        backend_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta)
        self.assertIn("BUILD FAILURE (Exit Code: 1)", summary)
        self.assertNotIn("ALL PASS", summary)
        self.assertIn("FAILURES DETECTED", summary)

    def test_observation_missing_case_or_wrong_runid(self):
        obs_dir = os.path.join(self.temp_dir, "obs")
        os.makedirs(obs_dir, exist_ok=True)
        file_path = os.path.join(obs_dir, "proxy-observations.json")

        # Wrong runId
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump({"runId": "other-run", "scenarios": [{"caseId": "TX1"}]}, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="expected-run", required_sets={"proxy-observations.json": ["TX1"]})
        self.assertIn("Mismatched runId", str(ctx.exception))

        # Missing case
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump({"runId": "expected-run", "scenarios": [{"caseId": "TX1"}]}, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="expected-run", required_sets={"proxy-observations.json": ["TX1", "TX2"]})
        self.assertIn("Required caseId 'TX2' not found", str(ctx.exception))

    def test_valid_input_with_nested_suites_rollup(self):
        reports_dir = os.path.join(self.temp_dir, "nested_reports")
        sub1 = os.path.join(reports_dir, "sub1")
        sub2 = os.path.join(reports_dir, "sub2")
        os.makedirs(sub1, exist_ok=True)
        os.makedirs(sub2, exist_ok=True)

        with open(os.path.join(sub1, "TEST-A.xml"), "w", encoding="utf-8") as f:
            f.write('<testsuite name="ClassA" tests="2" failures="0" errors="0" skipped="0" time="0.2"/>')
        with open(os.path.join(sub2, "TEST-B.xml"), "w", encoding="utf-8") as f:
            f.write('<testsuite name="ClassB" tests="3" failures="0" errors="0" skipped="0" time="0.3"/>')

        res = self.collector.parse_surefire_reports(reports_dir)
        self.assertEqual(res['total_tests'], 5)
        self.assertEqual(res['total_failures'], 0)
        self.assertEqual(len(res['classes']), 2)

    def test_runner_failure_preserves_log_and_does_not_promote_pass(self):
        # 1. Seed existing PASS evidence with sentinel content at destination
        dest_dir = os.path.join(self.temp_dir, 'docs', 'evidence', self.collector.prompt)
        os.makedirs(dest_dir, exist_ok=True)
        sentinel_path = os.path.join(dest_dir, "sentinel.txt")
        with open(sentinel_path, "w", encoding="utf-8") as f:
            f.write("PREVIOUS_PASS_SENTINEL")

        # 2. Stub command that simulates runner deleting target dir, printing error, and exiting with 2
        stub_cmd = [
            sys.executable, "-c",
            "import sys, os, shutil; "
            "t = os.path.join('backend-app', 'target'); "
            "shutil.rmtree(t, ignore_errors=True) if os.path.exists(t) else None; "
            "print('Build failed at compile stage'); sys.exit(2)"
        ]

        # 3. Mock get_runtime_info so test does not depend on host environment
        with mock.patch.object(self.collector, 'get_runtime_info', return_value={'java': 'test-java', 'maven': 'test-mvn', 'docker': 'test-docker'}):
            with self.assertRaises(ValidationError) as ctx:
                self.collector.run_suite_and_collect(run_id="test-fail-run", maven_cmd_override=stub_cmd)
        self.assertIn("Maven execution failed with exit code 2", str(ctx.exception))

        # 4. Check staging directory: log and failure manifest must be preserved
        staging_dir = os.path.join(self.temp_dir, 'backend-app', 'target', 'lab-evidence', 'test-fail-run')
        staged_log = os.path.join(staging_dir, "maven-test-output.txt")
        staged_manifest = os.path.join(staging_dir, "manifest.json")
        self.assertTrue(os.path.exists(staged_log), "Staged log file must exist even if runner failed")
        self.assertTrue(os.path.exists(staged_manifest), "Staged failure manifest must exist")

        with open(staged_log, "r", encoding="utf-8") as f:
            log_content = f.read()
        self.assertIn("Build failed at compile stage", log_content)

        with open(staged_manifest, "r", encoding="utf-8") as f:
            manifest_data = json.load(f)
        self.assertEqual(manifest_data.get("exitCode"), 2)
        self.assertIn("exit code 2", manifest_data.get("failureReason", ""))

        # 5. Verify destination sentinel file is preserved (not overwritten by failed run)
        self.assertTrue(os.path.exists(sentinel_path), "Existing evidence must not be wiped on failure")
        with open(sentinel_path, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "PREVIOUS_PASS_SENTINEL")

    def test_missing_exit_code_in_summary(self):
        meta_missing = {
            "runId": "run-no-exit",
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        backend_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta_missing)
        self.assertIn("BUILD FAILURE (Exit Code: UNKNOWN/INVALID)", summary)
        self.assertNotIn("ALL PASS", summary)
        self.assertIn("FAILURES DETECTED", summary)

    def test_skipped_tests_in_summary_cannot_be_all_pass(self):
        meta = {
            "runId": "run-skip",
            "exitCode": 0,
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 1, 'classes': {}}
        backend_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta)
        self.assertIn("BUILD FAILURE (Exit Code: 0)", summary)
        self.assertNotIn("ALL PASS", summary)
        self.assertIn("FAILURES DETECTED", summary)

    def test_valid_zero_exit_all_pass_control(self):
        meta = {
            "runId": "run-pass",
            "exitCode": 0,
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        backend_res = {'total_tests': 5, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta)
        self.assertIn("BUILD SUCCESS", summary)
        self.assertIn("ALL PASS", summary)
        self.assertNotIn("FAILURES DETECTED", summary)

    def test_fetch_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "fetch-obs")
        os.makedirs(obs_dir, exist_ok=True)
        fetch_file = os.path.join(obs_dir, "fetch-observations.json")

        # 1. Invalid payload (missing measurements)
        invalid_payload = {
            "runId": "run-fetch",
            "scenarios": [
                {
                    "caseId": "NQ1",
                    "datasetId": "D1",
                    "strategy": "Baseline N+1"
                    # missing required fields
                }
            ]
        }
        with open(fetch_file, "w", encoding="utf-8") as f:
            json.dump(invalid_payload, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-fetch", required_sets={"fetch-observations.json": ["NQ1"]})
        self.assertIn("missing required field", str(ctx.exception))

        # 2. Valid fetch observation scenario
        valid_payload = {
            "runId": "run-fetch",
            "scenarios": [
                {
                    "caseId": "NQ1",
                    "datasetId": "D1",
                    "strategy": "Baseline N+1",
                    "orderCount": 6,
                    "distinctBuyerCount": 6,
                    "preparedStatementsAfterRootQuery": 1,
                    "preparedStatementsAfterMapping": 7,
                    "sqlStatements": ["select 1", "select 2"],
                    "resultRows": [{"id": i} for i in range(6)],
                    "assertionsPassed": True
                }
            ]
        }
        with open(fetch_file, "w", encoding="utf-8") as f:
            json.dump(valid_payload, f)

        validated = self.collector.validate_observations(obs_dir, run_id="run-fetch", required_sets={"fetch-observations.json": ["NQ1"]})
        self.assertEqual(validated, ["fetch-observations.json"])

    def test_pagination_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "page-obs")
        os.makedirs(obs_dir, exist_ok=True)
        page_file = os.path.join(obs_dir, "pagination-observations.json")

        # 1. Rejection without exceptionClass raises
        bad_rejection = {
            "runId": "run-page",
            "scenarios": [
                {
                    "caseId": "PG2",
                    "strategy": "Fail-fast guard",
                    "offset": 0,
                    "pageSize": 2,
                    "policyEnabled": True,
                    "outcome": "EXPECTED_REJECTION",
                    "preparedStatementCount": 0,
                    "buyerEntityLoadCount": 0,
                    "orderEntityLoadCount": 0,
                    "sqlStatements": [],
                    "returnedBuyerIds": [],
                    "resultRows": [],
                    "assertionsPassed": True
                    # missing exceptionClass
                }
            ]
        }
        with open(page_file, "w", encoding="utf-8") as f:
            json.dump(bad_rejection, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-page", required_sets={"pagination-observations.json": ["PG2"]})
        self.assertIn("EXPECTED_REJECTION must include exceptionClass", str(ctx.exception))

        # 2. Boolean count raises
        bad_counts = {
            "runId": "run-page",
            "scenarios": [
                {
                    "caseId": "PG1",
                    "strategy": "Unsafe in-memory",
                    "offset": 0,
                    "pageSize": 2,
                    "policyEnabled": False,
                    "outcome": "RETURNED_PAGE",
                    "preparedStatementCount": True, # bool is not allowed as count
                    "buyerEntityLoadCount": 5,
                    "orderEntityLoadCount": 10,
                    "sqlStatements": ["select 1"],
                    "returnedBuyerIds": [1, 2],
                    "resultRows": [{"id": 1}, {"id": 2}],
                    "assertionsPassed": True
                }
            ]
        }
        with open(page_file, "w", encoding="utf-8") as f:
            json.dump(bad_counts, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-page", required_sets={"pagination-observations.json": ["PG1"]})
        self.assertIn("must be a non-negative integer", str(ctx.exception))

        # 3. Valid pagination observations payload (PG1 to PG5)
        valid_payload = {
            "runId": "run-page",
            "scenarios": [
                {
                    "caseId": "PG1",
                    "strategy": "Unsafe in-memory",
                    "offset": 0,
                    "pageSize": 2,
                    "policyEnabled": False,
                    "outcome": "RETURNED_PAGE",
                    "preparedStatementCount": 1,
                    "buyerEntityLoadCount": 5,
                    "orderEntityLoadCount": 10,
                    "sqlStatements": ["select 1"],
                    "returnedBuyerIds": [1, 2],
                    "resultRows": [{"id": 1}, {"id": 2}],
                    "assertionsPassed": True
                },
                {
                    "caseId": "PG2",
                    "strategy": "Fail-fast guard",
                    "offset": 0,
                    "pageSize": 2,
                    "policyEnabled": True,
                    "outcome": "EXPECTED_REJECTION",
                    "preparedStatementCount": 0,
                    "buyerEntityLoadCount": 0,
                    "orderEntityLoadCount": 0,
                    "sqlStatements": [],
                    "returnedBuyerIds": [],
                    "resultRows": [],
                    "exceptionClass": "org.hibernate.HibernateException",
                    "assertionsPassed": True
                },
                {
                    "caseId": "PG5",
                    "strategy": "Empty page",
                    "offset": 99,
                    "pageSize": 2,
                    "policyEnabled": True,
                    "outcome": "RETURNED_PAGE",
                    "preparedStatementCount": 1,
                    "buyerEntityLoadCount": 0,
                    "orderEntityLoadCount": 0,
                    "sqlStatements": ["select id from buyer"],
                    "returnedBuyerIds": [],
                    "resultRows": [],
                    "assertionsPassed": True
                }
            ]
        }
        with open(page_file, "w", encoding="utf-8") as f:
            json.dump(valid_payload, f)

        validated = self.collector.validate_observations(
            obs_dir,
            run_id="run-page",
            required_sets={"pagination-observations.json": ["PG1", "PG2", "PG5"]}
        )
        self.assertEqual(validated, ["pagination-observations.json"])


    def test_query_plan_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "plan-obs")
        os.makedirs(obs_dir, exist_ok=True)
        plan_file = os.path.join(obs_dir, "query-plan-observations.json")

        # 1. Missing rawExplainAnalyzeJson raises
        bad_plan = {
            "runId": "run-plan",
            "scenarios": [
                {
                    "caseId": "EP1",
                    "queryId": "Q1",
                    "sqlTemplate": "select ...",
                    "params": {"buyerId": 42},
                    "actualIndexInventory": ["query_plan_trade_pkey"],
                    "resultSummary": {"validationPassed": True, "rowCount": 5},
                    "nodeSummaries": [{"nodeType": "Limit"}],
                    "rootMetrics": {"totalCost": 10.0},
                    "hypothesis": "Limit -> Sort -> Seq Scan",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True
                    # missing rawExplainAnalyzeJson
                }
            ]
        }
        with open(plan_file, "w", encoding="utf-8") as f:
            json.dump(bad_plan, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-plan", required_sets={"query-plan-observations.json": ["EP1"]})
        self.assertIn("missing required field 'rawExplainAnalyzeJson'", str(ctx.exception))

        # 2. Raw explain analyze missing actual metrics raises
        bad_actual_metrics = {
            "runId": "run-plan",
            "scenarios": [
                {
                    "caseId": "EP1",
                    "queryId": "Q1",
                    "sqlTemplate": "select ...",
                    "params": {"buyerId": 42},
                    "actualIndexInventory": ["query_plan_trade_pkey"],
                    "resultSummary": {"validationPassed": True, "rowCount": 5},
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit"}}], # missing Actual Rows
                    "rawExplainOnlyJson": [{"Plan": {"Node Type": "Limit"}}],
                    "nodeSummaries": [{"nodeType": "Limit"}],
                    "rootMetrics": {"totalCost": 10.0},
                    "hypothesis": "Limit -> Sort -> Seq Scan",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True
                }
            ]
        }
        with open(plan_file, "w", encoding="utf-8") as f:
            json.dump(bad_actual_metrics, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-plan", required_sets={"query-plan-observations.json": ["EP1"]})
        self.assertIn("must contain actual execution metrics", str(ctx.exception))

        # 3. Valid EP1..EP4 payload with both MATCHED and DIFFERENT hypothesis status
        valid_plan = {
            "runId": "run-plan",
            "scenarios": [
                {
                    "caseId": "EP1",
                    "queryId": "Q1",
                    "sqlTemplate": "select ...",
                    "params": {"buyerId": 42},
                    "actualIndexInventory": ["query_plan_trade_pkey"],
                    "resultSummary": {"validationPassed": True, "rowCount": 5},
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 5, "Actual Loops": 1}}],
                    "rawExplainOnlyJson": [{"Plan": {"Node Type": "Limit"}}],
                    "nodeSummaries": [{"nodeType": "Limit"}],
                    "rootMetrics": {"totalCost": 10.0, "actualRows": 5},
                    "hypothesis": "Limit -> Sort -> Seq Scan",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True
                },
                {
                    "caseId": "EP2",
                    "queryId": "Q1",
                    "sqlTemplate": "select ...",
                    "params": {"buyerId": 42},
                    "actualIndexInventory": ["query_plan_trade_pkey", "idx_qpt_buyer"],
                    "resultSummary": {"validationPassed": True, "rowCount": 5},
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 5, "Actual Loops": 1}}],
                    "rawExplainOnlyJson": None,
                    "nodeSummaries": [{"nodeType": "Limit"}],
                    "rootMetrics": {"totalCost": 5.0, "actualRows": 5},
                    "hypothesis": "Index Scan or Bitmap Heap Scan",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True
                },
                {
                    "caseId": "EP3",
                    "queryId": "Q1",
                    "sqlTemplate": "select ...",
                    "params": {"buyerId": 42},
                    "actualIndexInventory": ["query_plan_trade_pkey", "idx_qpt_buyer_status_recent"],
                    "resultSummary": {"validationPassed": True, "rowCount": 5},
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 5, "Actual Loops": 1}}],
                    "rawExplainOnlyJson": None,
                    "nodeSummaries": [{"nodeType": "Limit"}],
                    "rootMetrics": {"totalCost": 1.0, "actualRows": 5},
                    "hypothesis": "Index Scan stopping at limit without separate Sort",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True
                },
                {
                    "caseId": "EP4",
                    "queryId": "Q2",
                    "sqlTemplate": "select ... where status = ?",
                    "params": {"status": "COMPLETED"},
                    "actualIndexInventory": ["query_plan_trade_pkey", "idx_qpt_status"],
                    "resultSummary": {"validationPassed": True, "rowCount": 90000},
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 90000, "Actual Loops": 1}}],
                    "rawExplainOnlyJson": None,
                    "nodeSummaries": [{"nodeType": "Seq Scan"}],
                    "rootMetrics": {"totalCost": 2000.0, "actualRows": 90000},
                    "hypothesis": "Seq Scan due to low selectivity",
                    "hypothesisStatus": "DIFFERENT", # Allowed to be DIFFERENT without failing validation
                    "assertionsPassed": True
                }
            ]
        }
        with open(plan_file, "w", encoding="utf-8") as f:
            json.dump(valid_plan, f)

        validated = self.collector.validate_observations(
            obs_dir,
            run_id="run-plan",
            required_sets={"query-plan-observations.json": ["EP1", "EP2", "EP3", "EP4"]}
        )
        self.assertEqual(validated, ["query-plan-observations.json"])

    def test_keyset_pagination_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "keyset-obs")
        os.makedirs(obs_dir, exist_ok=True)
        keyset_file = os.path.join(obs_dir, "keyset-pagination-observations.json")

        # 1. KP1 missing rawExplainAnalyzeJson raises
        bad_kp1 = {
            "runId": "run-keyset",
            "scenarios": [
                {
                    "caseId": "KP1",
                    "pageSize": 20,
                    "assertionsPassed": True,
                    "deepPagesEqual": True,
                    "expectedIds": list(range(20)),
                    "returnedIds": {
                        "firstPage": list(range(20)),
                        "deepOffset": list(range(20)),
                        "deepKeyset": list(range(20))
                    },
                    "measurements": {
                        "first_page": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_offset": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_keyset": {"sql": "select", "params": {}, "returnedIds": list(range(20))} # missing raw plan
                    },
                    "hypothesisStatus": "MATCHED"
                }
            ]
        }
        with open(keyset_file, "w", encoding="utf-8") as f:
            json.dump(bad_kp1, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-keyset", required_sets={"keyset-pagination-observations.json": ["KP1"]})
        self.assertIn("missing rawExplainAnalyzeJson", str(ctx.exception))

        # 2. KP1 deep offset IDs mismatch deep keyset IDs raises
        mismatch_kp1 = {
            "runId": "run-keyset",
            "scenarios": [
                {
                    "caseId": "KP1",
                    "pageSize": 20,
                    "assertionsPassed": True,
                    "deepPagesEqual": True,
                    "returnedIds": {
                        "firstPage": list(range(20)),
                        "deepOffset": list(range(20)),
                        "deepKeyset": list(range(1, 21)) # mismatch!
                    },
                    "measurements": {
                        "first_page": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_offset": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_keyset": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]}
                    },
                    "hypothesisStatus": "MATCHED"
                }
            ]
        }
        with open(keyset_file, "w", encoding="utf-8") as f:
            json.dump(mismatch_kp1, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-keyset", required_sets={"keyset-pagination-observations.json": ["KP1"]})
        self.assertIn("must be non-empty and strictly equal", str(ctx.exception))

        # 3. Valid KP1..KP4 payload passes
        valid_keyset = {
            "runId": "run-keyset",
            "scenarios": [
                {
                    "caseId": "KP1",
                    "pageSize": 20,
                    "assertionsPassed": True,
                    "deepPagesEqual": True,
                    "expectedIds": list(range(20)),
                    "returnedIds": {
                        "firstPage": list(range(20)),
                        "deepOffset": list(range(20)),
                        "deepKeyset": list(range(20))
                    },
                    "measurements": {
                        "first_page": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_offset": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]},
                        "deep_keyset": {"sql": "select", "params": {}, "returnedIds": list(range(20)), "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Limit", "Actual Rows": 20, "Actual Loops": 1}}]}
                    },
                    "hypothesisStatus": "MATCHED"
                },
                {
                    "caseId": "KP2",
                    "pageSize": 7,
                    "assertionsPassed": True,
                    "page1Ids": list(range(7)),
                    "brokenPage2Ids": list(range(7)),
                    "correctPage2Ids": list(range(7)),
                    "fullTraversalIds": list(range(24)),
                    "fullTraversalPageSizes": [7, 7, 7, 3]
                },
                {
                    "caseId": "KP3",
                    "pageSize": 5,
                    "assertionsPassed": True,
                    "page1Ids": list(range(5)),
                    "offsetPage2Ids": list(range(5)),
                    "keysetPage2Ids": list(range(5)),
                    "offsetDuplicateId": 20,
                    "keysetHasDuplicate": False
                },
                {
                    "caseId": "KP4",
                    "pageSize": 5,
                    "assertionsPassed": True,
                    "page1Ids": list(range(5)),
                    "offsetPage2Ids": list(range(5)),
                    "keysetPage2Ids": list(range(5)),
                    "offsetSkippedId": 19,
                    "keysetHasSkipped": False
                }
            ]
        }
        with open(keyset_file, "w", encoding="utf-8") as f:
            json.dump(valid_keyset, f)

        validated = self.collector.validate_observations(
            obs_dir,
            run_id="run-keyset",
            required_sets={"keyset-pagination-observations.json": ["KP1", "KP2", "KP3", "KP4"]}
        )
        self.assertEqual(validated, ["keyset-pagination-observations.json"])


    def test_actual_metrics_missing_rows_or_loops_raises(self):
        obs_dir = os.path.join(self.temp_dir, "missing_actuals")
        os.makedirs(obs_dir, exist_ok=True)
        qp_file = os.path.join(obs_dir, "query-plan-observations.json")

        base_plan = {
            "schemaVersion": "1.0.0",
            "runId": "run-actuals",
            "timestamp": "2026-10-13T10:00:00Z",
            "scenarios": [
                {
                    "caseId": "EP1",
                    "queryId": "Q1",
                    "sqlTemplate": "SELECT ...",
                    "params": {},
                    "actualIndexInventory": [],
                    "resultSummary": {"rowCount": 10, "validationPassed": True},
                    "nodeSummaries": [{"nodeType": "Seq Scan"}],
                    "rootMetrics": {},
                    "hypothesis": "Hypothesis",
                    "hypothesisStatus": "MATCHED",
                    "assertionsPassed": True,
                    "rawExplainOnlyJson": [{"Plan": {"Node Type": "Seq Scan"}}],
                    "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Seq Scan"}}] # Missing both
                }
            ]
        }

        # 1. Missing both raises
        with open(qp_file, "w", encoding="utf-8") as f:
            json.dump(base_plan, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-actuals",
                required_sets={"query-plan-observations.json": ["EP1"]}
            )
        self.assertIn("both 'Actual Rows' and 'Actual Loops'", str(cm.exception))

        # 2. Missing Actual Rows only raises
        base_plan["scenarios"][0]["rawExplainAnalyzeJson"] = [{"Plan": {"Node Type": "Seq Scan", "Actual Loops": 1}}]
        with open(qp_file, "w", encoding="utf-8") as f:
            json.dump(base_plan, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-actuals",
                required_sets={"query-plan-observations.json": ["EP1"]}
            )
        self.assertIn("both 'Actual Rows' and 'Actual Loops'", str(cm.exception))

        # 3. Missing Actual Loops only raises
        base_plan["scenarios"][0]["rawExplainAnalyzeJson"] = [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 10}}]
        with open(qp_file, "w", encoding="utf-8") as f:
            json.dump(base_plan, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-actuals",
                required_sets={"query-plan-observations.json": ["EP1"]}
            )
        self.assertIn("both 'Actual Rows' and 'Actual Loops'", str(cm.exception))

        # 4. Both present passes
        base_plan["scenarios"][0]["rawExplainAnalyzeJson"] = [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 10, "Actual Loops": 1}}]
        with open(qp_file, "w", encoding="utf-8") as f:
            json.dump(base_plan, f)
        validated = self.collector.validate_observations(
            obs_dir, run_id="run-actuals",
            required_sets={"query-plan-observations.json": ["EP1"]}
        )
        self.assertEqual(validated, ["query-plan-observations.json"])

    def test_predicate_index_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "predicate_test")
        os.makedirs(obs_dir, exist_ok=True)
        pred_file = os.path.join(obs_dir, "predicate-index-observations.json")

        valid_pred = {
            "schemaVersion": "1.0.0",
            "runId": "run-pred",
            "timestamp": "2026-10-13T10:00:00Z",
            "scenarios": [
                {
                    "caseId": "IP1",
                    "description": "Prefix LIKE before and after B-tree",
                    "assertionsPassed": True,
                    "expectedIds": list(range(100)),
                    "returnedIds": {
                        "prefixNoIndex": list(range(100)),
                        "prefixBTree": list(range(100))
                    },
                    "measurements": {
                        "prefix_no_index": {
                            "sql": "SELECT ...",
                            "params": {"pattern": "Redstone-%"},
                            "returnedIds": list(range(100)),
                            "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 100, "Actual Loops": 1}}],
                            "report": {}
                        },
                        "prefix_btree": {
                            "sql": "SELECT ...",
                            "params": {"pattern": "Redstone-%"},
                            "returnedIds": list(range(100)),
                            "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Bitmap Heap Scan", "Actual Rows": 100, "Actual Loops": 1}}],
                            "report": {}
                        }
                    }
                },
                {
                    "caseId": "IP2",
                    "description": "Contains LIKE with ordinary B-tree",
                    "assertionsPassed": True,
                    "expectedIds": list(range(200)),
                    "returnedIds": list(range(200)),
                    "measurements": {
                        "contains_like": {
                            "sql": "SELECT ...",
                            "params": {"pattern": "%Redstone%"},
                            "returnedIds": list(range(200)),
                            "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 200, "Actual Loops": 1}}],
                            "report": {}
                        }
                    }
                },
                {
                    "caseId": "IP3",
                    "description": "lower(column) with ordinary index",
                    "assertionsPassed": True,
                    "expectedIds": [50000, 100001],
                    "returnedIds": [50000, 100001],
                    "rawEqualityReturnedIds": [],
                    "measurements": {
                        "lower_ordinary_index": {
                            "sql": "SELECT ...",
                            "params": {"value": "redstone-050000"},
                            "returnedIds": [50000, 100001],
                            "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Seq Scan", "Actual Rows": 2, "Actual Loops": 1}}],
                            "report": {}
                        }
                    }
                },
                {
                    "caseId": "IP4",
                    "description": "lower(column) with expression index",
                    "assertionsPassed": True,
                    "expectedIds": [50000, 100001],
                    "returnedIds": [50000, 100001],
                    "measurements": {
                        "lower_expression_index": {
                            "sql": "SELECT ...",
                            "params": {"value": "redstone-050000"},
                            "returnedIds": [50000, 100001],
                            "rawExplainAnalyzeJson": [{"Plan": {"Node Type": "Bitmap Heap Scan", "Actual Rows": 2, "Actual Loops": 1}}],
                            "report": {}
                        }
                    }
                }
            ]
        }

        # 1. Valid payload passes
        with open(pred_file, "w", encoding="utf-8") as f:
            json.dump(valid_pred, f)

        validated = self.collector.validate_observations(
            obs_dir,
            run_id="run-pred",
            required_sets={"predicate-index-observations.json": ["IP1", "IP2", "IP3", "IP4"]}
        )
        self.assertEqual(validated, ["predicate-index-observations.json"])

        # 2. IP1 missing measurement raises
        corrupted = copy.deepcopy(valid_pred)
        del corrupted["scenarios"][0]["measurements"]["prefix_btree"]
        with open(pred_file, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-pred",
                required_sets={"predicate-index-observations.json": ["IP1", "IP2", "IP3", "IP4"]}
            )
        self.assertIn("missing required measurement 'prefix_btree'", str(cm.exception))

        # 3. IP3 rawEqualityReturnedIds non-empty raises
        corrupted = copy.deepcopy(valid_pred)
        corrupted["scenarios"][2]["rawEqualityReturnedIds"] = [50000]
        with open(pred_file, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-pred",
                required_sets={"predicate-index-observations.json": ["IP1", "IP2", "IP3", "IP4"]}
            )
        self.assertIn("rawEqualityReturnedIds must be empty", str(cm.exception))

        # 4. IP2 count mismatch raises
        corrupted = copy.deepcopy(valid_pred)
        corrupted["scenarios"][1]["returnedIds"] = list(range(199))
        with open(pred_file, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-pred",
                required_sets={"predicate-index-observations.json": ["IP1", "IP2", "IP3", "IP4"]}
            )
        self.assertIn("returnedIds must have exactly 200 elements", str(cm.exception))

    def test_idempotency_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "idempotency_test")
        os.makedirs(obs_dir, exist_ok=True)
        file_path = os.path.join(obs_dir, "idempotency-observations.json")

        valid_payload = {
            "schemaVersion": "1.0.0",
            "runId": "run-idem",
            "timestamp": "2026-10-13T10:00:00Z",
            "scenarios": [
                {
                    "caseId": "ID1",
                    "description": "Sequential replay",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "stock": 3,
                        "purchaseCount": 1,
                        "purchaseId1": "p-1",
                        "purchaseId2": "p-1",
                        "replayed1": False,
                        "replayed2": True
                    }
                },
                {
                    "caseId": "ID2",
                    "description": "Key reuse",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "exQtyThrown": "IdempotencyConflictException",
                        "exSkuThrown": "IdempotencyConflictException",
                        "finalStock": 4,
                        "purchaseCount": 1
                    }
                },
                {
                    "caseId": "ID3",
                    "description": "Concurrent lock wait",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "workerAPid": 101,
                        "workerBPid": 102,
                        "observedBlocking": True,
                        "blockingPidList": [101],
                        "purchaseIdA": "p-3",
                        "purchaseIdB": "p-3"
                    }
                },
                {
                    "caseId": "ID4",
                    "description": "Scope isolation",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "stock": 7,
                        "purchaseCount": 3,
                        "pId1": "p-4a",
                        "pId2": "p-4b",
                        "pId3": "p-4c"
                    }
                },
                {
                    "caseId": "ID5",
                    "description": "SQL error rollback",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "observedSqlState": "23514",
                        "stockAfterRollback": 5,
                        "stockAfterRetry": 3,
                        "purchaseCountAfterRetry": 1
                    }
                },
                {
                    "caseId": "ID6",
                    "description": "Replay NOT_AVAILABLE after restock",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "replayResult": "NOT_AVAILABLE",
                        "replayFlag": True,
                        "newKeyResult": "SUCCESS",
                        "finalStock": 3,
                        "totalSold": 2
                    }
                },
                {
                    "caseId": "ID7",
                    "description": "Outer tx rollback",
                    "assertionsPassed": True,
                    "expected": {},
                    "observed": {
                        "stockAfterRollback": 5,
                        "stockAfterRetry": 3,
                        "purchaseCountAfterRetry": 1
                    }
                }
            ]
        }

        # 1. Valid payload passes
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(valid_payload, f)

        validated = self.collector.validate_observations(
            obs_dir,
            run_id="run-idem",
            required_sets={"idempotency-observations.json": [f"ID{i}" for i in range(1, 8)]}
        )
        self.assertEqual(validated, ["idempotency-observations.json"])

        # 2. ID3 observedBlocking False raises
        corrupted = copy.deepcopy(valid_payload)
        corrupted["scenarios"][2]["observed"]["observedBlocking"] = False
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-idem",
                required_sets={"idempotency-observations.json": [f"ID{i}" for i in range(1, 8)]}
            )
        self.assertIn("observedBlocking must be True", str(cm.exception))

        # 3. ID5 wrong SQLSTATE raises
        corrupted = copy.deepcopy(valid_payload)
        corrupted["scenarios"][4]["observed"]["observedSqlState"] = "42P01"
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-idem",
                required_sets={"idempotency-observations.json": [f"ID{i}" for i in range(1, 8)]}
            )
        self.assertIn("observedSqlState must be '23514'", str(cm.exception))

        # 4. ID6 conservation violated raises
        corrupted = copy.deepcopy(valid_payload)
        corrupted["scenarios"][5]["observed"]["finalStock"] = 4
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(corrupted, f)
        with self.assertRaises(ValidationError) as cm:
            self.collector.validate_observations(
                obs_dir, run_id="run-idem",
                required_sets={"idempotency-observations.json": [f"ID{i}" for i in range(1, 8)]}
            )
        self.assertIn("stock conservation violated", str(cm.exception))


if __name__ == '__main__':
    unittest.main()
