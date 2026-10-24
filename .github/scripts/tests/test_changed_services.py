"""Exercise selection against real Git histories and GitHub event payloads."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "changed_services.py"
SERVICES = ["auth-profile", "forecast", "gateway-server", "orchestrator", "preprocessor"]


class ChangedServicesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-b", "main")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI tests")
        for service in SERVICES:
            self.write(f"services/{service}/build.gradle.kts", "plugins { java }")
        self.base = self.commit()

    def git(self, *arguments):
        return subprocess.check_output(
            ["git", *arguments], cwd=self.root, stderr=subprocess.PIPE
        ).decode().strip()

    def write(self, path, content="changed"):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "test change")
        return self.git("rev-parse", "HEAD")

    def select(self, event_name="push", event=None):
        event_path = self.root / "event.json"
        output_path = self.root / "output.txt"
        event_path.write_text(json.dumps(event if event is not None else {"before": self.base}))
        subprocess.run(
            ["python3", str(SCRIPT)], cwd=self.root, check=True, capture_output=True,
            env={**os.environ, "GITHUB_EVENT_NAME": event_name,
                 "GITHUB_EVENT_PATH": str(event_path), "GITHUB_OUTPUT": str(output_path)},
        )
        outputs = dict(line.split("=", 1) for line in output_path.read_text().splitlines())
        services = json.loads(outputs["services"])
        self.assertEqual(outputs["has_services_changes"], str(bool(services)).lower())
        return services

    def test_push_includes_every_commit_in_the_push(self):
        self.write("services/forecast/src/first.java")
        self.commit()
        self.write("services/preprocessor/src/second.java")
        self.commit()
        self.assertEqual(self.select(), ["forecast", "preprocessor"])

    def test_pull_request_uses_merge_base_not_target_only_changes(self):
        self.git("checkout", "-b", "feature/test")
        self.write("services/forecast/src/feature.java")
        self.commit()
        self.git("checkout", "main")
        self.write("services/preprocessor/src/target.java")
        target = self.commit()
        self.git("checkout", "feature/test")
        self.assertEqual(
            self.select("pull_request", {"pull_request": {"base": {"sha": target}}}),
            ["forecast"],
        )

    def test_pull_request_merge_commit_excludes_target_changes(self):
        self.git("checkout", "-b", "feature/test")
        self.write("services/forecast/src/feature.java")
        self.commit()
        self.git("checkout", "main")
        self.write("services/preprocessor/src/target.java")
        target = self.commit()
        self.git("merge", "--no-ff", "feature/test", "-m", "PR merge")
        self.assertEqual(
            self.select("pull_request", {"pull_request": {"base": {"sha": target}}}),
            ["forecast"],
        )

    def test_shared_inputs_select_all_services(self):
        for path in ["build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml",
                     "gradle/wrapper/gradle-wrapper.properties", "gradlew", "gradle.properties",
                     ".github/workflows/ci-build.yml", "buildSrc/build.gradle.kts",
                     "checkstyle/checkstyle.xml"]:
            with self.subTest(path=path):
                self.git("reset", "--hard", self.base)
                self.git("clean", "-fd")
                self.write(path)
                self.commit()
                self.assertEqual(self.select(), SERVICES)

    def test_packaged_openapi_selects_auth_profile(self):
        self.write("docs/openapi/auth-profile.yaml")
        self.commit()
        self.assertEqual(self.select(), ["auth-profile"])

    def test_documentation_only_skips_service_builds(self):
        self.write("README.md")
        self.write("docs/architecture.md")
        self.commit()
        self.assertEqual(self.select(), [])

    def test_new_branch_selects_all_services(self):
        self.assertEqual(self.select(event={"before": "0" * 40}), SERVICES)

    def test_missing_force_push_base_selects_all_services(self):
        self.assertEqual(self.select(event={"before": "f" * 40}), SERVICES)

    def test_manual_run_selects_all_services(self):
        self.assertEqual(self.select("workflow_dispatch", {}), SERVICES)

    def test_rename_between_services_selects_both(self):
        self.write("services/forecast/src/original.java")
        self.base = self.commit()
        self.write("services/preprocessor/src/placeholder.java")
        self.git("mv", "services/forecast/src/original.java", "services/preprocessor/src/moved.java")
        self.commit()
        self.assertEqual(self.select(), ["forecast", "preprocessor"])

    def test_deleted_service_is_excluded_from_matrix(self):
        self.git("rm", "services/forecast/build.gradle.kts")
        self.write("services/orchestrator/src/change.java")
        self.commit()
        self.assertEqual(self.select(), ["orchestrator"])

    def test_added_service_is_included_in_matrix(self):
        self.write("services/new-service/build.gradle.kts")
        self.commit()
        self.assertEqual(self.select(), ["new-service"])

    def test_changed_filename_can_contain_spaces_and_newlines(self):
        self.write("services/forecast/src/a file\nwith newline.java")
        self.commit()
        self.assertEqual(self.select(), ["forecast"])

    def test_deleting_source_selects_its_service(self):
        self.write("services/forecast/src/deleted.java")
        self.base = self.commit()
        self.git("rm", "services/forecast/src/deleted.java")
        self.commit()
        self.assertEqual(self.select(), ["forecast"])


if __name__ == "__main__":
    unittest.main()
