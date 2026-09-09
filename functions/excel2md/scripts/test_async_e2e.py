#!/usr/bin/env python3
"""Opt-in local Functions/Azurite E2E. Requires a packaged app, Java 21, func v4 and azurite.

Run this script explicitly; importing it (including unittest discovery) starts no processes.
All three Storage accounts are generated for the local emulator. Existing settings are ignored.
Owned processes and temporary files are removed on exit; --keep-artifacts retains local evidence.
"""

import argparse
import base64
import hashlib
import io
import json
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]

FIXTURE_SOURCE = r"""
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
public class ExcelQueueFixture {
 public static void main(String[] args) throws Exception {
  try(var book=new XSSFWorkbook()) {
   var sheet=book.createSheet("売上と画像");
   var border=book.createCellStyle(); border.setBorderTop(BorderStyle.THIN);border.setBorderBottom(BorderStyle.THIN);border.setBorderLeft(BorderStyle.THIN);border.setBorderRight(BorderStyle.THIN);
   String[][] rows={{"項目","金額"},{"日本語の売上","12345"}};
   for(int r=0;r<rows.length;r++) for(int c=0;c<2;c++) {var row=sheet.getRow(r);if(row==null)row=sheet.createRow(r);var cell=row.createCell(c);cell.setCellValue(rows[r][c]);cell.setCellStyle(border);}
   var drawing=sheet.createDrawingPatriarch();
   var image=new BufferedImage(120,60,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graphics.setColor(Color.ORANGE);graphics.fillRect(0,0,120,60);graphics.setColor(Color.BLUE);graphics.fillOval(30,10,60,40);graphics.dispose();
   var png=new ByteArrayOutputStream(); ImageIO.write(image,"png",png);image.flush();
   var pictureAnchor=new XSSFClientAnchor();pictureAnchor.setCol1(0);pictureAnchor.setRow1(4);pictureAnchor.setCol2(3);pictureAnchor.setRow2(9);
   drawing.createPicture(pictureAnchor,book.addPicture(png.toByteArray(),Workbook.PICTURE_TYPE_PNG));
   var shapeAnchor=new XSSFClientAnchor();shapeAnchor.setCol1(5);shapeAnchor.setRow1(4);shapeAnchor.setCol2(8);shapeAnchor.setRow2(9);
   var rectangle=drawing.createSimpleShape(shapeAnchor);rectangle.setShapeType(ShapeTypes.RECT);rectangle.setFillColor(110,70,220);rectangle.setLineStyleColor(20,20,70);
   try(var out=Files.newOutputStream(Path.of(args[0]))){book.write(out);}
  }
 }
}
"""

PRODUCER_SOURCE = r"""
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
public class ExcelDirectProducer {
 public static void main(String[] args) throws Exception {
  String mode=args[0],id=args[1];
  String filename=Path.of(args[2]).getFileName().toString();
  String source=id+"/"+filename;
  if(mode.equals("missing"))source=id+"/missing.xlsx";
  if(mode.equals("submit")){
   var container=new BlobServiceClientBuilder().connectionString(System.getenv("E2E_INPUT_STORAGE")).buildClient().getBlobContainerClient("e2e-input");container.createIfNotExists();
   container.getBlobClient(source).upload(BinaryData.fromBytes(Files.readAllBytes(Path.of(args[2]))),false);
  }
  Map<String,Object> request=Map.of("version",1,"jobId",id,"input",Map.of("storage",mode.equals("unknown")?"unregistered":"source","container","e2e-input","blobName",source),"output",Map.of("storage","archive","container","e2e-output","prefix","external-system"),"filename",filename);
  var queue=new QueueClientBuilder().connectionString(System.getenv("E2E_MAIN_STORAGE")).queueName("excel2md-jobs").messageEncoding(QueueMessageEncoding.BASE64).buildClient();queue.createIfNotExists();queue.sendMessage(new ObjectMapper().writeValueAsString(request));
  System.out.println("Queued "+id+" ("+mode+")");
 }
}
"""


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def stop(process):
    """Stop only the process group that this test started, even if its parent exited first."""
    def signal_group(value):
        try:
            os.killpg(process.pid, value)
            return True
        except ProcessLookupError:
            return False
    if signal_group(signal.SIGTERM):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            process.poll()  # Reap the parent if it has exited.
            if not signal_group(0):
                break
            time.sleep(0.1)
        else:
            signal_group(signal.SIGKILL)
    process.wait(timeout=5)


