#!/usr/bin/env python3
"""Post a release announcement to Discord through a channel webhook.

Usage: announce-discord.py <release-notes.md> <tag> <repo>
Reads the webhook from DISCORD_WEBHOOK_URL. With --dry-run, prints the payload instead.
"""
import json
import os
import re
import sys
import urllib.request

SITE = "https://nabrio.rajput.cc"
LOGO = f"{SITE}/img/nabrio-icon.png"
LIMIT = 4096  # Discord embed description limit


def build(notes: str, tag: str, repo: str) -> dict:
    lines = notes.strip().splitlines()
    title = f"Nabrio {tag.lstrip('v')}"
    if lines and lines[0].startswith("# "):
        title = lines.pop(0)[2:].strip()
    body = []
    for line in lines:
        heading = re.match(r"^#{2,6}\s+(.*)", line)
        body.append(f"**{heading.group(1).strip()}**" if heading else line)
    text = "\n".join(body).strip()
    release_url = f"https://github.com/{repo}/releases/tag/{tag}"
    footer = (
        f"\n\n**Download**\n"
        f"Android TV: {SITE}/tv\n"
        f"Phone: {SITE}/phone\n"
        f"Full notes: {release_url}"
    )
    room = LIMIT - len(footer)
    if len(text) > room:
        text = text[: room - 40].rsplit("\n", 1)[0] + "\n\n…more in the full notes."
    return {
        "username": "Nabrio Releases",
        "avatar_url": LOGO,
        "allowed_mentions": {"parse": []},
        "embeds": [{
            "title": f"🚀 {title} is out",
            "url": release_url,
            "description": text + footer,
            "color": 0xE50914,
            "thumbnail": {"url": LOGO},
        }],
    }


def main() -> None:
    args = [a for a in sys.argv[1:] if a != "--dry-run"]
    notes_path, tag, repo = args
    with open(notes_path, encoding="utf-8") as f:
        payload = build(f.read(), tag, repo)
    if "--dry-run" in sys.argv:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return
    # A secret pasted from some editors starts with an invisible byte-order mark, which breaks the URL.
    url = os.environ.get("DISCORD_WEBHOOK_URL", "").replace("\ufeff", "").strip()
    if not url:
        print("DISCORD_WEBHOOK_URL is not set; skipping the Discord announcement.")
        return
    request = urllib.request.Request(
        url + "?wait=true",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "nabrio-release"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        print(f"Discord announcement posted (HTTP {response.status}).")


if __name__ == "__main__":
    main()
