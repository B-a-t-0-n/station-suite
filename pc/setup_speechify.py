"""One-use, local Speechify key entry. No cloud requests or device access.

Run this script, open the printed loopback URL, and enter the key yourself.
The form expires after ten minutes. The key is saved outside the workspace at
%LOCALAPPDATA%/StationLab/speechify.json, never printed or placed in a URL.
"""

from __future__ import annotations

import csv
import html
import io
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit


LIFETIME_SECONDS = 600
MAX_BODY_BYTES = 16384
MAX_KEY_CHARACTERS = 4096
_KEY = re.compile(r"[A-Za-z0-9._~+/\-]+={0,2}\Z")
_SID = re.compile(r"S-1-5-21-(?:[0-9]+-){3}[0-9]+\Z")


class Rejected(ValueError):
    """A deliberately detail-free validation failure."""


def validate_headers(headers, origin: str, *, post: bool) -> None:
    """Require the exact loopback authority and same-origin form submissions."""
    expected_host = urlsplit(origin).netloc
    if headers.get_all("Host", []) != [expected_host]:
        raise Rejected()
    # Browsers supply Origin for ordinary POSTs. Referer is a fallback for
    # clients that omit it; conflicting or duplicate headers are never accepted.
    origins = headers.get_all("Origin", [])
    referers = headers.get_all("Referer", [])
    if len(origins) > 1 or len(referers) > 1:
        raise Rejected()
    if origins and origins != [origin]:
        raise Rejected()
    if referers:
        try:
            reference = urlsplit(referers[0])
        except ValueError:
            raise Rejected() from None
        if (reference.scheme != "http" or reference.netloc != expected_host
                or reference.path != "/" or reference.query or reference.fragment):
            raise Rejected()
    if post and not (origins or referers):
        raise Rejected()
    if headers.get("Sec-Fetch-Site", "same-origin") not in ("same-origin", "none"):
        raise Rejected()


def content_length(headers) -> int:
    if headers.get_all("Transfer-Encoding", []):
        raise Rejected()
    lengths = headers.get_all("Content-Length", [])
    if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,5}", lengths[0]):
        raise Rejected()
    length = int(lengths[0])
    if not 1 <= length <= MAX_BODY_BYTES:
        raise Rejected()
    content_types = headers.get_all("Content-Type", [])
    if len(content_types) != 1 or content_types[0].lower() not in (
            "application/x-www-form-urlencoded",
            "application/x-www-form-urlencoded; charset=utf-8"):
        raise Rejected()
    return length


def parse_submission(body: bytes, token: str) -> str:
    if not 1 <= len(body) <= MAX_BODY_BYTES:
        raise Rejected()
    try:
        encoded = body.decode("ascii")
        if re.search(r"%(?![0-9a-fA-F]{2})", encoded):
            raise Rejected()
        fields = parse_qs(encoded, keep_blank_values=True, strict_parsing=True,
                          max_num_fields=2, encoding="utf-8", errors="strict")
    except (UnicodeError, ValueError):
        raise Rejected() from None
    if set(fields) != {"csrf", "api_key"} or any(len(v) != 1 for v in fields.values()):
        raise Rejected()
    if not secrets.compare_digest(fields["csrf"][0].encode("utf-8"), token.encode("ascii")):
        raise Rejected()
    key = fields["api_key"][0]
    # Accept bearer-token characters without guessing a vendor-specific prefix.
    # Whitespace, control characters, HTML and pasted multiline data are refused.
    if not 20 <= len(key) <= MAX_KEY_CHARACTERS or not _KEY.fullmatch(key):
        raise Rejected()
    return key


