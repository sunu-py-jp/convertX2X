#!/usr/bin/env python3
"""Run isolated HTTP/Queue + direct JSON Queue end-to-end tests after ./mvnw package."""

import argparse
import base64
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

from async_settings import derive_settings

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_SOURCE = r"""
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
class CreateFixtures {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    Color[] colors = {Color.RED, Color.BLUE};
    try (PDDocument doc = new PDDocument()) {
      for (Color color : colors) {
        PDPage page = new PDPage(new PDRectangle(400, 200)); doc.addPage(page);
        try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
          out.setNonStrokingColor(color); out.addRect(0, 0, 400, 200); out.fill();
        }
      }
      doc.save(root.resolve("example.pdf").toFile());
    }
    try (XMLSlideShow doc = new XMLSlideShow()) {
      doc.setPageSize(new Dimension(400, 200));
      for (Color color : colors) {
        var shape = doc.createSlide().createAutoShape(); shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(0, 0, 400, 200)); shape.setFillColor(color); shape.setLineColor(color);
      }
      try (var out = Files.newOutputStream(root.resolve("example.pptx"))) { doc.write(out); }
    }
    try (HSLFSlideShow doc = new HSLFSlideShow()) {
      doc.setPageSize(new Dimension(400, 200));
      for (Color color : colors) {
        var shape = doc.createSlide().createAutoShape(); shape.setShapeType(ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(0, 0, 400, 200)); shape.setFillColor(color); shape.setLineColor(color);
      }
      try (var out = Files.newOutputStream(root.resolve("example.ppt"))) { doc.write(out); }
    }
  }
}
"""

