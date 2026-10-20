"""Check Windows launch selection and diagnostics without requiring Maven or Azure."""

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import run_local


class WindowsLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="office2pdf Windows & tests ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.stage = self.root / "target" / "azure-functions" / "office2pdf-local"
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
        self.assertEqual(child.call_args.args[0], f'"{self.root / "func.cmd"}" start --port 7074')

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

    def use_development_storage(self):
        (self.root / "local.settings.json").write_text(json.dumps({
            "Values": {"CONVERSION_STORAGE_CONNECTION_STRING": "UseDevelopmentStorage=true",
                       "CONVERSION_CREATE_RESOURCES": "false"},
        }))

    def test_azurite_missing_binary_reports_safe_guidance_and_never_starts_host(self):
        self.use_development_storage()
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local, "find_tool", side_effect=lambda name:
                             str(self.root / "func.cmd") if name == "func" else None), \
                patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local, "run_command") as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_not_called()
        diagnostic = self.errors.getvalue()
        self.assertIn("during Azurite startup", diagnostic)
        self.assertIn("AZURITE_NOT_FOUND", diagnostic)
        self.assertIn("npm.cmd install -g azurite", diagnostic)
        self.assertIn("azurite.cmd --version", diagnostic)
        self.assertIn("same terminal", diagnostic)
        self.assertNotIn("test-storage-secret", self.output.getvalue() + diagnostic)

    def test_azurite_launch_error_reports_original_codes_and_safe_guidance(self):
        self.use_development_storage()
        failure = OSError(8, "test-storage-secret: untrusted Azurite message")
        failure.winerror = 193
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local, "find_tool", side_effect=lambda name: str(self.root / f"{name}.cmd")), \
                patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local.subprocess, "Popen", side_effect=failure), \
                patch.object(run_local, "run_command") as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_not_called()
        diagnostic = self.errors.getvalue()
        for token in ("during Azurite startup", "AZURITE_LAUNCH_FAILED", "WinError 193", "errno 8"):
            self.assertIn(token, diagnostic)
        self.assertNotIn("test-storage-secret", self.output.getvalue() + diagnostic)
        self.assertNotIn("untrusted Azurite message", diagnostic)
        self.assertIn("azurite.cmd --version", diagnostic)
        self.assertIn("node --version", diagnostic)

    def test_azurite_child_exit_reports_exit_code_and_does_not_start_host(self):
        self.use_development_storage()
        process = Mock()
        process.poll.return_value = 19
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local, "find_tool", side_effect=lambda name: str(self.root / f"{name}.cmd")), \
                patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local.subprocess, "Popen", return_value=process), \
                patch.object(run_local.time, "monotonic", return_value=0), \
                patch.object(run_local, "stop_process") as stop, \
                patch.object(run_local, "run_command") as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_not_called()
        stop.assert_called_once_with(process)
        self.assertIn("AZURITE_EXITED", self.errors.getvalue())
        self.assertIn("exit code 19", self.errors.getvalue())
        self.assertIn("azurite.cmd --disableTelemetry --skipApiVersionCheck", self.errors.getvalue())
        self.assertIn("10000, 10001 or 10002", self.errors.getvalue())
        self.assertNotIn("test-storage-secret", self.errors.getvalue())


    def test_azurite_timeout_displays_trusted_guidance(self):
        failure = run_local.AzuriteStartupError("AZURITE_TIMEOUT")
        with patch.object(run_local.sys, "argv", ["run_local.py", "--skip-build"]), \
                patch.object(run_local, "find_tool", return_value=str(self.root / "func.cmd")), \
                patch.object(run_local, "prepare_azurite", side_effect=failure), \
                patch.object(run_local, "run_command") as child:
            self.assertEqual(run_local.main(), 1)
        child.assert_not_called()
        diagnostic = self.errors.getvalue()
        self.assertIn("AZURITE_TIMEOUT", diagnostic)
        self.assertIn(failure.guidance, diagnostic)
        self.assertIn("10000 and 10001", diagnostic)
        self.assertNotIn("test-storage-secret", self.output.getvalue() + diagnostic)


class AzuriteStartupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="Azurite Windows & tests ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.environment = {
            "AzureWebJobsStorage": "UseDevelopmentStorage=true",
            "UNRELATED_SETTING": "test-storage-secret",
        }
        self.output = io.StringIO()
        for context in (
            patch.object(run_local, "WINDOWS", True),
            patch.object(run_local, "ROOT", self.root),
            contextlib.redirect_stdout(self.output),
        ):
            context.__enter__()
            self.addCleanup(context.__exit__, None, None, None)

    def test_non_development_storage_does_not_probe_or_start_azurite(self):
        with patch.object(run_local, "ports_ready") as ready, \
                patch.object(run_local, "find_tool") as find, \
                patch.object(run_local.subprocess, "Popen") as launch:
            self.assertIsNone(run_local.prepare_azurite({"AzureWebJobsStorage": "test-storage-secret"}, self.root))
        ready.assert_not_called()
        find.assert_not_called()
        launch.assert_not_called()

    def test_existing_local_instance_is_reused_without_finding_or_launching_binary(self):
        with patch.object(run_local, "ports_ready", return_value=True), \
                patch.object(run_local, "find_tool") as find, \
                patch.object(run_local.subprocess, "Popen") as launch:
            self.assertIsNone(run_local.prepare_azurite(self.environment, self.root))
        find.assert_not_called()
        launch.assert_not_called()
        self.assertIn("using the existing local instance", self.output.getvalue())

    def test_missing_binary_has_explicit_safe_error_code(self):
        with patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local, "find_tool", return_value=None), \
                patch.object(run_local.subprocess, "Popen") as launch:
            with self.assertRaises(run_local.AzuriteStartupError) as caught:
                run_local.prepare_azurite(self.environment, self.root)
        self.assertEqual(caught.exception.code, "AZURITE_NOT_FOUND")
        self.assertIn("AZURITE_NOT_FOUND", run_local.error_summary(caught.exception))
        launch.assert_not_called()

    def test_launch_oserror_preserves_errno_and_winerror_without_payload(self):
        failure = OSError(8, "test-storage-secret: untrusted child launch details")
        failure.winerror = 193
        with patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local, "find_tool", return_value=str(self.root / "azurite.cmd")), \
                patch.object(run_local.subprocess, "Popen", side_effect=failure), \
                patch.object(run_local, "wait_for_azurite") as wait:
            with self.assertRaises(run_local.AzuriteStartupError) as caught:
                run_local.prepare_azurite(self.environment, self.root)
        error = caught.exception
        self.assertEqual(error.code, "AZURITE_LAUNCH_FAILED")
        self.assertEqual(error.errno, 8)
        self.assertEqual(error.winerror, 193)
        summary = run_local.error_summary(error)
        for token in ("AZURITE_LAUNCH_FAILED", "WinError 193", "errno 8"):
            self.assertIn(token, summary)
        self.assertNotIn("test-storage-secret", summary + self.output.getvalue())
        self.assertNotIn("untrusted child launch details", summary)
        self.assertNotIn("test-storage-secret", str(error) + error.guidance)
        wait.assert_not_called()

    def test_windows_cmd_uses_shell_but_exe_does_not(self):
        for extension in ("cmd", "exe"):
            with self.subTest(extension=extension):
                executable = self.root / "Tools & SDK" / f"azurite.{extension}"
                process = Mock()
                with patch.object(run_local, "ports_ready", return_value=False), \
                        patch.object(run_local, "find_tool", return_value=str(executable)), \
                        patch.object(run_local.subprocess, "Popen", return_value=process) as launch, \
                        patch.object(run_local, "wait_for_azurite") as wait:
                    self.assertIs(run_local.prepare_azurite(self.environment, self.root), process)
                launch.assert_called_once()
                command = [str(executable), "--location", str(self.root / "data"), "--silent",
                           "--disableTelemetry", "--skipApiVersionCheck"]
                expected = (f'"{executable}" ' + subprocess.list2cmdline(command[1:])
                            if extension == "cmd" else command)
                self.assertEqual(launch.call_args.args[0], expected)
                self.assertEqual(launch.call_args.kwargs["shell"], extension == "cmd")
                self.assertFalse(launch.call_args.kwargs["start_new_session"])
                self.assertEqual(launch.call_args.kwargs["cwd"], self.root)
                self.assertEqual(launch.call_args.kwargs["env"], self.environment)
                self.assertEqual(launch.call_args.kwargs["stderr"], subprocess.STDOUT)
                self.assertNotIn("test-storage-secret", str(launch.call_args.args))
                wait.assert_called_once_with(process)

    def test_child_exit_including_zero_reports_exit_code_without_timeout(self):
        for exit_code in (17, 0, -9):
            with self.subTest(exit_code=exit_code):
                process = Mock()
                process.poll.return_value = exit_code
                with patch.object(run_local, "ports_ready", return_value=False), \
                        patch.object(run_local.time, "monotonic", return_value=0), \
                        patch.object(run_local.time, "sleep") as sleep:
                    with self.assertRaises(run_local.AzuriteStartupError) as caught:
                        run_local.wait_for_azurite(process)
                self.assertEqual(caught.exception.code, "AZURITE_EXITED")
                self.assertEqual(caught.exception.returncode, exit_code)
                summary = run_local.error_summary(caught.exception)
                self.assertIn("AZURITE_EXITED", summary)
                self.assertIn(f"exit code {exit_code}", summary)
                sleep.assert_not_called()

    def test_exited_child_is_not_mistaken_for_ready_unrelated_ports(self):
        process = Mock()
        process.poll.return_value = 7
        with patch.object(run_local, "ports_ready", return_value=True) as ready, \
                patch.object(run_local.time, "monotonic", return_value=0):
            with self.assertRaises(run_local.AzuriteStartupError) as caught:
                run_local.wait_for_azurite(process)
        self.assertEqual(caught.exception.code, "AZURITE_EXITED")
        self.assertEqual(caught.exception.returncode, 7)
        ready.assert_not_called()

    def test_deadline_reports_timeout_for_still_running_child(self):
        process = Mock()
        process.poll.return_value = None
        ticks = iter(range(100))
        with patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local.time, "monotonic", side_effect=lambda: next(ticks)), \
                patch.object(run_local.time, "sleep"):
            with self.assertRaises(run_local.AzuriteStartupError) as caught:
                run_local.wait_for_azurite(process, timeout=3)
        self.assertEqual(caught.exception.code, "AZURITE_TIMEOUT")
        self.assertIn("AZURITE_TIMEOUT", run_local.error_summary(caught.exception))
        self.assertNotIn("exit code", run_local.error_summary(caught.exception))
        process.poll.assert_called()

    def test_wait_returns_when_ports_become_ready(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(run_local, "ports_ready", side_effect=[False, True]), \
                patch.object(run_local.time, "monotonic", return_value=0), \
                patch.object(run_local.time, "sleep"):
            self.assertIsNone(run_local.wait_for_azurite(process))

    def test_failed_readiness_stops_the_process_started_by_this_launcher(self):
        process = Mock()
        process.poll.return_value = 23
        with patch.object(run_local, "ports_ready", return_value=False), \
                patch.object(run_local, "find_tool", return_value=str(self.root / "azurite.cmd")), \
                patch.object(run_local.subprocess, "Popen", return_value=process), \
                patch.object(run_local.time, "monotonic", return_value=0), \
                patch.object(run_local, "stop_process") as stop:
            with self.assertRaises(run_local.AzuriteStartupError) as caught:
                run_local.prepare_azurite(self.environment, self.root)
        self.assertEqual(caught.exception.code, "AZURITE_EXITED")
        self.assertEqual(caught.exception.returncode, 23)
        stop.assert_called_once_with(process)


@unittest.skipUnless(os.name == "nt", "Requires the Windows command processor")
class WindowsBatchIntegrationTest(unittest.TestCase):
    def test_batch_path_with_spaces_and_ampersand_preserves_arguments_and_exit_code(self):
        with tempfile.TemporaryDirectory(prefix="office2pdf Windows & tests ") as directory:
            root = Path(directory)
            captured = root / "arguments.txt"
            batch = root / "func.cmd"
            batch.write_text(f'@echo off\n@echo %*>"{captured}"\n@exit /b 17\n')
            result = run_local.run_command([str(batch), "start", "--port", "7082"], cwd=root)
            self.assertEqual(result.returncode, 17)
            self.assertEqual(captured.read_text().strip(), "start --port 7082")


if __name__ == "__main__":
    unittest.main()
