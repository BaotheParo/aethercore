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

def _is_strict_int(val):
    return type(val) is int and not isinstance(val, bool)

class EvidenceCollector:
    def __init__(self, repo_root=None, prompt="prompt21"):
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
                'predicate-index-observations.json': [f"IP{i}" for i in range(1, 5)],
                'idempotency-observations.json': [f"ID{i}" for i in range(1, 8)]
            }
            if self.prompt in ("prompt20", "prompt21", "prompt22", "prompt24") or os.path.exists(os.path.join(staging_dir, 'migration-observations.json')):
                required_sets['migration-observations.json'] = ["MG1", "MG2", "MG3"]
            if self.prompt in ("prompt21", "prompt22", "prompt24") or os.path.exists(os.path.join(staging_dir, 'nontransactional-index-observations.json')):
                required_sets['nontransactional-index-observations.json'] = ["NC1", "NC2", "NC3"]
            if self.prompt in ("prompt22", "prompt24") or os.path.exists(os.path.join(staging_dir, 'security-observations.json')):
                required_sets['security-observations.json'] = [f"SEC{i}" for i in range(1, 10)]
            if self.prompt == "prompt24" or os.path.exists(os.path.join(staging_dir, 'method-security-observations.json')):
                required_sets['method-security-observations.json'] = ["MS1", "MS2"]

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
                elif filename == 'idempotency-observations.json':
                    self._validate_idempotency_scenario(case_id, s)
                elif filename == 'migration-observations.json':
                    self._validate_migration_scenario(case_id, s)
                elif filename == 'nontransactional-index-observations.json':
                    self._validate_nontransactional_index_scenario(case_id, s)
                elif filename == 'security-observations.json':
                    self._validate_security_scenario(case_id, s)
                elif filename == 'method-security-observations.json':
                    self._validate_method_security_scenario(case_id, s)

            for exp in expected_cases:
                if exp not in found_cases:
                    raise ValidationError(f"Required caseId '{exp}' not found in {filename}")

            validated.append(filename)

        if self.prompt == "prompt24" or os.path.exists(os.path.join(staging_dir, 'method-security-proxy-observations.json')):
            proxy_obs_path = os.path.join(staging_dir, 'method-security-proxy-observations.json')
            if not os.path.exists(proxy_obs_path):
                raise ValidationError(f"Missing required observation file: method-security-proxy-observations.json in {staging_dir}")
            self._validate_method_security_proxy_observations(proxy_obs_path, run_id)
            validated.append('method-security-proxy-observations.json')

        return validated

    def _validate_snapshot_dict(self, var, snap, snap_type):
        if not isinstance(snap, dict):
            raise ValidationError(f"Subcase '{var}' {snap_type} snapshot must be a dict")
        for field in ["stockAvailable", "stockVersion", "purchaseCount", "purchaseSumQuantity", "idempotencyRowCount"]:
            if field not in snap:
                raise ValidationError(f"Subcase '{var}' {snap_type} snapshot missing field '{field}'")
            val = snap[field]
            if not _is_strict_int(val):
                raise ValidationError(f"Subcase '{var}' {snap_type} snapshot field '{field}' must be a strict integer, got {type(val).__name__} ({val})")

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

    def _validate_idempotency_scenario(self, case_id, s):
        for req_field in ["caseId", "description", "expected", "observed", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in idempotency-observations.json")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        obs = s.get("observed")
        if not isinstance(obs, dict):
            raise ValidationError(f"Scenario {case_id} observed must be a dict")

        if case_id == "ID1":
            for f in ["stock", "purchaseCount", "purchaseId1", "purchaseId2", "replayed1", "replayed2"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID1 missing observed field '{f}'")
            if not _is_strict_int(obs.get("stock")) or obs.get("stock") != 3:
                raise ValidationError("Scenario ID1 stock must be integer 3")
            if not _is_strict_int(obs.get("purchaseCount")) or obs.get("purchaseCount") != 1:
                raise ValidationError("Scenario ID1 purchaseCount must be integer 1")
            if obs.get("purchaseId1") != obs.get("purchaseId2") or not obs.get("purchaseId1"):
                raise ValidationError("Scenario ID1 purchaseId1 and purchaseId2 must match and not be empty")
            if obs.get("replayed1") is not False or obs.get("replayed2") is not True:
                raise ValidationError("Scenario ID1 replayed flags mismatch")
            if "storedState" in obs and obs.get("storedState") != "COMPLETED":
                raise ValidationError("Scenario ID1 storedState must be 'COMPLETED'")
            if "resultCode" in obs and obs.get("resultCode") != "SUCCESS":
                raise ValidationError("Scenario ID1 resultCode must be 'SUCCESS'")

        elif case_id == "ID2":
            for f in ["exQtyThrown", "exSkuThrown", "finalStock", "purchaseCount"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID2 missing observed field '{f}'")
            if not _is_strict_int(obs.get("finalStock")) or obs.get("finalStock") != 4:
                raise ValidationError("Scenario ID2 finalStock must be integer 4")
            if not _is_strict_int(obs.get("purchaseCount")) or obs.get("purchaseCount") != 1:
                raise ValidationError("Scenario ID2 purchaseCount must be integer 1")
            if obs.get("exQtyThrown") != "IdempotencyConflictException" or obs.get("exSkuThrown") != "IdempotencyConflictException":
                raise ValidationError("Scenario ID2 conflict exceptions must be IdempotencyConflictException")
            if "storedRowUnchanged" in obs and obs.get("storedRowUnchanged") is not True:
                raise ValidationError("Scenario ID2 storedRowUnchanged must be True")
            if "invalidKeyCount" in obs and (not _is_strict_int(obs.get("invalidKeyCount")) or obs.get("invalidKeyCount") != 0):
                raise ValidationError("Scenario ID2 invalidKeyCount must be integer 0")

        elif case_id == "ID3":
            for f in ["workerAPid", "workerBPid", "observedBlocking", "blockingPidList", "purchaseIdA", "purchaseIdB"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID3 missing observed field '{f}'")
            if not _is_strict_int(obs.get("workerAPid")) or obs.get("workerAPid") <= 0:
                raise ValidationError("Scenario ID3 workerAPid must be a positive integer")
            if not _is_strict_int(obs.get("workerBPid")) or obs.get("workerBPid") <= 0:
                raise ValidationError("Scenario ID3 workerBPid must be a positive integer")
            if obs.get("workerAPid") == obs.get("workerBPid"):
                raise ValidationError("Scenario ID3 workerAPid and workerBPid must be distinct")
            if obs.get("observedBlocking") is not True:
                raise ValidationError("Scenario ID3 observedBlocking must be True")
            b_list = obs.get("blockingPidList")
            if not isinstance(b_list, list) or len(b_list) == 0:
                raise ValidationError("Scenario ID3 blockingPidList must be a non-empty list")
            if obs.get("workerAPid") not in b_list:
                raise ValidationError("Scenario ID3 blockingPidList must contain workerAPid")
            if obs.get("purchaseIdA") != obs.get("purchaseIdB") or not obs.get("purchaseIdA"):
                raise ValidationError("Scenario ID3 purchaseIdA and purchaseIdB must match and not be empty")
            if "finalStock" in obs and (not _is_strict_int(obs.get("finalStock")) or obs.get("finalStock") != 3):
                raise ValidationError("Scenario ID3 finalStock must be integer 3")
            if "purchaseCount" in obs and (not _is_strict_int(obs.get("purchaseCount")) or obs.get("purchaseCount") != 1):
                raise ValidationError("Scenario ID3 purchaseCount must be integer 1")
            if "replayedA" in obs and obs.get("replayedA") is not False:
                raise ValidationError("Scenario ID3 replayedA must be False")
            if "replayedB" in obs and obs.get("replayedB") is not True:
                raise ValidationError("Scenario ID3 replayedB must be True")

        elif case_id == "ID4":
            for f in ["stock", "purchaseCount", "pId1", "pId2", "pId3"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID4 missing observed field '{f}'")
            if not _is_strict_int(obs.get("stock")) or obs.get("stock") != 7:
                raise ValidationError("Scenario ID4 stock must be integer 7")
            if not _is_strict_int(obs.get("purchaseCount")) or obs.get("purchaseCount") != 3:
                raise ValidationError("Scenario ID4 purchaseCount must be integer 3")
            pids = [obs.get("pId1"), obs.get("pId2"), obs.get("pId3")]
            if len(set(pids)) != 3 or None in pids:
                raise ValidationError("Scenario ID4 purchases must have 3 distinct non-null IDs")
            for rk in ["replayed1", "replayed2", "replayed3"]:
                if rk in obs and obs.get(rk) is not False:
                    raise ValidationError(f"Scenario ID4 {rk} must be False")

        elif case_id == "ID5":
            for f in ["observedSqlState", "stockAfterRollback", "stockAfterRetry"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID5 missing observed field '{f}'")
            if obs.get("observedSqlState") != "23514":
                raise ValidationError(f"Scenario ID5 observedSqlState must be '23514', got '{obs.get('observedSqlState')}'")
            if not _is_strict_int(obs.get("stockAfterRollback")) or obs.get("stockAfterRollback") != 5:
                raise ValidationError("Scenario ID5 stockAfterRollback must be integer 5")
            if not _is_strict_int(obs.get("stockAfterRetry")) or obs.get("stockAfterRetry") != 3:
                raise ValidationError("Scenario ID5 stockAfterRetry must be integer 3")
            if "purchaseCountAfterRollback" in obs:
                if not _is_strict_int(obs.get("purchaseCountAfterRollback")) or obs.get("purchaseCountAfterRollback") != 0:
                    raise ValidationError("Scenario ID5 purchaseCountAfterRollback must be integer 0")
            if "idempotencyCountAfterRollback" in obs:
                if not _is_strict_int(obs.get("idempotencyCountAfterRollback")) or obs.get("idempotencyCountAfterRollback") != 0:
                    raise ValidationError("Scenario ID5 idempotencyCountAfterRollback must be integer 0")
            if "purchaseCountAfterRetry" in obs:
                if not _is_strict_int(obs.get("purchaseCountAfterRetry")) or obs.get("purchaseCountAfterRetry") != 1:
                    raise ValidationError("Scenario ID5 purchaseCountAfterRetry must be integer 1")
            if "idempotencyCountAfterRetry" in obs:
                if not _is_strict_int(obs.get("idempotencyCountAfterRetry")) or obs.get("idempotencyCountAfterRetry") != 1:
                    raise ValidationError("Scenario ID5 idempotencyCountAfterRetry must be integer 1")

        elif case_id == "ID6":
            for f in ["replayResult", "replayFlag", "newKeyResult", "finalStock", "totalSold"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID6 missing observed field '{f}'")
            if obs.get("replayResult") != "NOT_AVAILABLE" or obs.get("newKeyResult") != "SUCCESS":
                raise ValidationError("Scenario ID6 outcomes mismatch")
            if obs.get("replayFlag") is not True:
                raise ValidationError("Scenario ID6 replayFlag must be True")
            if not _is_strict_int(obs.get("finalStock")) or not _is_strict_int(obs.get("totalSold")):
                raise ValidationError("Scenario ID6 finalStock and totalSold must be integers")
            if obs.get("finalStock") + obs.get("totalSold") != 5:
                raise ValidationError("Scenario ID6 stock conservation violated")
            if obs.get("finalStock") != 3 or obs.get("totalSold") != 2:
                raise ValidationError("Scenario ID6 finalStock must be 3 and totalSold must be 2")
            if "idempotencyCount" in obs and (not _is_strict_int(obs.get("idempotencyCount")) or obs.get("idempotencyCount") != 2):
                raise ValidationError("Scenario ID6 idempotencyCount must be integer 2")

        elif case_id == "ID7":
            for f in ["stockAfterRollback", "stockAfterRetry", "purchaseCountAfterRetry"]:
                if f not in obs:
                    raise ValidationError(f"Scenario ID7 missing observed field '{f}'")
            if not _is_strict_int(obs.get("stockAfterRollback")) or obs.get("stockAfterRollback") != 5:
                raise ValidationError("Scenario ID7 stockAfterRollback must be integer 5")
            if not _is_strict_int(obs.get("stockAfterRetry")) or obs.get("stockAfterRetry") != 3:
                raise ValidationError("Scenario ID7 stockAfterRetry must be integer 3")
            if not _is_strict_int(obs.get("purchaseCountAfterRetry")) or obs.get("purchaseCountAfterRetry") != 1:
                raise ValidationError("Scenario ID7 purchaseCountAfterRetry must be integer 1")
            if "purchaseCountAfterRollback" in obs:
                if not _is_strict_int(obs.get("purchaseCountAfterRollback")) or obs.get("purchaseCountAfterRollback") != 0:
                    raise ValidationError("Scenario ID7 purchaseCountAfterRollback must be integer 0")
            if "idempotencyCountAfterRollback" in obs:
                if not _is_strict_int(obs.get("idempotencyCountAfterRollback")) or obs.get("idempotencyCountAfterRollback") != 0:
                    raise ValidationError("Scenario ID7 idempotencyCountAfterRollback must be integer 0")
            if "idempotencyCountAfterRetry" in obs:
                if not _is_strict_int(obs.get("idempotencyCountAfterRetry")) or obs.get("idempotencyCountAfterRetry") != 1:
                    raise ValidationError("Scenario ID7 idempotencyCountAfterRetry must be integer 1")


    def _validate_nontransactional_index_scenario(self, case_id, s):
        for req_field in ["caseId", "description", "expected", "observed", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in nontransactional-index-observations.json")
        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        obs = s.get("observed")
        if not isinstance(obs, dict):
            raise ValidationError(f"Scenario {case_id} observed must be a dict")

        if case_id == "NC1":
            for f in ["sqlState", "errorMessage", "autoCommitMode", "indexCatalogAfterRollback", "indexExistsAfterRollback", "rowsAfterRollback", "rowsPreserved"]:
                if f not in obs:
                    raise ValidationError(f"Scenario NC1 missing observed field '{f}'")
            if obs.get("sqlState") != "25001":
                raise ValidationError(f"Scenario NC1 sqlState must be '25001', got '{obs.get('sqlState')}'")
            if obs.get("autoCommitMode") is not False:
                raise ValidationError("Scenario NC1 autoCommitMode must be False")
            if obs.get("indexExistsAfterRollback") is not False:
                raise ValidationError("Scenario NC1 indexExistsAfterRollback must be False")
            cat = obs.get("indexCatalogAfterRollback")
            if not isinstance(cat, dict) or cat.get("exists") is not False:
                raise ValidationError("Scenario NC1 indexCatalogAfterRollback.exists must be False")
            rows = obs.get("rowsAfterRollback")
            if not isinstance(rows, list) or len(rows) != 3:
                raise ValidationError("Scenario NC1 rowsAfterRollback must contain exactly 3 rows")
            if obs.get("rowsPreserved") is not True:
                raise ValidationError("Scenario NC1 rowsPreserved must be True")

        elif case_id == "NC2":
            for f in ["migrationFailureSqlState", "indexCatalog", "indexExists", "indisunique", "indisvalid",
                      "historyRows", "v2HistorySuccess", "rowsAfter", "rowsPreserved", "duplicateGroups", "validationFailed"]:
                if f not in obs:
                    raise ValidationError(f"Scenario NC2 missing observed field '{f}'")
            if obs.get("migrationFailureSqlState") != "23505":
                raise ValidationError(f"Scenario NC2 migrationFailureSqlState must be '23505', got '{obs.get('migrationFailureSqlState')}'")
            if obs.get("indexExists") is not True:
                raise ValidationError("Scenario NC2 indexExists must be True")
            if obs.get("indisunique") is not True:
                raise ValidationError("Scenario NC2 indisunique must be True")
            if obs.get("indisvalid") is not False:
                raise ValidationError("Scenario NC2 indisvalid must be False (INVALID index)")
            if obs.get("v2HistorySuccess") is not False:
                raise ValidationError("Scenario NC2 v2HistorySuccess must be False")
            cat = obs.get("indexCatalog")
            if not isinstance(cat, dict) or cat.get("indisvalid") is not False or cat.get("exists") is not True:
                raise ValidationError("Scenario NC2 indexCatalog must record existing INVALID index")
            rows = obs.get("rowsAfter")
            if not isinstance(rows, list) or len(rows) != 3:
                raise ValidationError("Scenario NC2 rowsAfter must contain exactly 3 rows")
            dups = obs.get("duplicateGroups")
            if not isinstance(dups, list) or len(dups) == 0:
                raise ValidationError("Scenario NC2 duplicateGroups must not be empty")
            if obs.get("validationFailed") is not True:
                raise ValidationError("Scenario NC2 validationFailed must be True")

        elif case_id == "NC3":
            for f in ["fileHashesUnchanged", "checkpoint1_beforeRepair", "checkpoint2_afterRepair",
                      "checkpoint3_afterCleanup", "checkpoint4_afterRetry"]:
                if f not in obs:
                    raise ValidationError(f"Scenario NC3 missing observed field '{f}'")
            if obs.get("fileHashesUnchanged") is not True:
                raise ValidationError("Scenario NC3 fileHashesUnchanged must be True")

            cp1 = obs.get("checkpoint1_beforeRepair")
            cp2 = obs.get("checkpoint2_afterRepair")
            cp3 = obs.get("checkpoint3_afterCleanup")
            cp4 = obs.get("checkpoint4_afterRetry")
            for name, cp in [("checkpoint1", cp1), ("checkpoint2", cp2), ("checkpoint3", cp3), ("checkpoint4", cp4)]:
                if not isinstance(cp, dict):
                    raise ValidationError(f"Scenario NC3 {name} must be a dict")

            # Checkpoint 1: before repair
            if cp1.get("indexCatalog", {}).get("indisvalid") is not False:
                raise ValidationError("Scenario NC3 checkpoint1 index must be INVALID")
            h1 = cp1.get("history", [])
            if len(h1) != 2 or h1[1].get("success") is not False:
                raise ValidationError("Scenario NC3 checkpoint1 history must contain failed V2")

            # Checkpoint 2: after repair
            if cp2.get("indexCatalog", {}).get("indisvalid") is not False:
                raise ValidationError("Scenario NC3 checkpoint2 index must remain INVALID after repair")
            if cp2.get("indexCatalog", {}).get("exists") is not True:
                raise ValidationError("Scenario NC3 checkpoint2 index must still exist after repair")
            h2 = cp2.get("history", [])
            if len(h2) != 1 or h2[0].get("version") != "1" or h2[0].get("success") is not True:
                raise ValidationError("Scenario NC3 checkpoint2 history must only contain successful V1 (failed V2 removed)")
            if len(cp2.get("duplicateGroups", [])) == 0:
                raise ValidationError("Scenario NC3 checkpoint2 duplicate rows must not be altered by repair")

            # Checkpoint 3: after cleanup
            if cp3.get("indexCatalog", {}).get("exists") is not False:
                raise ValidationError("Scenario NC3 checkpoint3 index must be dropped")
            if len(cp3.get("duplicateGroups", [])) != 0:
                raise ValidationError("Scenario NC3 checkpoint3 duplicate groups must be empty after data cleanup")

            # Checkpoint 4: after retry
            if cp4.get("retrySuccess") is not True:
                raise ValidationError("Scenario NC3 checkpoint4 retrySuccess must be True")
            cat4 = cp4.get("indexCatalog", {})
            if cat4.get("exists") is not True or cat4.get("indisvalid") is not True or cat4.get("indisunique") is not True:
                raise ValidationError("Scenario NC3 checkpoint4 final index must exist, be valid, and be unique")
            h4 = cp4.get("history", [])
            if len(h4) != 2 or h4[1].get("success") is not True or h4[1].get("version") != "2":
                raise ValidationError("Scenario NC3 checkpoint4 history must contain successful V1 and V2")
            if cp4.get("duplicateInsertSqlState") != "23505":
                raise ValidationError(f"Scenario NC3 checkpoint4 duplicate insert must be rejected with SQLSTATE 23505, got '{cp4.get('duplicateInsertSqlState')}'")
            if not _is_strict_int(cp4.get("repeatMigrationCount")) or cp4.get("repeatMigrationCount") != 0:
                raise ValidationError("Scenario NC3 checkpoint4 repeatMigrationCount must be integer 0")
            if cp4.get("validationPassed") is not True:
                raise ValidationError("Scenario NC3 checkpoint4 validationPassed must be True")

    def _validate_security_scenario(self, case_id, s):
        subcases = s.get("subcases")
        if not isinstance(subcases, list) or len(subcases) == 0:
            raise ValidationError(f"Scenario {case_id} must contain a non-empty list of 'subcases'")

        subcase_count = s.get("subcaseCount")
        if not _is_strict_int(subcase_count) or subcase_count != len(subcases):
            raise ValidationError(f"Scenario {case_id} subcaseCount must match length of subcases list")

        subcases_by_variant = {}
        for sc in subcases:
            var = sc.get("variant")
            if not var:
                raise ValidationError(f"Scenario {case_id} subcase missing 'variant' field")
            if var in subcases_by_variant:
                raise ValidationError(f"Scenario {case_id} contains duplicate variant '{var}'")
            subcases_by_variant[var] = sc
            obs_st = sc.get("observedStatus")
            exp_st = sc.get("expectedStatus")
            if not _is_strict_int(obs_st) or not _is_strict_int(exp_st):
                raise ValidationError(f"Scenario {case_id} subcase '{var}' statuses must be integers")
            if obs_st != exp_st:
                raise ValidationError(f"Scenario {case_id} subcase '{var}' observedStatus ({obs_st}) != expectedStatus ({exp_st})")

            before = sc.get("before")
            after = sc.get("after")
            state_unchanged = sc.get("stateUnchanged")

            if before is not None and after is not None:
                self._validate_snapshot_dict(var, before, "before")
                self._validate_snapshot_dict(var, after, "after")
                if state_unchanged is True:
                    for fld in ["stockAvailable", "stockVersion", "purchaseCount", "purchaseSumQuantity", "idempotencyRowCount"]:
                        if before[fld] != after[fld]:
                            raise ValidationError(f"Scenario {case_id} subcase '{var}' claims stateUnchanged=True but raw snapshots differ on '{fld}': before={before[fld]}, after={after[fld]}")
                elif state_unchanged is False:
                    diffs = [fld for fld in ["stockAvailable", "stockVersion", "purchaseCount", "purchaseSumQuantity", "idempotencyRowCount"] if before[fld] != after[fld]]
                    if len(diffs) == 0:
                        raise ValidationError(f"Scenario {case_id} subcase '{var}' claims stateUnchanged=False but snapshots are identical")
            elif before is not None or after is not None:
                raise ValidationError(f"Scenario {case_id} subcase '{var}' snapshot before/after is incomplete")

        if case_id == "SEC1":
            for exp_var in ["missing_token", "malformed_token", "wrong_key_b"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC1 missing required subcase '{exp_var}'")
                sc = subcases_by_variant[exp_var]
                if sc.get("observedStatus") != 401:
                    raise ValidationError(f"Scenario SEC1 subcase '{exp_var}' must return HTTP 401")
                if not sc.get("bearerChallengePresent"):
                    raise ValidationError(f"Scenario SEC1 subcase '{exp_var}' must return Bearer challenge")
                if sc.get("stateUnchanged") is not True:
                    raise ValidationError(f"Scenario SEC1 subcase '{exp_var}' stateUnchanged must be True (no DB writes on rejection)")

        elif case_id == "SEC2":
            req_vars = [
                "wrong_issuer", "missing_audience", "wrong_audience", "expired",
                "future_nbf", "missing_exp", "missing_sub", "invalid_sub_non_uuid",
                "roles_as_integer", "roles_as_string"
            ]
            for exp_var in req_vars:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC2 missing required claim variant '{exp_var}'")
                sc = subcases_by_variant[exp_var]
                if sc.get("observedStatus") != 401:
                    raise ValidationError(f"Scenario SEC2 variant '{exp_var}' must return HTTP 401")
                if sc.get("stateUnchanged") is not True:
                    raise ValidationError(f"Scenario SEC2 variant '{exp_var}' stateUnchanged must be True (no DB writes)")

        elif case_id == "SEC3":
            for exp_var in ["empty_roles", "viewer_role_only", "trader_role_success"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC3 missing required subcase '{exp_var}'")
            if subcases_by_variant["empty_roles"].get("observedStatus") != 403:
                raise ValidationError("Scenario SEC3 empty_roles must return HTTP 403")
            if subcases_by_variant["viewer_role_only"].get("observedStatus") != 403:
                raise ValidationError("Scenario SEC3 viewer_role_only must return HTTP 403")
            if subcases_by_variant["trader_role_success"].get("observedStatus") != 200:
                raise ValidationError("Scenario SEC3 trader_role_success must return HTTP 200")

        elif case_id == "SEC4":
            for exp_var in ["fresh_purchase", "replay_exact", "conflict_reuse"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC4 missing required subcase '{exp_var}'")
            fresh_body = subcases_by_variant["fresh_purchase"].get("responseBody", "")
            replay_body = subcases_by_variant["replay_exact"].get("responseBody", "")
            try:
                import json as j
                fresh_pid = j.loads(fresh_body).get("purchaseId")
                replay_pid = j.loads(replay_body).get("purchaseId")
                if not fresh_pid or fresh_pid != replay_pid:
                    raise ValidationError("Scenario SEC4 replay_exact purchaseId must match fresh_purchase")
            except Exception as e:
                if not isinstance(e, ValidationError):
                    raise ValidationError(f"Scenario SEC4 failed to parse purchaseId: {e}")
                raise
            conflict_body = subcases_by_variant["conflict_reuse"].get("responseBody", "")
            if "IDEMPOTENCY_CONFLICT" not in conflict_body:
                raise ValidationError("Scenario SEC4 conflict_reuse must return IDEMPOTENCY_CONFLICT")

        elif case_id == "SEC5":
            for exp_var in ["caller_b_read_foreign_key", "caller_a_read_own_key", "read_unknown_key"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC5 missing required subcase '{exp_var}'")
            if subcases_by_variant["caller_b_read_foreign_key"].get("observedStatus") != 404:
                raise ValidationError("Scenario SEC5 caller_b_read_foreign_key must return HTTP 404 (foreign lookup rejected)")
            if subcases_by_variant["caller_a_read_own_key"].get("observedStatus") != 200:
                raise ValidationError("Scenario SEC5 caller_a_read_own_key must return HTTP 200")
            for var in ["caller_b_read_foreign_key", "caller_a_read_own_key", "read_unknown_key"]:
                if subcases_by_variant[var].get("stateUnchanged") is not True:
                    raise ValidationError(f"Scenario SEC5 subcase '{var}' must not mutate database")

        elif case_id == "SEC6":
            for exp_var in ["caller_a_purchase_key_k", "caller_b_purchase_same_key_k", "caller_a_read_k", "caller_b_read_k"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC6 missing required subcase '{exp_var}'")
            try:
                import json as j
                pid_a = j.loads(subcases_by_variant["caller_a_purchase_key_k"].get("responseBody", "")).get("purchaseId")
                pid_b = j.loads(subcases_by_variant["caller_b_purchase_same_key_k"].get("responseBody", "")).get("purchaseId")
                if not pid_a or not pid_b or pid_a == pid_b:
                    raise ValidationError("Scenario SEC6 callers A and B must receive distinct purchaseIds (no cross-caller replay)")
                read_pid_a = j.loads(subcases_by_variant["caller_a_read_k"].get("responseBody", "")).get("purchaseId")
                read_pid_b = j.loads(subcases_by_variant["caller_b_read_k"].get("responseBody", "")).get("purchaseId")
                if read_pid_a != pid_a:
                    raise ValidationError("Scenario SEC6 caller_a_read_k must return caller A's purchaseId")
                if read_pid_b != pid_b:
                    raise ValidationError("Scenario SEC6 caller_b_read_k must return caller B's purchaseId")
            except Exception as e:
                if not isinstance(e, ValidationError):
                    raise ValidationError(f"Scenario SEC6 JSON validation error: {e}")
                raise

        elif case_id == "SEC7":
            for exp_var in ["spoof_via_header_ignored", "spoof_via_query_ignored", "spoof_via_body_rejected_400"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC7 missing required subcase '{exp_var}'")
            if subcases_by_variant["spoof_via_body_rejected_400"].get("observedStatus") != 400:
                raise ValidationError("Scenario SEC7 spoof_via_body_rejected_400 must return HTTP 400")

        elif case_id == "SEC8":
            if "quantity_decimal_coercion_rejected" not in subcases_by_variant:
                raise ValidationError("Scenario SEC8 missing required subcase 'quantity_decimal_coercion_rejected'")
            if subcases_by_variant["quantity_decimal_coercion_rejected"].get("observedStatus") != 400:
                raise ValidationError("Scenario SEC8 quantity_decimal_coercion_rejected must return HTTP 400")
            if "not_available_replayed_after_restock" not in subcases_by_variant:
                raise ValidationError("Scenario SEC8 missing required subcase 'not_available_replayed_after_restock'")
            not_avail = subcases_by_variant["not_available_replayed_after_restock"]
            if not_avail.get("observedStatus") != 200:
                raise ValidationError("Scenario SEC8 not_available_replayed_after_restock must return HTTP 200")
            if "NOT_AVAILABLE" not in not_avail.get("responseBody", ""):
                raise ValidationError("Scenario SEC8 not_available_replayed_after_restock response must be NOT_AVAILABLE")

        elif case_id == "SEC9":
            for exp_var in ["stateless_request_with_token", "stateless_request_without_token_rejected_401", "public_get_lab_entry_200"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario SEC9 missing required subcase '{exp_var}'")
            if subcases_by_variant["stateless_request_without_token_rejected_401"].get("observedStatus") != 401:
                raise ValidationError("Scenario SEC9 request without token must return HTTP 401 (no session retention)")
            if subcases_by_variant["public_get_lab_entry_200"].get("observedStatus") != 200:
                raise ValidationError("Scenario SEC9 public_get_lab_entry_200 must return HTTP 200")

    def _validate_migration_scenario(self, case_id, s):
        for req_field in ["caseId", "description", "expected", "observed", "assertionsPassed"]:
            if req_field not in s:
                raise ValidationError(f"Scenario {case_id} missing required field '{req_field}' in migration-observations.json")

        if s.get("assertionsPassed") is not True:
            raise ValidationError(f"Scenario {case_id} assertionsPassed must be True")

        obs = s.get("observed")
        if not isinstance(obs, dict):
            raise ValidationError(f"Scenario {case_id} observed must be a dict")

        if case_id == "MG1":
            for f in ["v5MalformedAccepted", "validDataPreserved", "v6Success", "v6Validated",
                      "repeatMigrationCount", "historyCount", "v5ConstraintDef", "v6ConstraintDef"]:
                if f not in obs:
                    raise ValidationError(f"Scenario MG1 missing observed field '{f}'")
            if obs.get("v5MalformedAccepted") is not True:
                raise ValidationError("Scenario MG1 v5MalformedAccepted must be True")
            if obs.get("validDataPreserved") is not True:
                raise ValidationError("Scenario MG1 validDataPreserved must be True")
            if obs.get("v6Success") is not True:
                raise ValidationError("Scenario MG1 v6Success must be True")
            if obs.get("v6Validated") is not True:
                raise ValidationError("Scenario MG1 v6Validated must be True")
            if not _is_strict_int(obs.get("repeatMigrationCount")) or obs.get("repeatMigrationCount") != 0:
                raise ValidationError("Scenario MG1 repeatMigrationCount must be integer 0")
            if not _is_strict_int(obs.get("historyCount")) or obs.get("historyCount") != 6:
                raise ValidationError("Scenario MG1 historyCount must be integer 6")
            if "result_code IS NOT NULL" not in obs.get("v6ConstraintDef", ""):
                raise ValidationError("Scenario MG1 v6ConstraintDef must contain 'result_code IS NOT NULL'")

        elif case_id == "MG2":
            for f in ["truthTable", "updateRejected", "updateSqlState", "rowIntactAfterUpdateFailure"]:
                if f not in obs:
                    raise ValidationError(f"Scenario MG2 missing observed field '{f}'")
            if obs.get("updateRejected") is not True:
                raise ValidationError("Scenario MG2 updateRejected must be True")
            if obs.get("updateSqlState") != "23514":
                raise ValidationError(f"Scenario MG2 updateSqlState must be '23514', got '{obs.get('updateSqlState')}'")
            if obs.get("rowIntactAfterUpdateFailure") is not True:
                raise ValidationError("Scenario MG2 rowIntactAfterUpdateFailure must be True")
            tt = obs.get("truthTable")
            if not isinstance(tt, list) or len(tt) != 8:
                raise ValidationError("Scenario MG2 truthTable must contain exactly 8 entries")
            seen_variants = set()
            for row in tt:
                var = row.get("inputVariant")
                if not var:
                    var = f"{row.get('state')}|{row.get('resultCode')}|{row.get('withPurchase')}"
                if var in seen_variants:
                    raise ValidationError(f"Scenario MG2 truthTable has duplicate variant: {var}")
                seen_variants.add(var)
                if row.get("expected") != row.get("actual"):
                    raise ValidationError(f"Scenario MG2 truthTable mismatch: expected {row.get('expected')}, actual {row.get('actual')}")
                if row.get("state") == "COMPLETED" and row.get("resultCode") is None:
                    if row.get("actual") != "REJECT" or row.get("sqlState") != "23514":
                        raise ValidationError("Scenario MG2 COMPLETED/null must be rejected with SQLSTATE 23514")
            if len(seen_variants) != 8:
                raise ValidationError(f"Scenario MG2 truthTable must contain 8 unique variants, found {len(seen_variants)}")

        elif case_id == "MG3":
            for f in ["migrationFailureSqlState", "oldConstraintRestored", "v6HistoryCountAfterFailure",
                      "v6FileHash", "v6FileHashUnchanged", "retrySuccess", "finalHistoryCount"]:
                if f not in obs:
                    raise ValidationError(f"Scenario MG3 missing observed field '{f}'")
            if obs.get("migrationFailureSqlState") != "23514":
                raise ValidationError(f"Scenario MG3 migrationFailureSqlState must be '23514', got '{obs.get('migrationFailureSqlState')}'")
            if obs.get("oldConstraintRestored") is not True:
                raise ValidationError("Scenario MG3 oldConstraintRestored must be True")
            if not _is_strict_int(obs.get("v6HistoryCountAfterFailure")) or obs.get("v6HistoryCountAfterFailure") != 0:
                raise ValidationError("Scenario MG3 v6HistoryCountAfterFailure must be integer 0")
            if obs.get("v6FileHashUnchanged") is not True:
                raise ValidationError("Scenario MG3 v6FileHashUnchanged must be True")
            if obs.get("retrySuccess") is not True:
                raise ValidationError("Scenario MG3 retrySuccess must be True")
            if not _is_strict_int(obs.get("finalHistoryCount")) or obs.get("finalHistoryCount") != 6:
                raise ValidationError("Scenario MG3 finalHistoryCount must be integer 6")

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


    def _validate_method_security_scenario(self, case_id, s):
        subcases = s.get("subcases")
        if not isinstance(subcases, list) or len(subcases) == 0:
            raise ValidationError(f"Scenario {case_id} must contain a non-empty list of 'subcases'")

        subcase_count = s.get("subcaseCount")
        if not _is_strict_int(subcase_count) or subcase_count != len(subcases):
            raise ValidationError(f"Scenario {case_id} subcaseCount must match length of subcases list")

        subcases_by_variant = {}
        for sc in subcases:
            var = sc.get("variant")
            if not var:
                raise ValidationError(f"Scenario {case_id} subcase missing 'variant' field")
            if var in subcases_by_variant:
                raise ValidationError(f"Scenario {case_id} contains duplicate variant '{var}'")
            subcases_by_variant[var] = sc

            before = sc.get("before")
            after = sc.get("after")
            state_unchanged = sc.get("stateUnchanged")

            if before is None or after is None:
                raise ValidationError(f"Scenario {case_id} subcase '{var}' requires both before and after snapshots")
            self._validate_snapshot_dict(var, before, "before")
            self._validate_snapshot_dict(var, after, "after")

            if state_unchanged is True:
                for fld in ["stockAvailable", "stockVersion", "purchaseCount", "purchaseSumQuantity", "idempotencyRowCount"]:
                    if before[fld] != after[fld]:
                        raise ValidationError(f"Scenario {case_id} subcase '{var}' claims stateUnchanged=True but raw snapshots differ on '{fld}': before={before[fld]}, after={after[fld]}")
            elif state_unchanged is False:
                diffs = [fld for fld in ["stockAvailable", "stockVersion", "purchaseCount", "purchaseSumQuantity", "idempotencyRowCount"] if before[fld] != after[fld]]
                if len(diffs) == 0:
                    raise ValidationError(f"Scenario {case_id} subcase '{var}' claims stateUnchanged=False but snapshots are identical")

        if case_id == "MS1":
            for exp_var in ["direct_call_unauthenticated_denied", "direct_call_viewer_role_access_denied", "direct_call_trader_role_authorized_success"]:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario MS1 missing required subcase '{exp_var}'")
            unauth = subcases_by_variant["direct_call_unauthenticated_denied"]
            if unauth.get("stateUnchanged") is not True:
                raise ValidationError("Scenario MS1 unauthenticated call must have stateUnchanged=True")
            viewer = subcases_by_variant["direct_call_viewer_role_access_denied"]
            if viewer.get("stateUnchanged") is not True:
                raise ValidationError("Scenario MS1 viewer call must have stateUnchanged=True")
            trader = subcases_by_variant["direct_call_trader_role_authorized_success"]
            if trader.get("stateUnchanged") is not False:
                raise ValidationError("Scenario MS1 trader call must have stateUnchanged=False")
            if trader.get("outcome") != "SUCCESS":
                raise ValidationError("Scenario MS1 trader call outcome must be SUCCESS")

        elif case_id == "MS2":
            req_vars = [
                "caller_a_purchase_key_k",
                "caller_b_query_key_k_before_purchase_empty",
                "caller_b_purchase_same_key_k_distinct_id",
                "isolated_reads_for_both_callers_same_key",
                "caller_a_replay_same_key_cached_outcome"
            ]
            for exp_var in req_vars:
                if exp_var not in subcases_by_variant:
                    raise ValidationError(f"Scenario MS2 missing required subcase '{exp_var}'")
            sc_a = subcases_by_variant["caller_a_purchase_key_k"]
            sc_b = subcases_by_variant["caller_b_purchase_same_key_k_distinct_id"]
            if not sc_a.get("purchaseId") or not sc_b.get("purchaseId"):
                raise ValidationError("Scenario MS2 purchases must contain non-null purchaseId")
            if sc_a.get("purchaseId") == sc_b.get("purchaseId"):
                raise ValidationError("Scenario MS2 callers A and B must receive distinct purchaseIds for same key")

    def _validate_method_security_proxy_observations(self, fpath, run_id):
        try:
            with open(fpath, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception as e:
            raise ValidationError(f"Invalid JSON in method-security-proxy-observations.json: {e}")

        obs_run_id = data.get("runId")
        if run_id and obs_run_id != run_id:
            raise ValidationError(f"Mismatched runId in method-security-proxy-observations.json: expected {run_id}, got {obs_run_id}")

        if data.get("proxyMechanism") != "SPRING_CGLIB_PROXY":
            raise ValidationError(f"method-security-proxy-observations.json proxyMechanism must be 'SPRING_CGLIB_PROXY'")
        if data.get("methodSecurityInterceptor") != "AuthorizationManagerBeforeMethodInterceptor":
            raise ValidationError(f"method-security-proxy-observations.json methodSecurityInterceptor must be 'AuthorizationManagerBeforeMethodInterceptor'")

        subcases = data.get("subcases")
        if not isinstance(subcases, list) or len(subcases) == 0:
            raise ValidationError("method-security-proxy-observations.json must contain non-empty 'subcases'")

        subcases_by_variant = {}
        for sc in subcases:
            var = sc.get("variant")
            if not var:
                raise ValidationError("Proxy subcase missing 'variant' field")
            if var in subcases_by_variant:
                raise ValidationError(f"Proxy subcases contain duplicate variant '{var}'")
            subcases_by_variant[var] = sc

            cb = sc.get("counterBefore")
            ca = sc.get("counterAfter")
            if not _is_strict_int(cb) or not _is_strict_int(ca):
                raise ValidationError(f"Proxy subcase '{var}' counters must be strict integers")

        for req_var in ["direct_proxy_call_viewer_denied", "self_invocation_internal_call_viewer_bypassed", "external_wrapper_call_viewer_denied", "direct_proxy_call_trader_success"]:
            if req_var not in subcases_by_variant:
                raise ValidationError(f"Proxy observations missing required subcase '{req_var}'")

        # 1. Denied external calls must NOT increment counter
        v_denied = subcases_by_variant["direct_proxy_call_viewer_denied"]
        if v_denied["counterBefore"] != v_denied["counterAfter"] or v_denied.get("intercepted") is not True:
            raise ValidationError("direct_proxy_call_viewer_denied must not increment counter and must be intercepted")

        w_denied = subcases_by_variant["external_wrapper_call_viewer_denied"]
        if w_denied["counterBefore"] != w_denied["counterAfter"] or w_denied.get("intercepted") is not True:
            raise ValidationError("external_wrapper_call_viewer_denied must not increment counter and must be intercepted")

        # 2. Internal self-invocation MUST bypass and increment counter
        bypassed = subcases_by_variant["self_invocation_internal_call_viewer_bypassed"]
        if bypassed["counterAfter"] != bypassed["counterBefore"] + 1 or bypassed.get("intercepted") is not False:
            raise ValidationError("self_invocation_internal_call_viewer_bypassed must increment counter (+1) and must NOT be intercepted")

        # 3. Authorized trader call MUST succeed and increment counter
        trader = subcases_by_variant["direct_proxy_call_trader_success"]
        if trader["counterAfter"] != trader["counterBefore"] + 1 or trader.get("outcome") != "SUCCESS_AUTHORIZED":
            raise ValidationError("direct_proxy_call_trader_success must increment counter (+1) and have outcome SUCCESS_AUTHORIZED")

def main():
    parser = argparse.ArgumentParser(description="AetherCore Lab Evidence Collector")
    parser.add_argument("--run", action="store_true", help="Execute full Maven clean test before collection")
    parser.add_argument("--prompt", type=str, default="prompt21", help="Target prompt directory under docs/evidence (e.g. prompt15)")
    args = parser.parse_args()

    collector = EvidenceCollector(prompt=args.prompt)
    if args.run:
        collector.run_suite_and_collect()
    else:
        print("Use --run to execute full Maven clean test and collect evidence.")

if __name__ == '__main__':
    main()