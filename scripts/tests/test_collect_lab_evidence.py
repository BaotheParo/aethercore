import unittest
import os
import sys
import tempfile
import json
import shutil
import xml.etree.ElementTree as ET

# Ensure scripts dir is in path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
from collect_lab_evidence import EvidenceCollector, ValidationError

class TestEvidenceCollector(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="test-collector-")
        self.collector = EvidenceCollector(repo_root=self.temp_dir, prompt="test-prompt")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_missing_reports_dir_raises(self):
        empty_dir = os.path.join(self.temp_dir, "empty-reports")
        os.makedirs(empty_dir, exist_ok=True)
        with self.assertRaises(ValidationError) as ctx:
            self.collector.parse_surefire_reports(empty_dir)
        self.assertIn("No Surefire XML reports found", str(ctx.exception))

    def test_malformed_xml_raises(self):
        rep_dir = os.path.join(self.temp_dir, "malformed-reports")
        os.makedirs(rep_dir, exist_ok=True)
        xml_file = os.path.join(rep_dir, "TEST-malformed.xml")
        with open(xml_file, "w", encoding="utf-8") as f:
            f.write("<testsuite name='bad' tests='1'>unclosed tag")

        with self.assertRaises(ValidationError) as ctx:
            self.collector.parse_surefire_reports(rep_dir)
        self.assertIn("Malformed Surefire XML in file", str(ctx.exception))

    def test_xml_with_failure_or_error_detected(self):
        rep_dir = os.path.join(self.temp_dir, "failure-reports")
        os.makedirs(rep_dir, exist_ok=True)
        xml_file = os.path.join(rep_dir, "TEST-failure.xml")
        content = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.aethercore.FailureTest" tests="2" failures="1" errors="0" skipped="0" time="0.123">
  <testcase name="testOne" time="0.050"/>
  <testcase name="testTwo" time="0.073">
    <failure message="expected 1 but was 2">Assertion failure</failure>
  </testcase>