def _current_user_sid() -> str:
    result = subprocess.run(["whoami.exe", "/user", "/fo", "csv", "/nh"],
                            capture_output=True, timeout=10, check=True,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    # The SID is ASCII even when the account name uses a local code page.
    rows = list(csv.reader(io.StringIO(result.stdout.decode("ascii", errors="replace"))))
    if len(rows) != 1 or len(rows[0]) != 2 or not _SID.fullmatch(rows[0][1]):
        raise RuntimeError("Cannot determine current Windows user")
    return rows[0][1]


def _restrict_file(path: Path) -> None:
    """Protect the empty temporary file before any credential bytes are written."""
    if os.name == "nt":
        sid = _current_user_sid()
        subprocess.run(["icacls.exe", str(path), "/inheritance:r", "/grant:r",
                        f"*{sid}:F", "*S-1-5-18:F"],
                       capture_output=True, timeout=15, check=True,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    else:
        os.chmod(path, 0o600)


def save_key(key: str, target: Path) -> None:
    """Atomically replace a dedicated private file; subprocess arguments omit key."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.parent / (".speechify-" + secrets.token_hex(16) + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        _restrict_file(temporary)  # Fail closed while the file is still empty.
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = None
            stream.write(json.dumps({"speechify_api_key": key}, ensure_ascii=True).encode("utf-8"))
            stream.write(b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        # Only remove this attempt's random temporary file, never another key file.
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def save_failure_code(error: Exception) -> str:
    """Closed categories only: exception text/commands may contain private data."""
    if isinstance(error, PermissionError):
        return "SAVE_PERMISSION_DENIED"
    if isinstance(error, subprocess.TimeoutExpired):
        return "SAVE_ACCESS_SETUP_TIMEOUT"
    if isinstance(error, subprocess.CalledProcessError):
        return "SAVE_ACCESS_SETUP_FAILED"
    if isinstance(error, OSError):
        return "SAVE_IO_FAILED"
    return "SAVE_FAILED"


def page(title: str, content: str, nonce: str) -> bytes:
    return ("<!doctype html><html lang='ru'><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            f"<title>{html.escape(title)}</title><style nonce='{nonce}'>"
            "*{box-sizing:border-box}body{margin:0;background:#f6f3fb;color:#282233;"
            "font:16px system-ui,sans-serif;min-height:100vh;display:grid;place-items:center;"
            "padding:24px}main{background:#fff;width:min(100%,520px);padding:32px;"
            "border-radius:28px;box-shadow:0 16px 60px #55427813}h1{font-size:28px;"
            "letter-spacing:-.6px;margin:12px 0}p{line-height:1.6;color:#746b82}"
            ".tag{color:#7950c7;font-weight:650;font-size:13px}label{display:block;"
            "font-weight:600;margin:24px 0 8px}input[type=password]{width:100%;"
            "font:inherit;padding:15px;border:1px solid #dcd3eb;border-radius:14px;"
            "background:#fbf9ff;outline:none}input:focus{border-color:#8757cf;"
            "box-shadow:0 0 0 3px #8757cf20}button{width:100%;margin-top:18px;"
            "padding:15px;border:0;border-radius:14px;background:#8052c7;color:white;"
            "font:600 16px system-ui;cursor:pointer}small{display:block;margin-top:18px;"
            "color:#8a8196;line-height:1.5}</style><main>"
            "<div class='tag'>STATION VOICE LAB · SPEECHIFY</div>"
            f"<h1>{html.escape(title)}</h1>{content}</main></html>").encode("utf-8")


class SetupServer(HTTPServer):
    def __init__(self, target: Path):
        # An OS-assigned port avoids replacing or exposing an existing service.
        super().__init__(("127.0.0.1", 0), SetupHandler)
        self.origin = f"http://127.0.0.1:{self.server_port}"
        self.token = secrets.token_urlsafe(32)
        self.nonce = secrets.token_urlsafe(24)
        self.deadline = time.monotonic() + LIFETIME_SECONDS
        self.target = target
        self.saved = False
        self.timeout = 0.5

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(3)
        return connection, address

    def handle_error(self, request, client_address):
        # Tracebacks may contain request data. No default request logging.
        pass


class SetupHandler(BaseHTTPRequestHandler):
    server: SetupServer
    protocol_version = "HTTP/1.0"
    server_version = "LocalKeySetup"
    sys_version = ""

    def log_message(self, format, *args):
        pass

    def send_error(self, code, message=None, explain=None):
        self._reply(code, "Запрос отклонён", "<p>Откройте локальную страницу заново.</p>")

    def _reply(self, code: int, title: str, content: str) -> None:
        body = page(title, content, self.server.nonce)
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        # no-referrer makes a normal HTML form POST send Origin:null, which the
        # strict same-origin check correctly rejects. Keep internal Origin.
        self.send_header("Referrer-Policy", "same-origin")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'nonce-" + self.server.nonce
                         + "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(body)

    def _active(self) -> bool:
        if self.server.saved or time.monotonic() >= self.server.deadline:
            self._reply(410, "Сеанс завершён", "<p>Для повторного ввода запустите настройку заново.</p>")
            return False
        return True

    def do_GET(self):
        if not self._active():
            return
        try:
            validate_headers(self.headers, self.server.origin, post=False)
        except Rejected:
            self.send_error(403)
            return
        if self.path != "/":
            self.send_error(404)
            return
        self._reply(200, "Подключить голос", "<p>Вставьте API-ключ из панели разработчика Speechify. "
                    "Он сохранится только в закрытом файле на этом ПК.</p>"
                    "<form method='post' action='/save' autocomplete='off'>"
                    f"<input type='hidden' name='csrf' value='{self.server.token}'>"
                    "<label for='api_key'>API-ключ Speechify</label>"
                    f"<input type='password' id='api_key' name='api_key' minlength='20' maxlength='{MAX_KEY_CHARACTERS}' "
                    "required autocomplete='off' spellcheck='false' autocapitalize='none'>"
                    "<button type='submit'>Сохранить ключ</button></form>"
                    "<small>Страница работает только на этом компьютере и закроет приём после сохранения. "
                    "Сеанс действует 10 минут. Запросов к Speechify здесь нет.</small>")

    def do_POST(self):
        if not self._active():
            return
        try:
            validate_headers(self.headers, self.server.origin, post=True)
            if self.path != "/save":
                raise Rejected()
            length = content_length(self.headers)
            body = self.rfile.read(length)
            if len(body) != length:
                raise Rejected()
            key = parse_submission(body, self.server.token)
            if time.monotonic() >= self.server.deadline:
                raise Rejected()
        except (Rejected, OSError, socket.timeout):
            self.send_error(403)
            return
        try:
            save_key(key, self.server.target)
        except Exception as error:
            code = save_failure_code(error)
            print(json.dumps({"event": "save_failed", "error": code}), flush=True)
            self._reply(500, "Ключ не сохранён", "<p>Не удалось защитить или записать локальный файл. "
                        "Настройка не отправляла ключ в сеть.</p>"
                        f"<small>Код ошибки: <code>{code}</code></small>")
            return
        finally:
            key = None
            body = None
        self.server.saved = True
        self._reply(200, "Ключ сохранён", "<p>Настройка завершена. Эту вкладку можно закрыть.</p>"
                    "<small>Ключ не проверялся запросом к API и не передавался на Станцию.</small>")


def main() -> None:
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        raise SystemExit("LOCALAPPDATA is unavailable; no key was saved")
    target = Path(local) / "StationLab" / "speechify.json"
    with SetupServer(target) as server:
        print(json.dumps({"url": server.origin + "/", "expiresInSeconds": LIFETIME_SECONDS,
                          "apiCalls": 0, "deviceAccess": False}), flush=True)
        try:
            while not server.saved and time.monotonic() < server.deadline:
                server.handle_request()
        except KeyboardInterrupt:
            pass
        print(json.dumps({"saved": server.saved,
                          "expired": time.monotonic() >= server.deadline}), flush=True)


if __name__ == "__main__":
    main()