def wait_until(predicate, children, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(child.poll() is not None for child in children):
            raise RuntimeError("A test service stopped while waiting for " + description)
        try:
            if predicate():
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise TimeoutError("Timed out waiting for " + description)


def port_ready(port):
    with socket.create_connection(("127.0.0.1", port), timeout=1):
        return True


class Api:
    def __init__(self, port, children, timeout, input_hash, strict_fixture):
        self.base = f"http://127.0.0.1:{port}"
        self.children = children
        self.timeout = timeout
        self.input_hash = input_hash
        self.strict_fixture = strict_fixture
        self.measurements = []
        # Local emulator tests must not use machine proxy settings or follow external redirects.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, message, headers, new_url):
                return None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, body=None):
        check(path.startswith("/api/") and not path.startswith("//"), "Unexpected API response path")
        request = urllib.request.Request(self.base + path, data=body,
                headers={"Content-Type": "application/octet-stream"} if body is not None else {})
        started = time.perf_counter()
        try:
            response = self.opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read(100 * 1024 * 1024 + 1)
            check(len(data) <= 100 * 1024 * 1024, "HTTP response exceeds the default output budget")
            self.measurements.append({"path": path, "method": request.get_method(), "status": response.status,
                    "elapsedMs": round((time.perf_counter() - started) * 1000, 3), "responseBytes": len(data)})
            return response.status, response.headers, data

    def ready(self):
        status, _, body = self.request("/api/capabilities")
        return status == 200 and json.loads(body).get("asyncEnabled") is True

    def wait_job(self, path):
        completed = []
        def terminal():
            status, _, body = self.request(path)
            if status == 200:
                data = json.loads(body)
                if data["job"]["status"] in ("succeeded", "failed"):
                    completed.append(data)
                    return True
            return False
        wait_until(terminal, self.children, self.timeout, "job completion")
        return completed[0]

    def check_result(self, data, expected_markdown=None):
        check(data["job"]["status"] == "succeeded", "Expected a successful job")
        output = {}
        for field, filename in (("resultUrl", "document.md"), ("reportUrl", "report.json"),
                                ("archiveUrl", "archive.zip")):
            check(data[field].startswith("/api/jobs/") and "?" not in data[field], "Unsafe result URL")
            status, _, body = self.request(data[field])
            check(status == 200, "Result download failed: " + filename)
            output[filename] = body
        report = json.loads(output["report.json"])
        check(report["source"]["sha256"] == self.input_hash, "Converted source SHA-256 differs from the provided workbook")
        if self.strict_fixture:
            check("日本語の売上" in output["document.md"].decode("utf-8"), "Japanese Markdown missing")
            check(report["sheetCount"] == 1, "Unexpected sheet count")
            check(len(report["assets"]) == 2, "Expected an embedded image and a diagram PNG")
        check(len(report["assets"]) <= 1200, "Too many report assets")
        for asset in report["assets"]:
            check(re.fullmatch(r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}", asset["path"]), "Unsafe asset path")
            name = asset["path"].split("/")[-1]
            status, headers, body = self.request(data["assetsBaseUrl"] + name)
            check(status == 200, "Asset download failed: " + asset["path"])
            check(headers["Content-Type"] == asset["contentType"],
                  "Asset MIME differs for " + asset["path"] + ": " + str(headers["Content-Type"])
                  + " (expected " + asset["contentType"] + ")")
            if asset["contentType"] == "image/png":
                check(body[:8] == b"\x89PNG\r\n\x1a\n", "Image is not a PNG")
            elif asset["contentType"] == "image/jpeg":
                check(body[:3] == b"\xff\xd8\xff", "Image is not a JPEG")
            check(len(body) == asset["sizeBytes"], "Image size mismatch")
            check(hashlib.sha256(body).hexdigest() == asset["sha256"], "Image checksum mismatch")
            output[asset["path"]] = body
        status, _, _ = self.request(data["assetsBaseUrl"] + "not-listed.png")
        check(status == 404, "An unlisted artifact must not be accessible")
        archived = unzip_artifacts(output["archive.zip"])
        check(set(archived) == set(output) - {"archive.zip"}, "Archive entries differ from artifacts")
        for name, body in archived.items():
            check(body == output[name], "Archive content mismatch: " + name)
        if expected_markdown is not None:
            check(output["document.md"] == expected_markdown, "Sync/async Markdown differs")
        return output


def unzip_artifacts(data):
    files = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        check(len(archive.infolist()) <= 1202, "Too many ZIP entries")
        total = 0
        for entry in archive.infolist():
            name = entry.filename
            check(name in ("document.md", "report.json") or re.fullmatch(r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}", name), "Unsafe ZIP artifact path")
            check(name not in files and not entry.is_dir() and not entry.flag_bits & 1, "Duplicate/directory/encrypted ZIP entry")
            maximum = 100 * 1024 * 1024 if name == "report.json" else 20 * 1024 * 1024
            check(0 <= entry.file_size <= maximum, "ZIP member exceeds the default budget")
            total += entry.file_size
            check(total <= 100 * 1024 * 1024, "Expanded ZIP exceeds the default budget")
            files[name] = archive.read(entry)
    check("document.md" in files and "report.json" in files, "Required ZIP artifacts missing")
    return files


def save_evidence(directory, files, metadata):
    if directory is None:
        return
    directory.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        check(name in ("result.zip", "archive.zip", "document.md", "report.json")
              or re.fullmatch(r"images/[a-z]+-[0-9]+\.[a-z0-9]{1,8}", name), "Unexpected evidence filename")
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (directory / "request.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if "job" in metadata:
        (directory / "status.json").write_text(json.dumps(metadata["job"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_evidence_index(directory, summary):
    if directory is None:
        return
    (directory / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# 同一Excelの実HTTP・Queue変換", "", "**" + ("PASS" if summary["passed"] else "FAIL") + "**", "",
             "提供されたExcelを、ローカルFunctionsホストと別々のAzuriteアカウントで実処理しました。", "",
             "入力SHA-256: `" + summary["input"]["sha256"] + "`", "",
             "| 経路 | 完了まで（ms） | 実応答 | Markdown | レポート |", "| --- | ---: | --- | --- | --- |"]
    for name, label, zip_name in (("sync", "同期HTTP", "result.zip"), ("http-job", "HTTP受付→Queue", "archive.zip"),
                                  ("direct-job", "外部システム→直接Queue", "archive.zip")):
        if name in summary["timings"]:
            lines.append(f"| {label} | {summary['timings'][name]} | [ZIP]({name}/{zip_name}) | [Markdown]({name}/document.md) | [JSON]({name}/report.json) |")
    lines += ["", "時間は要求開始から完了状態の取得までです。直接Queueは送信用Javaの起動時間を含みます。", "",
              "各フォルダのimages/に実際の添付・描画画像を保存しています。接続文字列・local.settings.jsonは保存していません。", ""]
    (directory / "README.md").write_text("\n".join(lines), encoding="utf-8")


def child_environment(settings, accounts, java):
    # Never carry production app settings, connection strings or Java agents into the local host.
    prefixes = ("CONVERSION_", "AZUREWEBJOBS", "E2E_", "APPINSIGHTS", "APPLICATIONINSIGHTS", "OTEL_")
    env = {key: value for key, value in os.environ.items()
           if not key.upper().startswith(prefixes)
           and key not in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    env.update(settings)
    env.update({
        "AZURITE_ACCOUNTS": ";".join(name + ":" + key for name, key in accounts.items()),
        "FUNCTIONS_CORE_TOOLS_TELEMETRY_OPTOUT": "1",
        "AZURE_CORE_COLLECT_TELEMETRY": "false",
        "APPLICATIONINSIGHTS_CONNECTION_STRING": "",
        "APPINSIGHTS_INSTRUMENTATIONKEY": "",
        "NO_PROXY": "127.0.0.1,localhost",
        "no_proxy": "127.0.0.1,localhost",
    })
    properties = subprocess.run([java, "-XshowSettings:properties", "-version"],
                               capture_output=True, text=True, check=True, env=env)
    values = {}
    for line in properties.stderr.splitlines():
        key, separator, value = line.strip().partition(" = ")
        if separator:
            values[key] = value
    check(values.get("java.specification.version") == "21", "Run this test with Java 21")
    env["JAVA_HOME"] = values["java.home"]
    return env


def run(args, work, tools):
    stage = work / "functions"
    shutil.copytree(args.package_dir, stage)
    (work / "ExcelQueueFixture.java").write_text(FIXTURE_SOURCE, encoding="utf-8")
    (work / "ExcelDirectProducer.java").write_text(PRODUCER_SOURCE, encoding="utf-8")
    accounts = {name: base64.b64encode(secrets.token_bytes(64)).decode("ascii")
                for name in ("exceltest", "excelinput", "exceloutput")}
    def connection(name):
        return (f"DefaultEndpointsProtocol=http;AccountName={name};AccountKey={accounts[name]};"
                f"BlobEndpoint=http://127.0.0.1:{args.blob_port}/{name};"
                f"QueueEndpoint=http://127.0.0.1:{args.queue_port}/{name};"
                f"TableEndpoint=http://127.0.0.1:{args.table_port}/{name};")
    settings = {
        "FUNCTIONS_WORKER_RUNTIME": "java", "FUNCTIONS_WORKER_PROCESS_COUNT": "1",
        "AzureWebJobsStorage": connection("exceltest"),
        "CONVERSION_STORAGE_CONNECTION_STRING": connection("exceltest"),
        "CONVERSION_QUEUE_CONNECTION_STRING": connection("exceltest"),
        "CONVERSION_INPUT_STORAGE_SOURCE": connection("excelinput"),
        "CONVERSION_OUTPUT_STORAGE_ARCHIVE": connection("exceloutput"),
        "AzureWebJobs.ProcessConversion.Disabled": "false",
        "AzureWebJobs.PoisonConversion.Disabled": "false",
        "JAVA_OPTS": "-Djava.awt.headless=true",
    }
    local_settings = stage / "local.settings.json"
    with os.fdopen(os.open(local_settings, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w", encoding="utf-8") as output:
        json.dump({"IsEncrypted": False, "Values": settings}, output)
    local_settings.chmod(0o600)
    env = child_environment(settings, accounts, tools["java"])
    env["E2E_MAIN_STORAGE"] = connection("exceltest")
    env["E2E_INPUT_STORAGE"] = connection("excelinput")
    classpath = str(stage / "lib" / "*")
    fixture = work / (args.input.name if args.input else "japanese.xlsx")
    if args.input:
        shutil.copy2(args.input, fixture)
    else:
        subprocess.run([tools["java"], "-Djava.awt.headless=true", "--class-path", classpath,
                        str(work / "ExcelQueueFixture.java"), str(fixture)], env=env, check=True)
    data = fixture.read_bytes()
    input_hash = hashlib.sha256(data).hexdigest()
    summary = {"passed": False, "startedAt": datetime.now(timezone.utc).isoformat(),
               "input": {"filename": fixture.name, "sizeBytes": len(data), "sha256": input_hash}, "timings": {}}
    children, logs = [], []
    api = Api(args.port, children, args.timeout, input_hash, args.input is None)
    def evidence(name):
        return args.result_dir / name if args.result_dir else None
    try:
        def start(command, logfile, cwd=None):
            log = (work / logfile).open("w", encoding="utf-8")
            logs.append(log)
            children.append(subprocess.Popen(command, cwd=cwd, env=env, stdout=log,
                            stderr=subprocess.STDOUT, start_new_session=True))
        start([tools["azurite"], "--location", str(work / "storage"), "--silent", "--disableTelemetry",
               "--skipApiVersionCheck", "--blobHost", "127.0.0.1", "--queueHost", "127.0.0.1",
               "--tableHost", "127.0.0.1", "--blobPort", str(args.blob_port),
               "--queuePort", str(args.queue_port), "--tablePort", str(args.table_port)], "azurite.log")
        wait_until(lambda: port_ready(args.table_port), children, 20, "Azurite startup")
        start([tools["func"], "start", "--port", str(args.port)], "functions.log", stage)
        wait_until(api.ready, children, args.timeout, "Functions host startup")
        print("PASS actual Functions host with Queue enabled", flush=True)
        query = urllib.parse.urlencode({"filename": fixture.name})
        started = time.perf_counter()
        status, _, raw = api.request("/api/convert?" + query, data)
        summary["timings"]["sync"] = round((time.perf_counter() - started) * 1000, 3)
        check(status == 200, "Synchronous HTTP conversion failed")
        sync = unzip_artifacts(raw)
        sync_report = json.loads(sync["report.json"])
        check(sync_report["source"]["sha256"] == input_hash, "Synchronous source SHA-256 differs")
        if args.input is None:
            check("日本語の売上" in sync["document.md"].decode("utf-8"), "Synchronous Japanese Markdown missing")
        save_evidence(evidence("sync"), {**sync, "result.zip": raw}, {"status": status, "elapsedMs": summary["timings"]["sync"], "inputSha256": input_hash})
        print("PASS synchronous HTTP ZIP", flush=True)
        started = time.perf_counter()
        status, headers, raw = api.request("/api/jobs?" + query, data)
        check(status == 202, "HTTP submission failed")
        submitted = json.loads(raw)
        check(headers["Location"] == submitted["statusUrl"], "Submission Location header mismatch")
        done = api.wait_job(submitted["statusUrl"])
        summary["timings"]["http-job"] = round((time.perf_counter() - started) * 1000, 3)
        output = api.check_result(done, sync["document.md"])
        save_evidence(evidence("http-job"), output, {"job": done, "elapsedMs": summary["timings"]["http-job"], "inputSha256": input_hash})
        print("PASS HTTP submit → Queue worker → Markdown/report/images/archive", flush=True)
        def produce(mode, job_id):
            subprocess.run([tools["java"], "--class-path", classpath, str(work / "ExcelDirectProducer.java"),
                            mode, job_id, str(fixture)], env=env, check=True)
        direct_id = str(uuid.uuid4())
        started = time.perf_counter()
        produce("submit", direct_id)
        direct = api.wait_job("/api/jobs/" + direct_id)
        summary["timings"]["direct-job"] = round((time.perf_counter() - started) * 1000, 3)
        output = api.check_result(direct, sync["document.md"])
        save_evidence(evidence("direct-job"), output, {"job": direct, "elapsedMs": summary["timings"]["direct-job"], "inputSha256": input_hash})
        print("PASS direct cross-account Queue request with worker-created state", flush=True)
        def deliveries():
            return (work / "functions.log").read_text(encoding="utf-8", errors="replace").count("Executed 'Functions.ProcessConversion'")
        before = deliveries()
        produce("duplicate", direct_id)
        wait_until(lambda: deliveries() > before, children, args.timeout, "duplicate Queue delivery")
        duplicate = api.wait_job("/api/jobs/" + direct_id)
        check(duplicate["job"]["updatedAt"] == direct["job"]["updatedAt"], "Duplicate delivery changed terminal state")
        api.check_result(duplicate, sync["document.md"])
        print("PASS duplicate delivery preserves the completed result", flush=True)
        for mode, code in (("missing", "INPUT_NOT_FOUND"), ("unknown", "UNKNOWN_INPUT_STORAGE")):
            job_id = str(uuid.uuid4())
            produce(mode, job_id)
            failed = api.wait_job("/api/jobs/" + job_id)
            check(failed["job"]["status"] == "failed" and failed["job"]["errorCode"] == code,
                  "Unexpected failure handling for " + code)
            print("PASS terminal failure " + code, flush=True)
        (work / "result.json").write_text(json.dumps({"passed": True, "httpJob": done, "directJob": direct},
                ensure_ascii=False, indent=2), encoding="utf-8")
        summary.update(passed=True, httpJob=done, directJob=direct)
        print("PASS complete local Functions/Azurite E2E", flush=True)
    finally:
        summary["requests"] = api.measurements
        summary["completedAt"] = datetime.now(timezone.utc).isoformat()
        cleanup_errors = []
        for child in reversed(children):
            try:
                stop(child)
            except (OSError, subprocess.TimeoutExpired) as error:
                cleanup_errors.append(type(error).__name__)
        for log in logs:
            log.close()
        if cleanup_errors:
            summary.update(passed=False, cleanupErrors=cleanup_errors)
        write_evidence_index(args.result_dir, summary)
        if cleanup_errors:
            raise RuntimeError("A test process could not be stopped completely")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Convert this real .xlsx/.xls through every route instead of generating the smoke fixture")
    parser.add_argument("--result-dir", type=Path, help="Preserve actual result ZIPs/Markdown/report/images and public job timings in this empty directory")
    parser.add_argument("--package-dir", type=Path, default=ROOT / "target" / "azure-functions" / "excel2md-local")
    parser.add_argument("--port", type=int, default=7073)
    parser.add_argument("--blob-port", type=int, default=12200)
    parser.add_argument("--queue-port", type=int, default=12201)
    parser.add_argument("--table-port", type=int, default=12202)
    parser.add_argument("--timeout", type=int, default=180, help="Startup/job timeout in seconds")
    parser.add_argument("--temp-dir", type=Path, help="Parent directory for an isolated temporary workspace")
    parser.add_argument("--keep-artifacts", action="store_true", help="Keep local logs, fixtures and emulator-only settings after the test")
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("This local process-group test currently supports macOS/Linux.")
    if args.timeout < 1:
        parser.error("--timeout must be positive.")
    ports = (args.port, args.blob_port, args.queue_port, args.table_port)
    if len(set(ports)) != 4 or any(port < 1024 or port > 65535 for port in ports):
        parser.error("Choose four distinct ports between 1024 and 65535.")
    tools = {name: shutil.which(name) for name in ("java", "func", "azurite")}
    if os.environ.get("JAVA_HOME"):
        preferred_java = Path(os.environ["JAVA_HOME"]) / "bin" / "java"
        if preferred_java.is_file():
            tools["java"] = str(preferred_java)
    if not all(tools.values()):
        parser.error("Java 21, Azure Functions Core Tools v4 (func), and Azurite must be available on PATH.")
    if not (args.package_dir / "host.json").is_file():
        parser.error("Run ./mvnw package first, or select the staged app with --package-dir.")
    args.package_dir = args.package_dir.resolve()
    if args.input:
        if not args.input.is_file() or args.input.suffix.lower() not in (".xlsx", ".xls"):
            parser.error("--input must be an existing .xlsx or .xls file.")
        if not 0 < args.input.stat().st_size <= 20 * 1024 * 1024:
            parser.error("--input must fit the isolated test host's default 20 MiB input limit.")
        args.input = args.input.resolve()
    if args.result_dir:
        if args.result_dir.exists() and (not args.result_dir.is_dir() or any(args.result_dir.iterdir())):
            parser.error("--result-dir must be new or empty; existing results are not overwritten.")
        args.result_dir.mkdir(parents=True, exist_ok=True)
        args.result_dir = args.result_dir.resolve()
    for port in ports:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    work = Path(tempfile.mkdtemp(prefix="excel2md-async-e2e-", dir=args.temp_dir))
    print("Isolated local E2E workspace:", work, flush=True)
    try:
        run(args, work, tools)
    finally:
        if args.keep_artifacts:
            print("Local test artifacts retained:", work, flush=True)
        else:
            shutil.rmtree(work)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, AssertionError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Local E2E failed: {type(error).__name__}: {error}", file=sys.stderr)
        print("Use --keep-artifacts to retain local logs for diagnosis.", file=sys.stderr)
        sys.exit(1)
