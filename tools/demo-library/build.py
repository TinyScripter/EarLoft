#!/usr/bin/env python3
"""Builds the made-up demo library from catalog.py.

Usage: build.py [outdir]   (default: build/demo-library in the repository)

Output:
  <outdir>/device/Music/<Artist>/<Album>/NN - <Title>.mp3       tagged, embedded cover, cover.jpg next to it
  <outdir>/device/Audiobooks/<Author>/[<Series>/<N> – ]<Title> {<Narrator>}/
                                                               m4b with chapter marks or one MP3 per chapter,
                                                               cover.jpg, desc.txt, metadata.opf
  <outdir>/feeds/<slug>/feed.xml, cover.jpg, NN.mp3            RSS feeds served by serve.py
  <outdir>/feeds/demo-podcasts.opml                            all feeds for the OPML import

The audio is silence with realistic durations (mono, low bitrate), so the whole library stays small.
Podcast release dates count back from the moment the feeds are built (the newest episode is from today).
Existing files are kept; delete <outdir> to rebuild everything.
"""
import datetime as dt
import email.utils
import os
import re
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog  # noqa: E402
import covers  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
FEED_BASE = os.environ.get("DEMO_FEED_BASE", "http://127.0.0.1:8765")


def safe(name):
    """File-system safe name that keeps the text readable (no slashes or reserved characters)."""
    return re.sub(r'[\\/:*?"<>|]', "", name).strip()


def log(msg):
    print(msg, flush=True)


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def silent_mp3(path, seconds, cover_path, tags):
    """Silent MP3 (mono, 22.05 kHz, lowest VBR quality) with ID3v2.3 tags and the cover as attached picture."""
    if os.path.exists(path):
        return
    args = ["-f", "lavfi", "-t", str(seconds), "-i", "anullsrc=r=22050:cl=mono"]
    if cover_path:
        args += ["-i", cover_path, "-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic",
                 "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"]
    args += ["-c:a", "libmp3lame", "-q:a", "9", "-id3v2_version", "3"]
    for key, value in tags.items():
        args += ["-metadata", f"{key}={value}"]
    tmp = path + ".part.mp3"
    ffmpeg(*args, tmp)
    os.replace(tmp, path)


def silent_m4b(path, chapters, cover_path, tags):
    """Silent AAC audiobook (mono, 16 kHz, 12 kbit/s) with MP4 chapter marks and an embedded cover."""
    if os.path.exists(path):
        return
    total = sum(d for _, d in chapters)
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as meta:
        meta.write(";FFMETADATA1\n")
        for key, value in tags.items():
            meta.write(f"{key}={ffmeta_escape(value)}\n")
        start = 0
        for title, seconds in chapters:
            meta.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={start * 1000}\nEND={(start + seconds) * 1000}\n"
                       f"title={ffmeta_escape(title)}\n")
            start += seconds
    tmp = path + ".part.m4b"
    try:
        ffmpeg("-f", "lavfi", "-t", str(total), "-i", "anullsrc=r=16000:cl=mono", "-i", meta.name, "-i", cover_path,
               "-map", "0:a", "-map", "2:v", "-map_metadata", "1", "-map_chapters", "1",
               "-c:a", "aac", "-b:a", "12k", "-c:v", "copy", "-disposition:v", "attached_pic",
               "-f", "mp4", "-movflags", "+faststart", tmp)
        os.replace(tmp, path)
    finally:
        os.unlink(meta.name)


def ffmeta_escape(value):
    return re.sub(r"([=;#\\\n])", r"\\\1", str(value))


# ------------------------------------------------------------------------------------------------ music

def build_music(out):
    for album in catalog.ALBUMS:
        folder = f"{out}/device/Music/{safe(album['artist'])}/{safe(album['title'])}"
        cover = covers.cover(f"{folder}/cover.jpg", album["title"], album["artist"], album["palette"],
                             album["motif"], "album") if not os.path.exists(f"{folder}/cover.jpg") else f"{folder}/cover.jpg"
        count = len(album["tracks"])
        for n, (title, seconds) in enumerate(album["tracks"], start=1):
            silent_mp3(f"{folder}/{n:02d} - {safe(title)}.mp3", seconds, cover, {
                "title": title, "artist": album["artist"], "album_artist": album["artist"],
                "album": album["title"], "date": album["year"], "genre": album["genre"], "track": f"{n}/{count}",
                "disc": "1/1",
                # ffmpeg turns a "lyrics" key into TXXX:USLT; TXXX:UNSYNCEDLYRICS is a key the app reads.
                **({"UNSYNCEDLYRICS": catalog.LYRICS[title]} if title in catalog.LYRICS else {}),
            })
        log(f"album  {album['artist']} – {album['title']} ({count} tracks)")


# ------------------------------------------------------------------------------------------------ books

def book_folder(out, book):
    parts = [f"{out}/device/Audiobooks", safe(book["author"])]
    name = f"{safe(book['title'])} {{{safe(book['narrator'])}}}"
    if book.get("series"):
        parts.append(safe(book["series"]))
        name = f"{book['sequence']} – {name}"
    parts.append(name)
    return "/".join(parts)