DIRECT_QUEUE_SOURCE = r"""
import com.azure.storage.blob.*;
import com.azure.storage.queue.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

class DirectQueueE2E {
  private static final ObjectMapper JSON = new ObjectMapper();
  private record TestCase(String name, String extension, boolean images, boolean crossAccount,
                          String format, Integer page) {}

  public static void main(String[] args) {
    try {
      run(Path.of(args[0]));
    } catch (AssertionError failure) {
      System.err.println("Direct Queue E2E assertion failed: " + failure.getMessage());
      System.exit(1);
    } catch (Exception failure) {
      // Never print SDK exception messages, which can contain connection information.
      System.err.println("Direct Queue E2E failed (" + failure.getClass().getSimpleName() + ").");
      System.exit(1);
    }
  }

  private static BlobServiceClient storage(String setting) {
    String connection = System.getenv(setting);
    check(connection != null && !connection.isBlank(), "Required test storage setting is missing");
    return new BlobServiceClientBuilder().connectionString(connection).buildClient();
  }

  private static void run(Path work) throws Exception {
    BlobServiceClient control = storage("CONVERSION_STORAGE_CONNECTION_STRING");
    BlobServiceClient source = storage("CONVERSION_INPUT_STORAGE_SOURCE");
    BlobServiceClient archive = storage("CONVERSION_OUTPUT_STORAGE_ARCHIVE");
    QueueClient queue = new QueueClientBuilder()
        .connectionString(System.getenv("CONVERSION_STORAGE_CONNECTION_STRING"))
        .queueName("conversion-jobs").messageEncoding(QueueMessageEncoding.BASE64).buildClient();
    List<Map<String, Object>> reports = new ArrayList<>();
    List<TestCase> cases = List.of(
        new TestCase("pdf", "pdf", false, false, "png", null),
        new TestCase("pptx", "pptx", false, false, "png", null),
        new TestCase("ppt", "ppt", false, false, "png", null),
        new TestCase("cross-account", "pdf", false, true, "png", null),
        new TestCase("images-pdf", "pdf", true, false, "png", null),
        new TestCase("images-pptx", "pptx", true, false, "png", null),
        new TestCase("images-ppt", "ppt", true, false, "png", null),
        new TestCase("images-jpeg-page2", "pdf", true, false, "jpeg", 2),
        new TestCase("images-cross-account", "pdf", true, true, "png", null));
    for (TestCase test : cases) {
      String testCase = test.name();
      boolean crossAccount = test.crossAccount();
      String extension = test.extension();
      int pages = test.page() == null ? 2 : 1;
      int width = test.page() == null ? 533 : 320;
      int height = test.page() == null ? 267 : 160;
      String id = UUID.randomUUID().toString();
      String inputContainer = "direct-input-" + testCase;
      String outputContainer = "direct-output-" + testCase;
      String prefix = "customers/e2e/" + testCase;
      BlobServiceClient inputStorage = crossAccount ? source : control;
      BlobServiceClient outputStorage = crossAccount ? archive : control;
      check(!outputStorage.getBlobContainerClient(outputContainer).exists(), "Output must start absent");
      ObjectNode request = JSON.createObjectNode();
      request.put("version", 1).put("jobId", id);
      ObjectNode input = request.putObject("input");
      input.put("container", inputContainer).put("blobName", "nested/incoming/example." + extension);
      ObjectNode output = request.putObject("output");
      output.put("container", outputContainer).put("prefix", prefix);
      if (test.images()) output.put("mode", "images");
      if (crossAccount) {
        input.put("storage", "source");
        output.put("storage", "archive");
      }
      // Otherwise omit filename/options to exercise blob basename + source 96 dpi defaults.
      if (test.page() != null) {
        request.putObject("options").put("format", test.format()).put("width", width).put("page", test.page());
      }
      Path requestFile = work.resolve("direct-" + testCase + "-request.json");
      Files.writeString(requestFile, JSON.writeValueAsString(request));
      BlobClient statusBlob = control.getBlobContainerClient("conversion-jobs").getBlobClient(id + "/status.json");
      check(!statusBlob.exists(), "Direct producer must not need a pre-created status");
      long started = System.nanoTime();
      check(QueueProducer.publish(work.resolve("example." + extension), requestFile).equals(id),
          "Producer returned an unexpected job ID");
      check(inputStorage.getBlobContainerClient(inputContainer).getBlobClient(input.path("blobName").asText()).exists(),
          "Input was not uploaded to its selected account/container/path");
      JsonNode status = awaitTerminal(statusBlob);
      check(status.path("job").path("status").asText().equals("succeeded"),
          testCase + " did not succeed: " + status.path("job").path("errorCode").asText());
      check(status.path("job").path("id").asText().equals(id), "Status has an unexpected job ID");
      check(status.path("job").path("pageCount").asInt() == pages, "Unexpected output page count");
      check(status.path("request").isObject(), "Status must retain the normalized request");
      JsonNode result = status.path("result");
      check(result.path("storage").asText().equals(crossAccount ? "archive" : "default"), "Incorrect output storage alias");
      check(result.path("container").asText().equals(outputContainer), "Incorrect output container");
      check(result.path("contentType").asText().equals(test.images() ? "application/json" : "application/zip"),
          "Result metadata has the wrong content type");
      check(result.path("pageCount").asInt() == pages, "Result metadata must include output page count");
      String resultName = result.path("blobName").asText();
      String resultRoot = prefix + "/" + id + "/results/";
      check(resultName.startsWith(resultRoot), "Result is outside the requested prefix/job path");
      String[] tail = resultName.substring(resultRoot.length()).split("/", 2);
      check(tail.length == 2 && UUID.fromString(tail[0]).toString().equals(tail[0]), "Missing unique attempt ID");
      check(tail[1].equals(result.path("filename").asText()), "Result path must end with its filename");
      BlobContainerClient results = outputStorage.getBlobContainerClient(outputContainer);
      check(results.getAccessPolicy().getBlobAccessType() == null, "Worker-created output must be private");
      byte[] resultBytes = results.getBlobClient(resultName).downloadContent().toBytes();
      long outputBytes;
      Set<String> expectedBlobs = new HashSet<>();
      expectedBlobs.add(resultName);
      if (test.images()) {
        check(result.path("filename").asText().equals("manifest.json"), "Images result must point to manifest.json");
        outputBytes = verifyManifest(resultBytes, id, results, resultName, crossAccount ? "archive" : "default",
            test.format(), test.page(), pages, width, height, expectedBlobs);
        Files.write(work.resolve("direct-" + testCase + "-manifest.json"), resultBytes);
      } else {
        verifyZip(resultBytes);
        outputBytes = resultBytes.length;
        Files.write(work.resolve("direct-" + testCase + "-result.zip"), resultBytes);
      }
      checkResultBlobs(results, resultRoot, expectedBlobs, test.images());
      if (crossAccount) {
        check(!control.getBlobContainerClient(inputContainer).exists(), "Cross-account input appeared in control account");
        check(!control.getBlobContainerClient(outputContainer).exists(), "Cross-account output appeared in control account");
        check(!source.getBlobContainerClient(outputContainer).exists(), "Output appeared in the input account");
      }

      // Queue Storage is at-least-once. Two identical terminal redeliveries must preserve the result.
      awaitEmpty(queue);
      String statusEtag = statusBlob.getProperties().getETag();
      byte[] savedStatus = statusBlob.downloadContent().toBytes();
      queue.sendMessage(JSON.writeValueAsString(request));
      queue.sendMessage(JSON.writeValueAsString(request));
      awaitEmpty(queue);
      check(statusBlob.getProperties().getETag().equals(statusEtag), "Duplicate delivery rewrote terminal status");
      check(Arrays.equals(savedStatus, statusBlob.downloadContent().toBytes()), "Duplicate delivery changed the job");
      checkResultBlobs(results, resultRoot, expectedBlobs, test.images());
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("transport", "direct-queue"); report.put("case", testCase); report.put("jobId", id);
      report.put("mode", test.images() ? "images" : "zip"); report.put("format", test.format());
      report.put("outputBytes", outputBytes); report.put("pages", pages);
      report.put("width", width); report.put("height", height); report.put("duplicatePreserved", true);
      report.put("elapsedSeconds", Math.round((System.nanoTime() - started) / 10_000_000.0) / 100.0);
      reports.add(report);
    }
    Files.writeString(work.resolve("direct-queue-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
    System.out.println(JSON.writeValueAsString(reports));
  }

  private static JsonNode awaitTerminal(BlobClient statusBlob) throws Exception {
    long deadline = System.nanoTime() + 90_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (statusBlob.exists()) {
        JsonNode status = JSON.readTree(statusBlob.downloadContent().toBytes());
        if (Set.of("succeeded", "failed").contains(status.path("job").path("status").asText())) return status;
      }
      Thread.sleep(750);
    }
    throw new AssertionError("Timed out polling the status Blob");
  }

  private static void awaitEmpty(QueueClient queue) throws Exception {
    long deadline = System.nanoTime() + 90_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (queue.getProperties().getApproximateMessagesCount() == 0) return;
      Thread.sleep(750);
    }
    throw new AssertionError("Direct Queue messages were not acknowledged");
  }

  private static void verifyZip(byte[] bytes) throws Exception {
    int pages = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        pages++;
        check(entry.getName().equals(String.format("page-%04d.png", pages)), "Unexpected ZIP entry");
        byte[] png = zip.readAllBytes();
        check(png.length >= 24 && png[0] == (byte) 137 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "Expected PNG");
        ByteBuffer dimensions = ByteBuffer.wrap(png, 16, 8);
        check(dimensions.getInt() == 533 && dimensions.getInt() == 267, "Expected 400x200 points rasterized at 96 dpi (533x267)");
      }
    }
    check(pages == 2, "Expected two PNG entries in the ZIP");
  }

  private static long verifyManifest(byte[] bytes, String jobId, BlobContainerClient results, String manifestName,
      String storageAlias, String format, Integer selectedPage, int pages, int width, int height,
      Set<String> expectedBlobs) throws Exception {
    JsonNode manifest = JSON.readTree(bytes);
    check(manifest.path("version").asInt() == 1, "Manifest version must be 1");
    check(manifest.path("jobId").asText().equals(jobId), "Manifest has an unexpected job ID");
    check(manifest.path("mode").asText().equals("images"), "Manifest mode must be images");
    check(manifest.path("pageCount").asInt() == pages, "Manifest pageCount must count emitted images");
    check(manifest.path("images").isArray() && manifest.path("images").size() == pages, "Unexpected manifest image count");
    String attemptRoot = manifestName.substring(0, manifestName.lastIndexOf('/') + 1);
    check(results.getBlobClient(manifestName).getProperties().getContentType().equals("application/json"),
        "Manifest Blob has the wrong content type");
    long outputBytes = bytes.length;
    int index = 0;
    for (JsonNode image : manifest.path("images")) {
      int page = selectedPage == null ? ++index : selectedPage;
      String filename = String.format(Locale.ROOT, "page-%04d.%s", page, format);
      String blobName = image.path("blobName").asText();
      check(image.path("page").asInt() == page, "Manifest must retain the original page number");
      check(image.path("storage").asText().equals(storageAlias), "Manifest image has the wrong storage alias");
      check(image.path("container").asText().equals(results.getBlobContainerName()), "Manifest image has the wrong container");
      check(image.path("filename").asText().equals(filename), "Manifest image filename is incorrect");
      check(blobName.equals(attemptRoot + filename), "Every image must share the manifest's attempt directory");
      check(expectedBlobs.add(blobName), "Manifest repeats an image Blob");
      String contentType = "image/" + format;
      check(image.path("contentType").asText().equals(contentType), "Manifest image has the wrong content type");
      BlobClient imageBlob = results.getBlobClient(blobName);
      check(imageBlob.exists(), "Manifest image Blob does not exist");
      check(imageBlob.getProperties().getContentType().equals(contentType), "Image Blob has the wrong content type");
      byte[] data = imageBlob.downloadContent().toBytes();
      check(image.path("sizeBytes").isIntegralNumber() && image.path("sizeBytes").asLong() == data.length && data.length > 0,
          "Manifest image sizeBytes does not match the Blob");
      if (format.equals("png")) {
        check(data.length >= 24 && data[0] == (byte) 137 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G', "Expected PNG image");
        ByteBuffer dimensions = ByteBuffer.wrap(data, 16, 8);
        check(dimensions.getInt() == width && dimensions.getInt() == height, "PNG dimensions differ from requested size");
      } else {
        check(data.length > 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8, "Expected JPEG image");
      }
      var decoded = ImageIO.read(new ByteArrayInputStream(data));
      check(decoded != null && decoded.getWidth() == width && decoded.getHeight() == height, "Image could not decode at expected size");
      // Both fixture pages are solid colors; page 2 must contain the blue page, including JPEG output.
      int pixel = decoded.getRGB(width / 2, height / 2);
      int red = (pixel >>> 16) & 255, blue = pixel & 255;
      check(page == 1 ? red > 200 && blue < 50 : blue > 200 && red < 50, "Wrong source page was rendered");
      outputBytes += data.length;
    }
    return outputBytes;
  }

  private static void checkResultBlobs(BlobContainerClient results, String resultRoot,
      Set<String> expected, boolean images) {
    Set<String> actual = new HashSet<>();
    for (var blob : results.listBlobs()) {
      if (blob.getName().startsWith(resultRoot)) {
        actual.add(blob.getName());
        if (images) check(!blob.getName().toLowerCase(Locale.ROOT).endsWith(".zip"), "Images mode must not write a ZIP");
      }
    }
    check(actual.equals(expected), "Unexpected/missing output Blobs or a duplicate attempt was created");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
"""


