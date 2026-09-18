"""Check Windows launch selection and diagnostics without requiring Maven or Azure."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run_local


class WindowsLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="office2md Windows & tests ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.stage = self.root / "target" / "azure-functions" / "office2md-local"
        self.stage.mkdir(parents=True)
        (self.stage / "host.json").write_text("{}")
        (self.root / "local.settings.json").write_text(json.dumps({
            "Values": {"CONVERSION_STORAGE_CONNECTION_STRING": "test-storage-secret"},
        }))
        self.output = io.StringIO()
        self.errors = io.StringIO()
        for context in (
            patch.object(run_local, "WINDOWS", True),
            patch.object(run_local, "ROOT", self.root),
            patch.dict(os.environ, {"JAVA_HOME": "C:\\Java\\jdk-21"}, clear=True),
            patch.object(run_local.sys, "argv", ["run_local.py", "--port", "7082"]),
            contextlib.redirect_stdout(self.output),
            contextlib.redirect_stderr(self.errors),
        ):
            context.__enter__()
            self.addCleanup(context.__exit__, None, None, None)

    def test_windows_build_and_host_preserve_settings_directory_and_exit_code(self):
        for extension in ("cmd", "exe"):
            with self.subTest(extension=extension):
                func = self.root / "Tools & SDK" / f"func.{extension}"
                with patch.object(run_local.shutil, "which", return_value=str(func)), \
                        patch.object(run_local.subprocess, "run", side_effect=[
                            subprocess.CompletedProcess([], 0),
                            subprocess.CompletedProcess([], 17),
                        ]) as child, patch.object(run_local.os, "execvpe") as replace:
                    self.assertEqual(run_local.main(), 17)
                replace.assert_not_called()
                build, host = child.call_args_list
                self.assertEqual(build.args[0], f'"{self.root / "mvnw.cmd"}" -B -ntp package')
                self.assertTrue(build.kwargs["shell"])
                self.assertTrue(build.kwargs["check"])
                self.assertEqual(build.kwargs["cwd"], self.root)
                expected = (f'"{func}" start --port 7082' if extension == "cmd"
                            else [str(func), "start", "--port", "7082"])
                self.assertEqual(host.args[0], expected)
                self.assertEqual(host.kwargs["shell"], extension == "cmd")
                self.assertEqual(host.kwargs["cwd"], self.stage)
                self.assertEqual(host.kwargs["env"]["CONVERSION_QUEUE_CONNECTION_STRING"],
                                 "test-storage-secret")
                self.assertEqual(host.kwargs["env"]["AzureWebJobs.ProcessConversion.Disabled"], "false")
                self.assertNotIn("test-storage-secret", str(host.args))

    def test_skip_build_still_launches_windows_host(self):
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local.shutil, "which", return_value=str(self.root / "func.cmd")), \
                patch.object(run_local.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as child:
            self.assertEqual(run_local.main(), 0)
        child.assert_called_once()
        self.assertEqual(child.call_args.args[0], f'"{self.root / "func.cmd"}" start --port 7072')

    def test_winerror_reports_step_and_code_without_exception_payload(self):
        failure = OSError(8, "test-storage-secret")
        failure.winerror = 193
        with patch.object(run_local.shutil, "which", return_value=str(self.root / "func.exe")), \
                patch.object(run_local.subprocess, "run", side_effect=failure) as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_called_once()
        self.assertIn("during Maven build (OSError; WinError 193; errno 8)", self.errors.getvalue())
        self.assertNotIn("test-storage-secret", self.output.getvalue() + self.errors.getvalue())

    def test_host_launch_error_reports_host_step(self):
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local.shutil, "which", return_value=str(self.root / "func.cmd")), \
                patch.object(run_local.subprocess, "run", side_effect=OSError(2, "test-storage-secret")):
            self.assertEqual(run_local.main(), 1)
        self.assertIn("during Functions host startup", self.errors.getvalue())
        self.assertNotIn("test-storage-secret", self.errors.getvalue())

    def test_build_failure_reports_exit_code_and_does_not_start_host(self):
        failure = subprocess.CalledProcessError(7, ["test-storage-secret"])
        with patch.object(run_local.shutil, "which", return_value=str(self.root / "func.exe")), \
                patch.object(run_local.subprocess, "run", side_effect=failure) as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_called_once()
        self.assertIn("during Maven build (CalledProcessError; exit code 7)", self.errors.getvalue())
        self.assertNotIn("test-storage-secret", self.errors.getvalue())


@unittest.skipUnless(os.name == "nt", "Requires the Windows command processor")
class WindowsBatchIntegrationTest(unittest.TestCase):
    def test_batch_path_with_spaces_and_ampersand_preserves_arguments_and_exit_code(self):
        with tempfile.TemporaryDirectory(prefix="office2md Windows & tests ") as directory:
            root = Path(directory)
            captured = root / "arguments.txt"
            batch = root / "func.cmd"
            batch.write_text(f'@echo off\n@echo %*>"{captured}"\n@exit /b 17\n')
            result = run_local.run_command([str(batch), "start", "--port", "7082"], cwd=root)
            self.assertEqual(result.returncode, 17)
            self.assertEqual(captured.read_text().strip(), "start --port 7082")


if __name__ == "__main__":
    unittest.main()
