"""Refresh the compact channel index from Okko's public television guide."""
import gzip
import io
import json
from pathlib import Path
import re
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "apps/voicebridge/src/local/station/voicebridge/OkkoPublicChannelSnapshot.java"
ENDPOINT = "https://api.okko.tv/screenapi/v3/noauth/epgcollection/web/4"
ALIAS = "tvchannels_all"
MAX_PAGE_SIZE = 100
MAX_PAGES = 8
MAX_WIRE_BYTES = 4 * 1024 * 1024
MAX_DECODED_BYTES = 48 * 1024 * 1024
CHANNEL_ID = re.compile(r"[1-9][0-9]{0,17}")
PAGE_TOKEN = re.compile(r"[A-Za-z0-9_+/-]{1,128}={0,2}")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_page(opener, token):
    parameters = {
        "elementAlias": ALIAS,
        "currentMinusHour": 0,
        "maxResults": MAX_PAGE_SIZE,
        "programInTimeline": "false",
    }
    if token:
        parameters["pageToken"] = token
    request = Request(ENDPOINT + "?" + urlencode(parameters),
                      headers={"Accept": "application/json", "Accept-Encoding": "gzip"})
    with opener.open(request, timeout=10) as response:
        if response.status != 200 or response.headers.get_content_type() != "application/json":
            raise ValueError("Okko returned an unexpected response")
        body = response.read(MAX_WIRE_BYTES + 1)
        if len(body) > MAX_WIRE_BYTES:
            raise ValueError("Compressed guide page exceeds its limit")
        if response.headers.get("Content-Encoding") == "gzip":
            with gzip.GzipFile(fileobj=io.BytesIO(body)) as stream:
                body = stream.read(MAX_DECODED_BYTES + 1)
        if len(body) > MAX_DECODED_BYTES:
            raise ValueError("Decoded guide page exceeds its limit")
    return json.loads(body)


def read_catalog(opener):
    channels = {}
    visited = set()
    token = ""
    total = None
    for _ in range(MAX_PAGES):
        page = read_page(opener, token)
        if page.get("status") != 0 or page.get("authorized") is not False:
            raise ValueError("Expected the public Okko guide")
        section = page["element"]
        collection = section["collectionItems"]
        declared_total = collection["totalSize"]
        if section.get("alias") != ALIAS or not isinstance(declared_total, int):
            raise ValueError("Unexpected Okko guide section")
        if total is not None and total != declared_total:
            raise ValueError("Guide size changed during refresh")
        total = declared_total
        for row in collection["items"]:
            channel = row["element"]
            ident, name = channel["alias"], channel["name"].strip()
            if (channel.get("type") != "TV_CHANNEL" or not CHANNEL_ID.fullmatch(ident)
                    or not 1 <= len(name) <= 80 or any(not char.isprintable() for char in name)
                    or ident in channels):
                raise ValueError("Unexpected or duplicate Okko channel")
            channels[ident] = name
        token = section.get("pageInfo", {}).get("nextPageToken", "")
        if not token:
            if total != len(channels) or not channels:
                raise ValueError("Incomplete Okko guide")
            return channels
        if not PAGE_TOKEN.fullmatch(token) or token in visited:
            raise ValueError("Invalid Okko guide continuation")
        visited.add(token)
    raise ValueError("Okko guide exceeds the page limit")


def snapshot(channels):
    entries = "\n".join(
        "        {" + json.dumps(name, ensure_ascii=False) + ', "' + ident + '"},'
        for ident, name in sorted(channels.items(), key=lambda item: int(item[0]))
    )
    return ("""package local.station.voicebridge;

/** Generated from Okko's public TV guide by tools/update_okko_channels.py. */
final class OkkoPublicChannelSnapshot {
    static final String[][] ENTRIES = {
""" + entries + """
    };

    private OkkoPublicChannelSnapshot() { }
}
""")


def main():
    channels = read_catalog(build_opener(NoRedirect()))
    OUTPUT.write_text(snapshot(channels), encoding="utf-8", newline="\n")
    print(f"Refreshed {len(channels)} public Okko channels")


if __name__ == "__main__":
    main()