def request(url: str, data: bytes | None = None) -> tuple[int, dict, bytes]:
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/octet-stream"})
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def wait_until(check, processes, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(process.poll() is not None for process in processes):
            raise RuntimeError("A test service exited before becoming ready; inspect the logs.")
        try:
            if check():
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise TimeoutError("The test service did not become ready.")


def port_ready(port):
    with socket.create_connection(("127.0.0.1", port), timeout=1):
        return True


def stop(process):
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=7073)
    parser.add_argument("--blob-port", type=int, default=11000)
    parser.add_argument("--queue-port", type=int, default=11001)
    parser.add_argument("--table-port", type=int, default=11002)
    parser.add_argument("--storage-integration-test", action="store_true",
                        help="Also run the optional Java storage integration test against this emulator.")
    args = parser.parse_args()
    for tool in ("azurite", "func", "java", "javac"):
        if not shutil.which(tool):
            parser.error(f"Required command not found: {tool}")
    package = ROOT / "target" / "azure-functions" / "slide2image-local"
    if not (package / "host.json").exists():
        parser.error("Build the Functions package with ./mvnw package first.")
    for port in (args.port, args.blob_port, args.queue_port, args.table_port):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    work = Path(tempfile.mkdtemp(prefix="slide2image-async-e2e-"))
    print(f"Isolated E2E workspace: {work}", flush=True)
    stage = work / "functions"
    shutil.copytree(package, stage)
    accounts = {name: base64.b64encode(secrets.token_bytes(64)).decode("ascii")
                for name in ("conversiontest", "conversioninput", "conversionoutput")}

    def storage_connection(account):
        return (f"DefaultEndpointsProtocol=http;AccountName={account};AccountKey={accounts[account]};"
                f"BlobEndpoint=http://127.0.0.1:{args.blob_port}/{account};"
                f"QueueEndpoint=http://127.0.0.1:{args.queue_port}/{account};"
                f"TableEndpoint=http://127.0.0.1:{args.table_port}/{account};")

    connection = storage_connection("conversiontest")
    settings = derive_settings(connection)
    settings.update({"FUNCTIONS_WORKER_RUNTIME": "java", "AzureWebJobsStorage": connection,
                     "JAVA_OPTS": "-Djava.awt.headless=true", "AZURE_CORE_COLLECT_TELEMETRY": "false",
                     "CONVERSION_INPUT_STORAGE_SOURCE": storage_connection("conversioninput"),
                     "CONVERSION_OUTPUT_STORAGE_ARCHIVE": storage_connection("conversionoutput")})
    local_settings = stage / "local.settings.json"
    local_settings.write_text(json.dumps({"IsEncrypted": False, "Values": settings}), encoding="utf-8")
    local_settings.chmod(0o600)
    fixture = work / "CreateFixtures.java"
    fixture.write_text(FIXTURE_SOURCE, encoding="utf-8")
    subprocess.run(["java", "-Djava.awt.headless=true", "--class-path", str(stage / "lib" / "*"),
                    str(fixture), str(work)], check=True, stdout=subprocess.DEVNULL)
    direct_source = work / "DirectQueueE2E.java"
    direct_source.write_text(DIRECT_QUEUE_SOURCE, encoding="utf-8")
    direct_classes = work / "java-classes"
    direct_classes.mkdir()
    subprocess.run(["javac", "--class-path", str(stage / "lib" / "*"), "-d", str(direct_classes),
                    str(ROOT / "examples" / "QueueProducer.java"), str(direct_source)], check=True)

    children = []
    log_handles = []
    try:
        azurite_log = (work / "azurite.log").open("w")
        log_handles.append(azurite_log)
        emulator_env = os.environ.copy()
        emulator_env["AZURITE_ACCOUNTS"] = ";".join(f"{name}:{key}" for name, key in accounts.items())
        children.append(subprocess.Popen([
            "azurite", "--location", str(work / "storage"), "--silent", "--disableTelemetry",
            "--skipApiVersionCheck", "--blobHost", "127.0.0.1", "--queueHost", "127.0.0.1",
            "--tableHost", "127.0.0.1", "--blobPort", str(args.blob_port),
            "--queuePort", str(args.queue_port), "--tablePort", str(args.table_port)],
            env=emulator_env, stdout=azurite_log, stderr=subprocess.STDOUT, start_new_session=True))
        wait_until(lambda: port_ready(args.table_port), children, timeout=20)

        if args.storage_integration_test:
            test_env = os.environ.copy()
            test_env["CONVERSION_TEST_STORAGE_CONNECTION_STRING"] = connection
            test_env["CONVERSION_TEST_INPUT_STORAGE_CONNECTION_STRING"] = settings["CONVERSION_INPUT_STORAGE_SOURCE"]
            test_env["CONVERSION_TEST_OUTPUT_STORAGE_CONNECTION_STRING"] = settings["CONVERSION_OUTPUT_STORAGE_ARCHIVE"]
            subprocess.run([str(ROOT / "mvnw"), "-B", "-ntp", "-Dtest=AzureJobStoreIntegrationTest", "test"],
                           cwd=ROOT, env=test_env, check=True)

        function_log = (work / "functions.log").open("w")
        log_handles.append(function_log)
        function_env = os.environ.copy()
        function_env.update(settings)
        if not function_env.get("JAVA_HOME"):
            java_settings = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                                           capture_output=True, text=True, check=True)
            for line in java_settings.stderr.splitlines():
                key, separator, value = line.strip().partition(" = ")
                if separator and key == "java.home":
                    function_env["JAVA_HOME"] = value
                    break
            if not function_env.get("JAVA_HOME"):
                raise RuntimeError("JAVA_HOME could not be determined.")
        children.append(subprocess.Popen(["func", "start", "--port", str(args.port)], cwd=stage,
                        env=function_env, stdout=function_log, stderr=subprocess.STDOUT, start_new_session=True))
        base = f"http://127.0.0.1:{args.port}"
        wait_until(lambda: request(base + "/api/jobs/invalid")[0] == 400, children)

        reports = []
        for extension in ("pdf", "pptx", "ppt"):
            source = work / f"example.{extension}"
            started = time.monotonic()
            code, headers, body = request(base + f"/api/jobs?filename=example.{extension}&width=320", source.read_bytes())
            assert code == 202, (code, body.decode("utf-8", errors="replace"))
            accepted = json.loads(body)
            assert accepted["job"]["status"] == "queued"
            status_path = accepted["statusUrl"]
            assert headers.get("Location") == status_path
            status = accepted
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                code, _, body = request(base + status_path)
                assert code == 200, (code, body)
                status = json.loads(body)
                if status["job"]["status"] in ("succeeded", "failed"):
                    break
                time.sleep(1)
            assert status["job"]["status"] == "succeeded", status
            code, headers, output = request(base + status["resultUrl"])
            assert code == 200 and headers.get("Content-Type", "").startswith("application/zip"), headers
            with zipfile.ZipFile(io.BytesIO(output)) as archive:
                assert archive.namelist() == ["page-0001.png", "page-0002.png"]
                for name in archive.namelist():
                    png = archive.read(name)
                    assert png[:8] == b"\x89PNG\r\n\x1a\n"
                    assert struct.unpack(">II", png[16:24]) == (320, 160)
            (work / f"{extension}-result.zip").write_bytes(output)
            reports.append({"format": extension, "inputBytes": source.stat().st_size,
                            "outputBytes": len(output), "pages": status["job"]["pageCount"],
                            "elapsedSeconds": round(time.monotonic() - started, 2)})
            print(f"PASS {extension}: HTTP 202 -> Queue worker -> succeeded -> ZIP with two 320x160 PNGs", flush=True)
        direct_log = (work / "direct-queue.log").open("w")
        log_handles.append(direct_log)
        direct = subprocess.run(["java", "-Djava.awt.headless=true", "--class-path",
                                 os.pathsep.join((str(direct_classes), str(stage / "lib" / "*"))),
                                 "DirectQueueE2E", str(work)],
                                env=function_env, stdout=direct_log, stderr=subprocess.STDOUT, text=True)
        if direct.returncode:
            raise RuntimeError(f"Direct Queue E2E failed; inspect {work / 'direct-queue.log'}")
        # Azure SDK/JVM diagnostics may also use stdout; read the helper's dedicated JSON artifact.
        direct_reports = json.loads((work / "direct-queue-report.json").read_text(encoding="utf-8"))
        reports.extend(direct_reports)
        for report in direct_reports:
            if report["mode"] == "images":
                code, _, body = request(base + f"/api/jobs/{report['jobId']}")
                assert code == 200, (code, body)
                status = json.loads(body)
                assert status["job"]["status"] == "succeeded"
                assert "result" not in status and "request" not in status
                assert status["resultUrl"] == f"/api/jobs/{report['jobId']}/result"
                code, headers, manifest_body = request(base + status["resultUrl"])
                assert code == 200 and headers.get("Content-Type", "").startswith("application/json"), headers
                expected_manifest = json.loads((work / f"direct-{report['case']}-manifest.json").read_text(encoding="utf-8"))
                assert json.loads(manifest_body) == expected_manifest
                assert int(headers["X-Page-Count"]) == report["pages"]
                assert 'filename="manifest.json"' in headers.get("Content-Disposition", "")
                report["httpManifestMatched"] = True
            print(f"PASS {report['case']}: direct JSON Queue -> status Blob -> private {report['mode']} "
                  f"with {report['pages']} {report['width']}x{report['height']} {report['format'].upper()} image(s); "
                  f"duplicate preserved" + ("; HTTP manifest matched" if report["mode"] == "images" else ""), flush=True)
        (work / "report.json").write_text(json.dumps(reports, indent=2), encoding="utf-8")
        print(json.dumps(reports, indent=2), flush=True)
    finally:
        for process in reversed(children):
            stop(process)
        for handle in log_handles:
            handle.close()
        local_settings.unlink(missing_ok=True)
        print("Stopped isolated Functions host and Azurite.", flush=True)


if __name__ == "__main__":
    main()
