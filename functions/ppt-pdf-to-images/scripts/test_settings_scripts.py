"""Exercise the real launchers with fake local child commands; no Azure account needed."""

import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

from async_settings import QUEUE_STORAGE_KEY, STORAGE_KEY, MI_KEYS, QUEUE_MI_KEYS, derive_settings


class SettingsScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="conversion-script-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        (self.root / "scripts").mkdir()
        source = Path(__file__).parent
        for name in ("async_settings.py", "run_local.py", "configure_azure_async.py"):
            shutil.copy2(source / name, self.root / "scripts" / name)
        self.stage = self.root / "target" / "azure-functions" / "slide2image-local"
        self.stage.mkdir(parents=True)
        (self.stage / "host.json").write_text('{"version":"2.0"}')
        self.capture = self.root / "child-capture.json"
        self.env = os.environ.copy()
        for key in list(self.env):
            if key.startswith("CONVERSION_") or key.startswith("AzureWebJobs."):
                self.env.pop(key, None)
        for key in (STORAGE_KEY, QUEUE_STORAGE_KEY, "AzureWebJobsStorage"):
            self.env.pop(key, None)
        self.env.update({
            "PATH": str(self.bin) + os.pathsep + self.env.get("PATH", ""),
            "JAVA_HOME": "/test/jdk-21",
            "CHILD_CAPTURE": str(self.capture),
            "PYTHONDONTWRITEBYTECODE": "1",
        })
        self.fake("func", """
import json, os, sys
from pathlib import Path
json.dump({'argv': sys.argv, 'cwd': str(Path.cwd()), 'env': dict(os.environ)},
          open(os.environ['CHILD_CAPTURE'], 'w'))
""")
        self.fake("az", """
import json, os, stat, sys
from pathlib import Path
if "delete" in sys.argv:
    sys.exit(0)
if "list" in sys.argv:
    print(os.environ.get("FAKE_AZ_EXISTING", "[]"))
    sys.exit(0)
settings_file = Path(sys.argv[sys.argv.index('--settings') + 1][1:])
payload = json.loads(settings_file.read_text())
json.dump({'argv': sys.argv, 'payload': payload, 'temporaryFile': str(settings_file),
           'mode': stat.S_IMODE(settings_file.stat().st_mode)},
          open(os.environ['CHILD_CAPTURE'], 'w'))
if os.environ.get('FAKE_AZ_FAIL'):
    print(payload['CONVERSION_STORAGE_CONNECTION_STRING'], file=sys.stderr)
    sys.exit(17)
""")

    def fake(self, name, source):
        file = self.bin / name
        file.write_text(f"#!{sys.executable}\n" + source)
        file.chmod(0o700)

    def run_script(self, name, *args):
        return subprocess.run(
            [sys.executable, str(self.root / "scripts" / name), *args],
            cwd=self.root, env=self.env, capture_output=True, text=True, check=False,
        )

    def local_settings(self, **values):
        (self.root / "local.settings.json").write_text(json.dumps({
            "IsEncrypted": False, "Values": values,
        }))

    def assert_switches(self, values, enabled):
        for name in ("ProcessConversion", "PoisonConversion"):
            self.assertEqual(values[f"AzureWebJobs.{name}.Disabled"], str(not enabled).lower())

    def test_local_empty_env_overrides_stale_file_and_disables_listeners(self):
        self.local_settings(**{
            STORAGE_KEY: "stale-file-value", "UNRELATED_SETTING": "keep-me",
            "CONVERSION_MAX_INPUT_BYTES": "9999",
            "AzureWebJobs.ProcessConversion.Disabled": "false",
        })
        self.env[STORAGE_KEY] = "  "
        self.env["CONVERSION_MAX_INPUT_BYTES"] = "1234"
        result = self.run_script("run_local.py", "--skip-build", "--port", "7088")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(captured["env"][STORAGE_KEY], "")
        self.assertEqual(captured["env"][QUEUE_STORAGE_KEY], "UseDevelopmentStorage=true")
        self.assertEqual(captured["env"]["UNRELATED_SETTING"], "keep-me")
        self.assertEqual(captured["env"]["CONVERSION_MAX_INPUT_BYTES"], "1234")
        self.assert_switches(captured["env"], False)
        self.assertEqual(captured["argv"][1:], ["start", "--port", "7088"])
        self.assertEqual(Path(captured["cwd"]).resolve(), self.stage.resolve())

    def test_local_env_enables_both_clients_and_does_not_print_secret(self):
        self.local_settings(**{"UNRELATED_SETTING": "keep-me"})
        self.env[STORAGE_KEY] = "not-a-real-storage-secret"
        result = self.run_script("run_local.py", "--skip-build")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(captured["env"][QUEUE_STORAGE_KEY], self.env[STORAGE_KEY])
        self.assertEqual(captured["env"]["AzureWebJobsStorage"], self.env[STORAGE_KEY])
        self.assert_switches(captured["env"], True)
        self.assertNotIn(self.env[STORAGE_KEY], result.stdout + result.stderr)
        file = self.stage / "local.settings.json"
        self.assertEqual(stat.S_IMODE(file.stat().st_mode), 0o600)
        self.assertEqual(json.loads(file.read_text())["Values"][STORAGE_KEY], self.env[STORAGE_KEY])

    def test_azure_on_uses_one_protected_payload_and_no_secret_arguments(self):
        self.env[STORAGE_KEY] = "not-a-real-storage-secret"
        result = self.run_script("configure_azure_async.py", "--resource-group", "rg",
                                 "--name", "app", "--slot", "staging")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(len(captured["payload"]), 5)
        self.assertEqual(captured["payload"][QUEUE_STORAGE_KEY], self.env[STORAGE_KEY])
        self.assert_switches(captured["payload"], True)
        self.assertEqual(captured["mode"], 0o600)
        self.assertFalse(Path(captured["temporaryFile"]).exists())
        self.assertEqual(captured["argv"][-2:], ["--slot", "staging"])
        self.assertNotIn(self.env[STORAGE_KEY], json.dumps(captured["argv"]))
        self.assertNotIn(self.env[STORAGE_KEY], result.stdout + result.stderr)

    def test_azure_off_replaces_only_the_four_managed_values(self):
        result = self.run_script("configure_azure_async.py", "--resource-group", "rg", "--name", "app")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(captured["payload"][STORAGE_KEY], "")
        self.assertEqual(captured["payload"][QUEUE_STORAGE_KEY], "UseDevelopmentStorage=true")
        self.assert_switches(captured["payload"], False)
        self.assertEqual(set(captured["payload"]), {
            STORAGE_KEY, QUEUE_STORAGE_KEY,
            "AzureWebJobs.ProcessConversion.Disabled", "AzureWebJobs.PoisonConversion.Disabled",
            "AzureWebJobs.MaintainConversions.Disabled",
        })

    def test_identity_removes_stale_scalar_and_disables_unused_timer(self):
        self.local_settings(**{QUEUE_STORAGE_KEY: "stale-trigger-connection", "AzureWebJobsStorage": "UseDevelopmentStorage=true"})
        self.env.update({MI_KEYS[0]: "https://example.blob.core.windows.net", MI_KEYS[1]: "https://example.queue.core.windows.net"})
        result = self.run_script("run_local.py", "--skip-build")
        self.assertEqual(result.returncode, 0, result.stderr)
        env = json.loads(self.capture.read_text())["env"]
        self.assertNotIn(QUEUE_STORAGE_KEY, env)
        self.assertNotIn(STORAGE_KEY, env)
        self.assertEqual(env[QUEUE_MI_KEYS[0]], "https://example.queue.core.windows.net")
        self.assertEqual(env[QUEUE_MI_KEYS[1]], "managedidentity")
        self.assertEqual(env["AzureWebJobs.MaintainConversions.Disabled"], "true")
        self.assert_switches(env, True)

    def test_maintenance_requires_async_and_opt_in(self):
        base = {STORAGE_KEY: "UseDevelopmentStorage=true"}
        self.assertEqual(derive_settings(base)["AzureWebJobs.MaintainConversions.Disabled"], "true")
        self.assertEqual(derive_settings({**base, "CONVERSION_RESULT_RETENTION_DAYS": "1"})["AzureWebJobs.MaintainConversions.Disabled"], "false")
        self.assertEqual(derive_settings({"CONVERSION_RESULT_RETENTION_DAYS": "1"})["AzureWebJobs.MaintainConversions.Disabled"], "true")
        self.assertEqual(derive_settings({**base, "CONVERSION_RESULT_QUEUE_EVENTS__queueName": "result-events"})["AzureWebJobs.MaintainConversions.Disabled"], "false")
        with self.assertRaises(ValueError):
            derive_settings({MI_KEYS[0]: "https://example.blob.core.windows.net"})

    def test_authentication_update_preserves_remote_maintenance_activation_without_echoing_secrets(self):
        self.env[STORAGE_KEY] = "new-storage-secret"
        self.env["FAKE_AZ_EXISTING"] = json.dumps([
            {"name": "CONVERSION_RESULT_QUEUE_EVENTS__connectionString", "value": "existing-queue-secret"},
            {"name": "CONVERSION_RESULT_QUEUE_EVENTS__queueName", "value": "result-events"},
            {"name": "CONVERSION_RESULT_RETENTION_DAYS", "value": "7"},
            {"name": "UNRELATED_SECRET", "value": "never-echo-me"},
        ])
        result = self.run_script("configure_azure_async.py", "--resource-group", "rg", "--name", "app")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(captured["payload"]["AzureWebJobs.MaintainConversions.Disabled"], "false")
        self.assertNotIn("CONVERSION_RESULT_QUEUE_EVENTS__connectionString", captured["payload"])
        for secret in ("existing-queue-secret", "never-echo-me", "new-storage-secret"):
            self.assertNotIn(secret, result.stdout + result.stderr)

    def test_azure_failure_hides_cli_secret_and_removes_temporary_payload(self):
        self.env[STORAGE_KEY] = "not-a-real-storage-secret"
        self.env["FAKE_AZ_FAIL"] = "1"
        result = self.run_script("configure_azure_async.py", "--resource-group", "rg", "--name", "app")
        self.assertEqual(result.returncode, 17)
        self.assertNotIn(self.env[STORAGE_KEY], result.stdout + result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertFalse(Path(captured["temporaryFile"]).exists())


if __name__ == "__main__":
    unittest.main()