def opf(book):
    series = ""
    if book.get("series"):
        series = (f'    <meta name="calibre:series" content="{escape(book["series"])}"/>\n'
                  f'    <meta name="calibre:series_index" content="{book["sequence"]}"/>\n')
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>{escape(book["title"])}</dc:title>
    <dc:creator opf:role="aut">{escape(book["author"])}</dc:creator>
    <dc:contributor opf:role="nrt">{escape(book["narrator"])}</dc:contributor>
    <dc:description>{escape(book["description"])}</dc:description>
    <dc:publisher>Imaginary Audio Press</dc:publisher>
    <dc:date>{book["year"]}</dc:date>
    <dc:language>en</dc:language>
    <dc:subject>{escape(book["genre"])}</dc:subject>
{series}  </metadata>
</package>
"""


def build_books(out):
    for book in catalog.BOOKS:
        folder = book_folder(out, book)
        cover = f"{folder}/cover.jpg"
        if not os.path.exists(cover):
            covers.cover(cover, book["title"], book["author"], book["palette"], book["motif"], "book")
        with open(f"{folder}/desc.txt", "w", encoding="utf-8") as f:
            f.write(book["description"] + "\n")
        with open(f"{folder}/metadata.opf", "w", encoding="utf-8") as f:
            f.write(opf(book))
        tags = {"title": book["title"], "artist": book["author"], "album_artist": book["author"],
                "album": book["title"], "composer": book["narrator"], "date": book["year"], "genre": "Audiobook"}
        if book["layout"] == "m4b":
            silent_m4b(f"{folder}/{safe(book['title'])}.m4b", book["chapters"], cover, tags)
        else:
            count = len(book["chapters"])
            for n, (title, seconds) in enumerate(book["chapters"], start=1):
                silent_mp3(f"{folder}/{n:02d} - {safe(title)}.mp3", seconds, cover,
                           {**tags, "title": title, "track": f"{n}/{count}"})
        hours = sum(d for _, d in book["chapters"]) / 3600
        log(f"book   {book['author']} – {book['title']} ({book['layout']}, {len(book['chapters'])} chapters, {hours:.1f} h)")


# ---------------------------------------------------------------------------------------------- podcasts

def hms(seconds):
    return f"{seconds // 3600:02d}:{seconds % 3600 // 60:02d}:{seconds % 60:02d}"


def build_podcasts(out):
    now = dt.datetime.now(dt.timezone.utc).replace(minute=0, second=0, microsecond=0)
    outlines = []
    for i, pod in enumerate(catalog.PODCASTS):
        folder = f"{out}/feeds/{pod['slug']}"
        base = f"{FEED_BASE}/{pod['slug']}"
        if not os.path.exists(f"{folder}/cover.jpg"):
            covers.cover(f"{folder}/cover.jpg", pod["title"], pod["author"], pod["palette"], pod["motif"], "podcast")
        items = []
        count = len(pod["episodes"])
        for k, (title, seconds, description) in enumerate(pod["episodes"]):
            number = count - k
            file_name = f"{number:02d}.mp3"
            silent_mp3(f"{folder}/{file_name}", seconds, None,
                       {"title": title, "artist": pod["author"], "album": pod["title"], "genre": "Podcast"})
            # Staggered by podcast so that the "new" list mixes shows; the first two podcasts have today's episode.
            published = now - dt.timedelta(days=k * pod["every_days"] + (i // 2), hours=3 * i + 2)
            size = os.path.getsize(f"{folder}/{file_name}")
            items.append(f"""    <item>
      <title>{escape(title)}</title>
      <guid isPermaLink="false">{pod['slug']}-{number}</guid>
      <pubDate>{email.utils.format_datetime(published)}</pubDate>
      <description>{escape(description)}</description>
      <itunes:summary>{escape(description)}</itunes:summary>
      <itunes:episode>{number}</itunes:episode>
      <itunes:duration>{hms(seconds)}</itunes:duration>
      <itunes:explicit>false</itunes:explicit>
      <enclosure url="{base}/{file_name}" length="{size}" type="audio/mpeg"/>
    </item>""")
        feed = f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>{escape(pod['title'])}</title>
    <link>{base}/</link>
    <atom:link href="{base}/feed.xml" rel="self" type="application/rss+xml"/>
    <language>en</language>
    <description>{escape(pod['description'])}</description>
    <itunes:summary>{escape(pod['description'])}</itunes:summary>
    <itunes:author>{escape(pod['author'])}</itunes:author>
    <itunes:category text="{escape(pod['category'])}"/>
    <itunes:explicit>false</itunes:explicit>
    <itunes:image href="{base}/cover.jpg"/>
    <image><url>{base}/cover.jpg</url><title>{escape(pod['title'])}</title><link>{base}/</link></image>
{chr(10).join(items)}
  </channel>
</rss>
"""
        with open(f"{folder}/feed.xml", "w", encoding="utf-8") as f:
            f.write(feed)
        outlines.append(f'    <outline type="rss" text="{escape(pod["title"])}" title="{escape(pod["title"])}" '
                        f'xmlUrl="{base}/feed.xml"/>')
        log(f"feed   {base}/feed.xml ({count} episodes)")
    with open(f"{out}/feeds/demo-podcasts.opml", "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<opml version="2.0">\n  <head><title>Demo podcasts</title></head>\n'
                f'  <body>\n{chr(10).join(outlines)}\n  </body>\n</opml>\n')


if __name__ == "__main__":
    target = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else f"{REPO}/build/demo-library")
    for step in (build_music, build_books, build_podcasts):
        step(target)
    log(f"DONE {target}")
