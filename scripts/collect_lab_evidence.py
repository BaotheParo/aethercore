import os
import sys
import glob
import json
import uuid
import datetime
import tempfile
import shutil
import subprocess
import argparse
import xml.etree.ElementTree as ET

class ValidationError(Exception):
    """Raised when evidence collection fails validation rules."""
    pass

class EvidenceCollector:
    def __init__(self, repo_root=None, prompt="prompt15"):
        if repo_root is None:
            self.repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
        else:
            self.repo_root = os.path.abspath(repo_root)
        self.prompt = prompt

    def get_runtime_info(self):
        info = {
            "java": "NOT_CAPTURED",
            "maven": "NOT_CAPTURED",
            "docker": "NOT_CAPTURED"
        }
        try:
            java_out = subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).strip()
            first_line = java_out.splitlines()[0] if java_out else "NOT_CAPTURED"
            info["java"] = first_line
        except Exception:
            pass

        try:
            mvn_bin = shutil.which('mvn') or ('mvn.cmd' if os.name == 'nt' else 'mvn')
            mvn_out = subprocess.check_output([mvn_bin, '-version'], text=True).strip()
            first_line = mvn_out.splitlines()[0] if mvn_out else "NOT_CAPTURED"
            info["maven"] = first_line
        except Exception:
            pass

        try:
            docker_out = subprocess.check_output(['docker', 'version', '--format', '{{.Server.Version}}'], text=True).strip()
            info["docker"] = f"Docker Server {docker_out}"
        except Exception:
            pass

        return info

    def parse_surefire_reports(self, reports_dir):
        """
        Parses all TEST-*.xml in reports_dir or subdirectories.
        Returns dict of class_name -> stats, and aggregated totals.
        """
        xml_files = glob.glob(os.path.join(reports_dir, '**', 'TEST-*.xml'), recursive=True)
        if not xml_files:
            xml_files = glob.glob(os.path.join(reports_dir, 'TEST-*.xml'))

        if not xml_files:
            raise ValidationError(f"No Surefire XML reports found in {reports_dir}")

        class_data = {}
        total_tests = 0
        total_failures = 0
        total_errors = 0
        total_skipped = 0

        for f in sorted(xml_files):
            try:
                tree = ET.parse(f)
            except ET.ParseError as e:
                raise ValidationError(f"Malformed Surefire XML in file {f}: {e}")

            root = tree.getroot()
            cname = root.attrib.get('name')
            if not cname:
                continue

            try:
                tests = int(root.attrib.get('tests', 0))
                failures = int(root.attrib.get('failures', 0))
                errors = int(root.attrib.get('errors', 0))
                skipped = int(root.attrib.get('skipped', 0))
                time_val = root.attrib.get('time', '0')
            except ValueError as e:
                raise ValidationError(f"Invalid numeric attributes in {f}: {e}")

            # Check individual testcases if root summary is suspicious
            tc_failures = len(root.findall('.//failure'))
            tc_errors = len(root.findall('.//error'))
            tc_skipped = len(root.findall('.//skipped'))
            if tc_failures > failures:
                failures = tc_failures
            if tc_errors > errors:
                errors = tc_errors
            if tc_skipped > skipped:
                skipped = tc_skipped

            class_data[cname] = {
                'tests': tests,
                'failures': failures,
                'errors': errors,
                'skipped': skipped,
                'time': time_val,
                'status': 'PASS' if (failures == 0 and errors == 0 and skipped == 0) else 'FAIL'
            }

            total_tests += tests
            total_failures += failures
            total_errors += errors
            total_skipped += skipped

        if total_tests == 0:
            raise ValidationError(f"Total tests run is 0 in reports dir: {reports_dir}")

        return {
            'classes': class_data,
            'total_tests': total_tests,
            'total_failures': total_failures,
            'total_errors': total_errors,
            'total_skipped': total_skipped
        }

    def validate_observations(self, staging_dir, run_id=None, required_sets=None):
        """
        Validates raw observation JSON files in staging_dir.
        Checks matching runId, no duplicate caseId, all required cases present,
        and semantic/payload constraints on measurements.
        """
        if required_sets is None:
            required_sets = {
                'proxy-observations.json': [f"TX{i}" for i in range(1, 9)],
                'propagation-observations.json': [f"PR{i}" for i in range(1, 7)],
                'deadlock-observations.json': [f"DL{i}" for i in range(1, 4)],
                'fetch-observations.json': [f"NQ{i}" for i in range(1, 6)],
                'pagination-observations.json': [f"PG{i}" for i in range(1, 6)],
                'query-plan-observations.json': [f"EP{i}" for i in range(1, 5)],
                'keyset-pagination-observations.json': [f"KP{i}" for i in range(1, 5)],
                'predicate-index-observations.json': [f"IP{i}" for i in range(1, 5)]
            }

        validated = []
        for filename, expected_cases in required_sets.items():
            fpath = os.path.join(staging_dir, filename)
            if not os.path.exists(fpath):
                raise ValidationError(f"Missing required observation file: {filename} in {staging_dir}")

            try:
                with open(fpath, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except Exception as e:
                raise ValidationError(f"Invalid JSON in observation file {filename}: {e}")

            # Validate runId if checking against a specific run
            obs_run_id = data.get("runId")
            if run_id and obs_run_id != run_id:
                raise ValidationError(f"Mismatched runId in {filename}: expected {run_id}, got {obs_run_id}")

            scenarios = data.get("scenarios")
            if not isinstance(scenarios, list):
                raise ValidationError(f"Field 'scenarios' must be a list in {filename}")

            found_cases = set()
            for s in scenarios:
                case_id = s.get("caseId")
                if not case_id:
                    raise ValidationError(f"Scenario missing 'caseId' in {filename}")
                if case_id in found_cases:
                    raise ValidationError(f"Duplicate caseId '{case_id}' in {filename}")
                found_cases.add(case_id)

                # Special validation for fetch observations (Prompt 14)
                if filename == 'fetch-observations.json':
                    self._validate_fetch_scenario(case_id, s)
                # Special validation for pagination observations (Prompt 15)
                elif filename == 'pagination-observations.json':
                    self._validate_pagination_scenario(case_id, s)
                # Special validation for query plan observations (Prompt 16)
                elif filename == 'query-plan-observations.json':
                    self._validate_query_plan_scenario(case_id, s)
                # Special validation for keyset pagination observations (Prompt 17)
                elif filename == 'keyset-pagination-observations.json':
                    self._validate_keyset_scenario(case_id, s)
                # Special validation for predicate index observations (Prompt 18)
                elif filename == 'predicate-index-observations.json':
                    self._validate_predicate_index_scenario(case_id, s)

            for exp in expected_cases:
                if exp not in found_cases:
                    raise ValidationError(f"Required caseId '{exp}' not found in {filename}")

            validated.append(filename)

        return validated

    def _validate_fetch_scenario(self, case_id, s):
        for req_field in ["datasetId", "strategy", "orderCount", "distinctBuyerCount",
                          "preparedStatementsAfterRootQuery", "preparedStatementsAfterMapping",
                          "sqlStatements", "resultRows", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in fetch-observations.json")

        order_count = s.get("orderCount")
        if not isinstance(order_count, int) or order_count <= 0:
            raise ValidationError(f"Scenario {case_id} orderCount must be positive integer")

        root_stmts = s.get("preparedStatementsAfterRootQuery")
        map_stmts = s.get("preparedStatementsAfterMapping")
        if not isinstance(root_stmts, int) or root_stmts < 0 or not isinstance(map_stmts, int) or map_stmts < 0:
            raise ValidationError(f"Scenario {case_id} statement counts must be non-negative integers")

        sql_stmts = s.get("sqlStatements")
        if not isinstance(sql_stmts, list) or len(sql_stmts) == 0:
            raise ValidationError(f"Scenario {case_id} sqlStatements must be a non-empty list")

        result_rows = s.get("resultRows")
        if not isinstance(result_rows, list) or len(result_rows) != order_count:
            raise ValidationError(f"Scenario {case_id} resultRows count ({len(result_rows) if isinstance(result_rows, list) else 'invalid'}) does not match orderCount ({order_count})")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

    def _validate_pagination_scenario(self, case_id, s):
        for req_field in ["strategy", "offset", "pageSize", "policyEnabled", "outcome",
                          "preparedStatementCount", "buyerEntityLoadCount", "orderEntityLoadCount",
                          "sqlStatements", "returnedBuyerIds", "resultRows", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in pagination-observations.json")

        outcome = s.get("outcome")
        if outcome not in ["RETURNED_PAGE", "EXPECTED_REJECTION"]:
            raise ValidationError(f"Scenario {case_id} outcome must be RETURNED_PAGE or EXPECTED_REJECTION, got '{outcome}'")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        # Counts must be non-negative integers (strictly not boolean)
        for count_field in ["preparedStatementCount", "buyerEntityLoadCount", "orderEntityLoadCount"]:
            val = s.get(count_field)
            if type(val) is not int or val < 0:
                raise ValidationError(f"Scenario {case_id} {count_field} must be a non-negative integer")

        if outcome == "EXPECTED_REJECTION":
            if not s.get("exceptionClass"):
                raise ValidationError(f"Scenario {case_id} EXPECTED_REJECTION must include exceptionClass")
        elif outcome == "RETURNED_PAGE":
            sql_stmts = s.get("sqlStatements")
            if not isinstance(sql_stmts, list) or len(sql_stmts) == 0:
                raise ValidationError(f"Scenario {case_id} sqlStatements must be a non-empty list for RETURNED_PAGE")

            returned_ids = s.get("returnedBuyerIds")
            result_rows = s.get("resultRows")
            if not isinstance(returned_ids, list) or not isinstance(result_rows, list):
                raise ValidationError(f"Scenario {case_id} returnedBuyerIds and resultRows must be lists")
            if len(returned_ids) != len(result_rows):
                raise ValidationError(f"Scenario {case_id} returnedBuyerIds count ({len(returned_ids)}) does not match resultRows count ({len(result_rows)})")

    def _validate_query_plan_scenario(self, case_id, s):
        for req_field in [
            "queryId", "sqlTemplate", "params", "actualIndexInventory",
            "resultSummary", "rawExplainAnalyzeJson", "nodeSummaries",
            "rootMetrics", "hypothesis", "hypothesisStatus", "assertionsPassed"
        ]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in query-plan-observations.json")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        hyp_status = s.get("hypothesisStatus")
        if hyp_status not in ["MATCHED", "DIFFERENT"]:
            raise ValidationError(f"Scenario {case_id} hypothesisStatus must be MATCHED or DIFFERENT, got '{hyp_status}'")

        res_summary = s.get("resultSummary")
        if not isinstance(res_summary, dict) or res_summary.get("validationPassed") is not True:
            raise ValidationError(f"Scenario {case_id} resultSummary must have validationPassed=True")

        raw_analyze = s.get("rawExplainAnalyzeJson")
        if not raw_analyze:
            raise ValidationError(f"Scenario {case_id} missing rawExplainAnalyzeJson")

        plan_obj = None
        if isinstance(raw_analyze, list) and len(raw_analyze) > 0 and isinstance(raw_analyze[0], dict):
            plan_obj = raw_analyze[0].get("Plan")
        elif isinstance(raw_analyze, dict):
            plan_obj = raw_analyze.get("Plan")

        if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
            raise ValidationError(f"Scenario {case_id} rawExplainAnalyzeJson must contain Plan with 'Node Type'")

        if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
            raise ValidationError(f"Scenario {case_id} rawExplainAnalyzeJson must contain actual execution metrics (both 'Actual Rows' and 'Actual Loops')")

        if case_id == "EP1":
            raw_only = s.get("rawExplainOnlyJson")
            if not raw_only:
                raise ValidationError(f"Scenario EP1 missing rawExplainOnlyJson")
            plan_only = None
            if isinstance(raw_only, list) and len(raw_only) > 0 and isinstance(raw_only[0], dict):
                plan_only = raw_only[0].get("Plan")
            elif isinstance(raw_only, dict):
                plan_only = raw_only.get("Plan")
            if not isinstance(plan_only, dict) or "Node Type" not in plan_only:
                raise ValidationError(f"Scenario EP1 rawExplainOnlyJson must contain Plan with 'Node Type'")

        node_summaries = s.get("nodeSummaries")
        if not isinstance(node_summaries, list) or len(node_summaries) == 0:
            raise ValidationError(f"Scenario {case_id} nodeSummaries must be a non-empty list")

    def _validate_keyset_scenario(self, case_id, s):
        for req_field in ["caseId", "pageSize", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in keyset-pagination-observations.json")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        if case_id == "KP1":
            if s.get("deepPagesEqual") is not True:
                raise ValidationError(f"Scenario KP1 deepPagesEqual must be True")

            measurements = s.get("measurements")
            if not isinstance(measurements, dict):
                raise ValidationError("Scenario KP1 measurements must be a dict")

            for m_key in ["first_page", "deep_offset", "deep_keyset"]:
                if m_key not in measurements:
                    raise ValidationError(f"Scenario KP1 missing measurement '{m_key}'")
                m = measurements[m_key]
                if not isinstance(m.get("returnedIds"), list) or len(m.get("returnedIds")) != 20:
                    raise ValidationError(f"Scenario KP1 measurement '{m_key}' must have 20 returnedIds")

                raw_plan = m.get("rawExplainAnalyzeJson")
                if not raw_plan:
                    raise ValidationError(f"Scenario KP1 measurement '{m_key}' missing rawExplainAnalyzeJson")

                plan_obj = None
                if isinstance(raw_plan, list) and len(raw_plan) > 0 and isinstance(raw_plan[0], dict):
                    plan_obj = raw_plan[0].get("Plan")
                elif isinstance(raw_plan, dict):
                    plan_obj = raw_plan.get("Plan")

                if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
                    raise ValidationError(f"Scenario KP1 measurement '{m_key}' rawExplainAnalyzeJson must contain Plan with 'Node Type'")

                if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
                    raise ValidationError(f"Scenario KP1 measurement '{m_key}' rawExplainAnalyzeJson must contain actual execution metrics (both 'Actual Rows' and 'Actual Loops')")

            ret_ids = s.get("returnedIds", {})
            first_page_ids = ret_ids.get("firstPage")
            deep_offset_ids = ret_ids.get("deepOffset")
            deep_keyset_ids = ret_ids.get("deepKeyset")
            expected_ids = s.get("expectedIds")

            if not deep_offset_ids or not deep_keyset_ids or deep_offset_ids != deep_keyset_ids:
                raise ValidationError("Scenario KP1 deepOffset IDs and deepKeyset IDs must be non-empty and strictly equal")

            if not expected_ids or expected_ids != deep_offset_ids:
                raise ValidationError("Scenario KP1 expectedIds must match deepOffset IDs")

            meas_first = measurements.get("first_page", {}).get("returnedIds")
            meas_offset = measurements.get("deep_offset", {}).get("returnedIds")
            meas_keyset = measurements.get("deep_keyset", {}).get("returnedIds")

            if meas_first != first_page_ids:
                raise ValidationError("Scenario KP1 measurement 'first_page' returnedIds mismatch with top-level returnedIds")
            if meas_offset != deep_offset_ids:
                raise ValidationError("Scenario KP1 measurement 'deep_offset' returnedIds mismatch with top-level returnedIds")
            if meas_keyset != deep_keyset_ids:
                raise ValidationError("Scenario KP1 measurement 'deep_keyset' returnedIds mismatch with top-level returnedIds")

            hyp_status = s.get("hypothesisStatus")
            if hyp_status not in ["MATCHED", "DIFFERENT"]:
                raise ValidationError(f"Scenario KP1 hypothesisStatus must be MATCHED or DIFFERENT, got '{hyp_status}'")

        elif case_id == "KP2":
            for field in ["page1Ids", "brokenPage2Ids", "correctPage2Ids", "fullTraversalIds", "fullTraversalPageSizes"]:
                if field not in s or not isinstance(s[field], list):
                    raise ValidationError(f"Scenario KP2 missing or invalid list field '{field}'")
            if len(s["page1Ids"]) != 7 or len(s["brokenPage2Ids"]) != 7 or len(s["correctPage2Ids"]) != 7 or len(s["fullTraversalIds"]) != 24:
                raise ValidationError("Scenario KP2 unexpected list lengths for page1, broken, correct, or fullTraversal")

        elif case_id == "KP3":
            for field in ["page1Ids", "offsetPage2Ids", "keysetPage2Ids", "offsetDuplicateId"]:
                if field not in s:
                    raise ValidationError(f"Scenario KP3 missing required field '{field}'")
            if s.get("keysetHasDuplicate") is not False:
                raise ValidationError("Scenario KP3 keysetHasDuplicate must be False")

        elif case_id == "KP4":
            for field in ["page1Ids", "offsetPage2Ids", "keysetPage2Ids", "offsetSkippedId"]:
                if field not in s:
                    raise ValidationError(f"Scenario KP4 missing required field '{field}'")
            if s.get("keysetHasSkipped") is not False:
                raise ValidationError("Scenario KP4 keysetHasSkipped must be False")


    def _validate_predicate_index_scenario(self, case_id, s):
        for req_field in ["caseId", "description", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in predicate-index-observations.json")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        if case_id == "IP1":
            measurements = s.get("measurements")
            if not isinstance(measurements, dict):
                raise ValidationError("Scenario IP1 missing 'measurements' map")
            for m_key in ["prefix_no_index", "prefix_btree"]:
                if m_key not in measurements:
                    raise ValidationError(f"Scenario IP1 missing required measurement '{m_key}'")
                m = measurements[m_key]
                for req in ["sql", "params", "returnedIds", "rawExplainAnalyzeJson", "report"]:
                    if req not in m:
                        raise ValidationError(f"Scenario IP1 measurement '{m_key}' missing required field '{req}'")

                raw_plan = m.get("rawExplainAnalyzeJson")
                plan_obj = None
                if isinstance(raw_plan, list) and len(raw_plan) > 0 and isinstance(raw_plan[0], dict):
                    plan_obj = raw_plan[0].get("Plan")
                elif isinstance(raw_plan, dict):
                    plan_obj = raw_plan.get("Plan")

                if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
                    raise ValidationError(f"Scenario IP1 measurement '{m_key}' rawExplainAnalyzeJson must contain Plan with 'Node Type'")

                if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
                    raise ValidationError(f"Scenario IP1 measurement '{m_key}' rawExplainAnalyzeJson must contain both 'Actual Rows' and 'Actual Loops'")

            ret_ids = s.get("returnedIds", {})
            p_no_idx = ret_ids.get("prefixNoIndex")
            p_btree = ret_ids.get("prefixBTree")
            exp_ids = s.get("expectedIds")

            if not isinstance(p_no_idx, list) or len(p_no_idx) != 100:
                raise ValidationError("Scenario IP1 prefixNoIndex IDs must have exactly 100 elements")
            if not isinstance(p_btree, list) or len(p_btree) != 100:
                raise ValidationError("Scenario IP1 prefixBTree IDs must have exactly 100 elements")
            if p_no_idx != p_btree:
                raise ValidationError("Scenario IP1 prefixNoIndex and prefixBTree IDs must be strictly equal")
            if not isinstance(exp_ids, list) or exp_ids != p_no_idx:
                raise ValidationError("Scenario IP1 expectedIds must match prefix returned IDs")

        elif case_id == "IP2":
            measurements = s.get("measurements")
            if not isinstance(measurements, dict) or "contains_like" not in measurements:
                raise ValidationError("Scenario IP2 missing measurement 'contains_like'")
            m = measurements["contains_like"]
            for req in ["sql", "params", "returnedIds", "rawExplainAnalyzeJson", "report"]:
                if req not in m:
                    raise ValidationError(f"Scenario IP2 measurement 'contains_like' missing required field '{req}'")

            raw_plan = m.get("rawExplainAnalyzeJson")
            plan_obj = None
            if isinstance(raw_plan, list) and len(raw_plan) > 0 and isinstance(raw_plan[0], dict):
                plan_obj = raw_plan[0].get("Plan")
            elif isinstance(raw_plan, dict):
                plan_obj = raw_plan.get("Plan")

            if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
                raise ValidationError("Scenario IP2 rawExplainAnalyzeJson must contain Plan with 'Node Type'")

            if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
                raise ValidationError("Scenario IP2 rawExplainAnalyzeJson must contain both 'Actual Rows' and 'Actual Loops'")

            ret_ids = s.get("returnedIds")
            exp_ids = s.get("expectedIds")
            if not isinstance(ret_ids, list) or len(ret_ids) != 200:
                raise ValidationError("Scenario IP2 returnedIds must have exactly 200 elements")
            if not isinstance(exp_ids, list) or exp_ids != ret_ids:
                raise ValidationError("Scenario IP2 expectedIds must match returnedIds")

        elif case_id == "IP3":
            measurements = s.get("measurements")
            if not isinstance(measurements, dict) or "lower_ordinary_index" not in measurements:
                raise ValidationError("Scenario IP3 missing measurement 'lower_ordinary_index'")
            m = measurements["lower_ordinary_index"]
            for req in ["sql", "params", "returnedIds", "rawExplainAnalyzeJson", "report"]:
                if req not in m:
                    raise ValidationError(f"Scenario IP3 measurement 'lower_ordinary_index' missing required field '{req}'")

            raw_plan = m.get("rawExplainAnalyzeJson")
            plan_obj = None
            if isinstance(raw_plan, list) and len(raw_plan) > 0 and isinstance(raw_plan[0], dict):
                plan_obj = raw_plan[0].get("Plan")
            elif isinstance(raw_plan, dict):
                plan_obj = raw_plan.get("Plan")

            if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
                raise ValidationError("Scenario IP3 rawExplainAnalyzeJson must contain Plan with 'Node Type'")

            if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
                raise ValidationError("Scenario IP3 rawExplainAnalyzeJson must contain both 'Actual Rows' and 'Actual Loops'")

            ret_ids = s.get("returnedIds")
            exp_ids = s.get("expectedIds")
            raw_eq_ids = s.get("rawEqualityReturnedIds")
            if not isinstance(ret_ids, list) or len(ret_ids) != 2:
                raise ValidationError("Scenario IP3 returnedIds must have exactly 2 elements")
            if not isinstance(exp_ids, list) or exp_ids != ret_ids:
                raise ValidationError("Scenario IP3 expectedIds must match returnedIds")
            if not isinstance(raw_eq_ids, list) or len(raw_eq_ids) != 0:
                raise ValidationError("Scenario IP3 rawEqualityReturnedIds must be empty (0 elements)")

        elif case_id == "IP4":
            measurements = s.get("measurements")
            if not isinstance(measurements, dict) or "lower_expression_index" not in measurements:
                raise ValidationError("Scenario IP4 missing measurement 'lower_expression_index'")
            m = measurements["lower_expression_index"]
            for req in ["sql", "params", "returnedIds", "rawExplainAnalyzeJson", "report"]:
                if req not in m:
                    raise ValidationError(f"Scenario IP4 measurement 'lower_expression_index' missing required field '{req}'")

            raw_plan = m.get("rawExplainAnalyzeJson")
            plan_obj = None
            if isinstance(raw_plan, list) and len(raw_plan) > 0 and isinstance(raw_plan[0], dict):
                plan_obj = raw_plan[0].get("Plan")
            elif isinstance(raw_plan, dict):
                plan_obj = raw_plan.get("Plan")

            if not isinstance(plan_obj, dict) or "Node Type" not in plan_obj:
                raise ValidationError("Scenario IP4 rawExplainAnalyzeJson must contain Plan with 'Node Type'")

            if "Actual Rows" not in plan_obj or "Actual Loops" not in plan_obj:
                raise ValidationError("Scenario IP4 rawExplainAnalyzeJson must contain both 'Actual Rows' and 'Actual Loops'")

            ret_ids = s.get("returnedIds")
            exp_ids = s.get("expectedIds")
            if not isinstance(ret_ids, list) or len(ret_ids) != 2:
                raise ValidationError("Scenario IP4 returnedIds must have exactly 2 elements")
            if not isinstance(exp_ids, list) or exp_ids != ret_ids:
                raise ValidationError("Scenario IP4 expectedIds must match returnedIds")

    def generate_summary_text(self, core_res, backend_res, meta):
        total_core = core_res['total_tests']
        core_failures = core_res['total_failures']
        core_errors = core_res['total_errors']
        core_skipped = core_res['total_skipped']

        total_backend = backend_res['total_tests']
        backend_failures = backend_res['total_failures']
        backend_errors = backend_res['total_errors']
        backend_skipped = backend_res['total_skipped']

        total_reactor = total_core + total_backend
        total_failures = core_failures + backend_failures
        total_errors = core_errors + backend_errors
        total_skipped = core_skipped + backend_skipped

        has_valid_exit = "exitCode" in meta and meta["exitCode"] is not None
        exit_code = meta.get("exitCode")

        if not has_valid_exit:
            reactor_status = "BUILD FAILURE (Exit Code: UNKNOWN/INVALID)"
        elif exit_code == 0 and total_failures == 0 and total_errors == 0 and total_skipped == 0:
            reactor_status = "BUILD SUCCESS"
        else:
            reactor_status = f"BUILD FAILURE (Exit Code: {exit_code})"

        is_all_pass = (
            has_valid_exit
            and exit_code == 0
            and total_reactor > 0
            and total_failures == 0
            and total_errors == 0
            and total_skipped == 0
        )
        reactor_verdict = "ALL PASS" if is_all_pass else "FAILURES DETECTED"

        summary = f"""================================================================================
AETHERCORE SUREFIRE TEST EXECUTION SUMMARY - {self.prompt.upper()}
================================================================================
Run ID: {meta.get('runId', 'N/A')}
Timestamp: {meta.get('timestamp', 'N/A')}
Git Base HEAD: {meta.get('gitHead', 'N/A')}
Working Tree State: {"DIRTY (uncommitted files present)" if meta.get('workingTreeDirty') else "CLEAN"}
Command: {meta.get('command', 'N/A')}
Reactor Result: {reactor_status}

Runtime Environment:
- Java: {meta.get('runtimeInfo', {}).get('java', 'NOT_CAPTURED')}
- Maven: {meta.get('runtimeInfo', {}).get('maven', 'NOT_CAPTURED')}
- Docker: {meta.get('runtimeInfo', {}).get('docker', 'NOT_CAPTURED')}
- Test Database: postgres:16-alpine (configured); runtime per-suite recorded in observations
- Frameworks (pom resolved): Spring Boot 3.3.4 / Spring 6.1.13 / Hibernate 6.5.3.Final (test-scope)

================================================================================
MODULE 1: core-lab (Pure Java Mechanics)
Total Test Classes: {len(core_res['classes'])}
Total Tests Run: {total_core} | Failures: {core_failures} | Errors: {core_errors} | Skipped: {core_skipped}
--------------------------------------------------------------------------------
"""
        for cname, d in sorted(core_res['classes'].items()):
            status_tag = f"[{d['status']}]"
            summary += f"- {cname}: {d['tests']} tests (elapsed: {d['time']}s) {status_tag}\n"

        summary += f"""
================================================================================
MODULE 2: backend-app (Spring Boot, Concurrency, JDBC, JPA, Propagation, Locking, Fetch, Pagination, Query Plan, Keyset Pagination)
Total Test Classes: {len(backend_res['classes'])}
Total Tests Run: {total_backend} | Failures: {backend_failures} | Errors: {backend_errors} | Skipped: {backend_skipped}
--------------------------------------------------------------------------------
"""
        for cname, d in sorted(backend_res['classes'].items()):
            status_tag = f"[{d['status']}]"
            summary += f"- {cname}: {d['tests']} tests (elapsed: {d['time']}s) {status_tag}\n"

        summary += f"""
================================================================================
REACTOR TOTAL: {total_reactor} Tests Run | {total_failures} Failures | {total_errors} Errors | {total_skipped} Skipped | {reactor_verdict}
================================================================================
"""
        return summary

    def run_suite_and_collect(self, run_id=None, maven_cmd_override=None):
        """
        Executes Maven clean test, validates outputs, and writes evidence bundle.
        """
        if run_id is None:
            ts_str = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
            run_id = f"{self.prompt}-{ts_str}-{uuid.uuid4().hex[:6]}"

        timestamp = datetime.datetime.now(datetime.timezone.utc).astimezone().isoformat()

        # Capture git status
        try:
            git_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.repo_root, text=True).strip()
            git_status = subprocess.check_output(['git', 'status', '--short'], cwd=self.repo_root, text=True).strip()
            git_dirty = bool(git_status)
        except Exception:
            git_head = "UNKNOWN"
            git_dirty = True

        runtime_info = self.get_runtime_info()

        staging_dir = os.path.join(self.repo_root, 'backend-app', 'target', 'lab-evidence', run_id)
        os.makedirs(staging_dir, exist_ok=True)

        if maven_cmd_override is not None:
            maven_cmd = maven_cmd_override
        else:
            mvn_bin = shutil.which('mvn') or ('mvn.cmd' if os.name == 'nt' else 'mvn')
            maven_cmd = [
                mvn_bin, 'clean', 'test',
                f'-Dlab.runId={run_id}',
                f'-Dlab.evidenceDir={os.path.abspath(staging_dir)}'
            ]

        # Use temporary directory outside target for live logs so maven clean doesn't erase it mid-flight
        temp_log_dir = tempfile.mkdtemp(prefix="aethercore-log-")
        temp_log_path = os.path.join(temp_log_dir, "maven-test-output.txt")

        print(f"[{datetime.datetime.now().isoformat()}] Running command: {' '.join(maven_cmd)}")
        print(f"Staging evidence in: {staging_dir}")
        print(f"Temporary log file: {temp_log_path}")

        exit_code = -1
        try:
            with open(temp_log_path, 'w', encoding='utf-8') as log_file:
                process = subprocess.Popen(
                    maven_cmd,
                    cwd=self.repo_root,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding='utf-8',
                    errors='replace'
                )
                try:
                    for line in process.stdout:
                        sys.stdout.write(line)
                        log_file.write(line)
                finally:
                    process.stdout.close()
                process.wait()
                exit_code = process.returncode
        except Exception as e:
            exit_code = -1
            print(f"Failed to execute Maven process: {e}", file=sys.stderr)

        # Re-create staging_dir after Maven clean finishes to ensure it exists
        os.makedirs(staging_dir, exist_ok=True)

        # Copy log into staging dir with .txt extension (not ignored by .gitignore *.log)
        staged_log_path = os.path.join(staging_dir, "maven-test-output.txt")
        if os.path.exists(temp_log_path):
            shutil.copyfile(temp_log_path, staged_log_path)
            shutil.rmtree(temp_log_dir, ignore_errors=True)

        print(f"Maven process exited with code: {exit_code}")

        # Meta record
        meta = {
            "runId": run_id,
            "prompt": self.prompt,
            "timestamp": timestamp,
            "gitHead": git_head,
            "workingTreeDirty": git_dirty,
            "command": " ".join(maven_cmd),
            "exitCode": exit_code,
            "runtimeInfo": runtime_info
        }

        # Check Maven exit code strictly
        if exit_code != 0:
            # Write failure manifest in staging
            meta["exitCode"] = exit_code
            meta["failureReason"] = f"Maven execution failed with exit code {exit_code}"
            fail_manifest = os.path.join(staging_dir, "manifest.json")
            with open(fail_manifest, "w", encoding="utf-8") as f:
                json.dump(meta, f, indent=2)
            raise ValidationError(f"Maven execution failed with exit code {exit_code}. Log preserved in: {staged_log_path}")

        # Parse test reports
        core_reports = os.path.join(self.repo_root, 'core-lab', 'target', 'surefire-reports')
        backend_reports = os.path.join(self.repo_root, 'backend-app', 'target', 'surefire-reports')

        core_res = self.parse_surefire_reports(core_reports)
        backend_res = self.parse_surefire_reports(backend_reports)

        # Validate observations in staging_dir
        validated_obs = self.validate_observations(staging_dir, run_id=run_id)
        meta["observationsValidated"] = validated_obs

        # Generate summary text
        summary_text = self.generate_summary_text(core_res, backend_res, meta)

        # Destination directory under docs/evidence/<prompt>
        dest_dir = os.path.join(self.repo_root, 'docs', 'evidence', self.prompt)
        os.makedirs(dest_dir, exist_ok=True)

        # Write manifest and summary in dest
        with open(os.path.join(dest_dir, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f, indent=2)

        with open(os.path.join(dest_dir, "test-summary.txt"), "w", encoding="utf-8") as f:
            f.write(summary_text)

        # Copy observations and log
        for obs_file in validated_obs:
            shutil.copyfile(os.path.join(staging_dir, obs_file), os.path.join(dest_dir, obs_file))

        shutil.copyfile(staged_log_path, os.path.join(dest_dir, "maven-test-output.txt"))

        print(f"Successfully promoted evidence bundle to: {dest_dir}")
        print("EVIDENCE COLLECTION VERIFIED: SUCCESS")
        return dest_dir

def main():
    parser = argparse.ArgumentParser(description="AetherCore Lab Evidence Collector")
    parser.add_argument("--run", action="store_true", help="Execute full Maven clean test before collection")
    parser.add_argument("--prompt", type=str, default="prompt15", help="Target prompt directory under docs/evidence (e.g. prompt15)")
    args = parser.parse_args()

    collector = EvidenceCollector(prompt=args.prompt)
    if args.run:
        collector.run_suite_and_collect()
    else:
        print("Use --run to execute full Maven clean test and collect evidence.")

if __name__ == '__main__':
    main()
