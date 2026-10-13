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
                'pagination-observations.json': [f"PG{i}" for i in range(1, 6)]
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

        if "exitCode" not in meta or meta["exitCode"] is None:
            reactor_status = "BUILD FAILURE (Exit Code: UNKNOWN/INVALID)"
            reactor_exit = "UNKNOWN"
        else:
            reactor_exit = meta['exitCode']
            reactor_status = "BUILD SUCCESS" if (reactor_exit == 0 and total_failures == 0 and total_errors == 0) else f"BUILD FAILURE (Exit Code: {reactor_exit})"

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
MODULE 2: backend-app (Spring Boot, Concurrency, JDBC, JPA, Propagation, Locking, Fetch, Pagination)
Total Test Classes: {len(backend_res['classes'])}
Total Tests Run: {total_backend} | Failures: {backend_failures} | Errors: {backend_errors} | Skipped: {backend_skipped}
--------------------------------------------------------------------------------
"""
        for cname, d in sorted(backend_res['classes'].items()):
            status_tag = f"[{d['status']}]"
            summary += f"- {cname}: {d['tests']} tests (elapsed: {d['time']}s) {status_tag}\n"

        summary += f"""
================================================================================
REACTOR TOTAL: {total_reactor} Tests Run | {total_failures} Failures | {total_errors} Errors | {total_skipped} Skipped | {"ALL PASS" if total_failures == 0 and total_errors == 0 else "FAILURES DETECTED"}
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
