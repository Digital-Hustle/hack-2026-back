"""Select existing Gradle services using the complete event's Git diff."""

import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


SHARED_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
}
SHARED_DIRECTORIES = (".github/", "gradle/", "buildSrc/", "build-logic/", "config/", "checkstyle/")
SERVICE_INPUTS = {"docs/openapi/auth-profile.yaml": "auth-profile"}


def git(*arguments):
    return subprocess.check_output(["git", *arguments], stderr=subprocess.PIPE)


def available_services():
    return sorted(
        path.parent.name
        for path in Path("services").glob("*/build.gradle.kts")
        if re.fullmatch(r"[a-zA-Z0-9_-]+", path.parent.name)
    )


def changed_files(event_name, event):
    if event_name == "pull_request":
        # checkout uses GitHub's merge commit: diff against its target branch.
        base = git("merge-base", event["pull_request"]["base"]["sha"], "HEAD").decode().strip()
    elif event_name == "push":
        base = event.get("before", "")
        if not base or set(base) == {"0"}:
            return None
        try:
            git("cat-file", "-e", f"{base}^{{commit}}")
        except subprocess.CalledProcessError:
            # A force push can make the previous tip unreachable after checkout.
            return None
    else:
        # A manual run verifies everything, without guessing a comparison base.
        return None

    # Disable rename detection so moves include both the old and new paths.
    paths = git("diff", "--name-only", "--no-renames", "-z", base, "HEAD", "--")
    return [os.fsdecode(path) for path in paths.split(b"\0") if path]


def select_services(services, paths):
    if paths is None or any(
        path in SHARED_FILES or path.startswith(SHARED_DIRECTORIES) for path in paths
    ):
        return services

    selected = set()
    for path in paths:
        parts = PurePosixPath(path).parts
        if len(parts) >= 3 and parts[0] == "services":
            selected.add(parts[1])
        if path in SERVICE_INPUTS:
            selected.add(SERVICE_INPUTS[path])
    return sorted(selected.intersection(services))


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    services = select_services(available_services(), changed_files(os.environ["GITHUB_EVENT_NAME"], event))
    result = {
        "services": json.dumps(services, separators=(",", ":")),
        "has_services_changes": str(bool(services)).lower(),
    }
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for name, value in result.items():
            print(f"{name}={value}", file=output)
    print(f"Selected services: {result['services']}")


if __name__ == "__main__":
    main()