</testsuite>
"""
        with open(xml_file, "w", encoding="utf-8") as f:
            f.write(content)

        res = self.collector.parse_surefire_reports(rep_dir)
        self.assertEqual(res['total_tests'], 2)
        self.assertEqual(res['total_failures'], 1)
        self.assertEqual(res['total_errors'], 0)
        self.assertEqual(res['classes']['com.aethercore.FailureTest']['status'], 'FAIL')

    def test_xml_with_skipped_detected(self):
        rep_dir = os.path.join(self.temp_dir, "skipped-reports")
        os.makedirs(rep_dir, exist_ok=True)
        xml_file = os.path.join(rep_dir, "TEST-skipped.xml")
        content = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.aethercore.SkippedTest" tests="2" failures="0" errors="0" skipped="1" time="0.050">
  <testcase name="testOne" time="0.050"/>
  <testcase name="testSkipped" time="0.000">
    <skipped/>
  </testcase>
</testsuite>
"""
        with open(xml_file, "w", encoding="utf-8") as f:
            f.write(content)

        res = self.collector.parse_surefire_reports(rep_dir)
        self.assertEqual(res['total_tests'], 2)
        self.assertEqual(res['total_skipped'], 1)
        self.assertEqual(res['classes']['com.aethercore.SkippedTest']['status'], 'FAIL')

    def test_nonzero_exit_code_in_manifest(self):
        meta = {
            "runId": "run-fail",
            "exitCode": 1,
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        backend_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta)
        self.assertIn("Reactor Result: BUILD FAILURE (Exit Code: 1)", summary)
        self.assertIn("SOME FAILED/SKIPPED", summary)

    def test_observation_missing_case_or_wrong_runid(self):
        obs_dir = os.path.join(self.temp_dir, "observations")
        os.makedirs(obs_dir, exist_ok=True)

        # 1. Wrong runId
        obs_path = os.path.join(obs_dir, "proxy-observations.json")
        with open(obs_path, "w", encoding="utf-8") as f:
            json.dump({
                "runId": "wrong-id",
                "scenarios": [{"caseId": f"TX{i}"} for i in range(1, 9)]
            }, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="expected-id", required_sets={"proxy-observations.json": [f"TX{i}" for i in range(1, 9)]})
        self.assertIn("Mismatched runId", str(ctx.exception))

        # 2. Missing required case (TX8 missing)
        with open(obs_path, "w", encoding="utf-8") as f:
            json.dump({
                "runId": "expected-id",
                "scenarios": [{"caseId": f"TX{i}"} for i in range(1, 8)]
            }, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="expected-id", required_sets={"proxy-observations.json": [f"TX{i}" for i in range(1, 9)]})
        self.assertIn("Required caseId 'TX8' not found", str(ctx.exception))

        # 3. Duplicate case
        with open(obs_path, "w", encoding="utf-8") as f:
            json.dump({
                "runId": "expected-id",
                "scenarios": [{"caseId": "TX1"}, {"caseId": "TX1"}]
            }, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="expected-id", required_sets={"proxy-observations.json": ["TX1"]})
        self.assertIn("Duplicate caseId 'TX1'", str(ctx.exception))

    def test_valid_input_with_nested_suites_rollup(self):
        rep_dir = os.path.join(self.temp_dir, "nested-reports")
        os.makedirs(rep_dir, exist_ok=True)

        parent_xml = os.path.join(rep_dir, "TEST-com.aethercore.ParentTest.xml")
        nested_a_xml = os.path.join(rep_dir, "TEST-com.aethercore.ParentTest$NestedA.xml")
        nested_b_xml = os.path.join(rep_dir, "TEST-com.aethercore.ParentTest$NestedB.xml")

        with open(parent_xml, "w", encoding="utf-8") as f:
            f.write('<testsuite name="com.aethercore.ParentTest" tests="0" failures="0" errors="0" skipped="0" time="0.010"/>')
        with open(nested_a_xml, "w", encoding="utf-8") as f:
            f.write('<testsuite name="com.aethercore.ParentTest$NestedA" tests="3" failures="0" errors="0" skipped="0" time="0.025"/>')
        with open(nested_b_xml, "w", encoding="utf-8") as f:
            f.write('<testsuite name="com.aethercore.ParentTest$NestedB" tests="2" failures="0" errors="0" skipped="0" time="0.015"/>')

        res = self.collector.parse_surefire_reports(rep_dir)
        self.assertEqual(res['total_tests'], 5)
        self.assertEqual(res['total_failures'], 0)
        self.assertEqual(res['total_errors'], 0)
        self.assertEqual(res['total_skipped'], 0)
        self.assertEqual(len(res['classes']), 3)

        meta = {
            "runId": "run-nested",
            "exitCode": 0,
            "runtimeInfo": {}
        }
        summary = self.collector.generate_summary_text(res, res, meta)
        self.assertIn("REACTOR TOTAL: 10 Tests Run | 0 Failures | 0 Errors | 0 Skipped | ALL PASS", summary)

    def test_runner_failure_preserves_log_and_does_not_promote_pass(self):
        # Stub command that returns non-zero exit code (simulating compile or maven error)
        stub_cmd = [sys.executable, "-c", "import sys; print('Build failed at compile stage'); sys.exit(2)"]
        with self.assertRaises(ValidationError) as ctx:
            self.collector.run_suite_and_collect(run_id="test-fail-run", maven_cmd_override=stub_cmd)
        self.assertIn("Maven execution failed with exit code 2", str(ctx.exception))

        # Check staging directory: log must be preserved
        staging_dir = os.path.join(self.temp_dir, 'backend-app', 'target', 'lab-evidence', 'test-fail-run')
        staged_log = os.path.join(staging_dir, "maven-test-output.txt")
        self.assertTrue(os.path.exists(staged_log), "Staged log file must exist even if runner failed")

        with open(staged_log, "r", encoding="utf-8") as f:
            log_content = f.read()
        self.assertIn("Build failed at compile stage", log_content)

        # Verify docs/evidence was NOT created / NOT promoted
        docs_evidence = os.path.join(self.temp_dir, 'docs', 'evidence', 'test-prompt')
        self.assertFalse(os.path.exists(docs_evidence), "docs/evidence must not be promoted on failure")

    def test_missing_exit_code_in_summary(self):
        meta_missing = {
            "runId": "run-no-exit",
            "runtimeInfo": {}
        }
        core_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        backend_res = {'total_tests': 1, 'total_failures': 0, 'total_errors': 0, 'total_skipped': 0, 'classes': {}}
        summary = self.collector.generate_summary_text(core_res, backend_res, meta_missing)
        self.assertIn("BUILD FAILURE (Exit Code: UNKNOWN/INVALID)", summary)

    def test_fetch_observations_payload_validation(self):
        obs_dir = os.path.join(self.temp_dir, "fetch-obs")
        os.makedirs(obs_dir, exist_ok=True)
        fetch_file = os.path.join(obs_dir, "fetch-observations.json")

        # 1. Missing resultRows or count mismatch
        payload_invalid = {
            "runId": "run-fetch",
            "scenarios": [
                {
                    "caseId": "NQ1",
                    "datasetId": "D1",
                    "strategy": "BASELINE",
                    "orderCount": 6,
                    "distinctBuyerCount": 6,
                    "preparedStatementsAfterRootQuery": 1,
                    "preparedStatementsAfterMapping": 7,
                    "sqlStatements": ["SELECT 1"],
                    "resultRows": [{"orderId": 1}], # Only 1 row, expected 6
                    "assertionsPassed": True
                }
            ]
        }
        with open(fetch_file, "w", encoding="utf-8") as f:
            json.dump(payload_invalid, f)

        with self.assertRaises(ValidationError) as ctx:
            self.collector.validate_observations(obs_dir, run_id="run-fetch", required_sets={"fetch-observations.json": ["NQ1"]})
        self.assertIn("resultRows count (1) does not match orderCount (6)", str(ctx.exception))

        # 2. Valid fetch observation scenario
        payload_valid = {
            "runId": "run-fetch",
            "scenarios": [
                {
                    "caseId": "NQ1",
                    "datasetId": "D1",
                    "strategy": "BASELINE",
                    "orderCount": 2,
                    "distinctBuyerCount": 2,
                    "preparedStatementsAfterRootQuery": 1,
                    "preparedStatementsAfterMapping": 3,
                    "sqlStatements": ["SELECT 1", "SELECT 2"],
                    "resultRows": [{"orderId": 101}, {"orderId": 102}],
                    "assertionsPassed": True
                }
            ]
        }
        with open(fetch_file, "w", encoding="utf-8") as f:
            json.dump(payload_valid, f)

        validated = self.collector.validate_observations(obs_dir, run_id="run-fetch", required_sets={"fetch-observations.json": ["NQ1"]})
        self.assertEqual(validated, ["fetch-observations.json"])

if __name__ == "__main__":
    unittest.main()
