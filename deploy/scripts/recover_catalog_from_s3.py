#!/usr/bin/env python3
"""
Re-links videos that are still in the media bucket but lost their catalog rows (e.g. after the catalog
databases were destroyed and recreated). For every  videos/{uuid}/  folder in the bucket that no catalog row
points at, it inserts a catalog.video row (and its catalog.video_rendition rows) on the right shard, so the
videos show up on the site again.

What the bucket still has, and is recovered:
    videos/{uuid}/source                 -> video.video_key, size, content type, created_at (object time)
    videos/{uuid}/thumbnail-{uuid}       -> video.thumbnail_key (newest one if several)
    videos/{uuid}/hls/{q}/index.m3u8     -> video_rendition (HLS), duration (sum of #EXTINF)
    videos/{uuid}/renditions/{q}.mp4     -> video_rendition (legacy MP4), only where no HLS of that quality exists

What the bucket never had, and has to be supplied or defaulted:
    title / description  -> --titles CSV (folder uuid,title[,description]); default "Recovered video <date>"
    owner / channel      -> --channel-id, or --user-id (+ --channel-name) to use/create that user's channel
    visibility           -> --visibility (default PUBLIC); video type is always VIDEO, ads off

Videos without any rendition are inserted as PROCESSING with no transcode time, so catalog-service's
TranscodeSweeper re-queues them for the transcode worker. Videos with only MP4 renditions are picked up by the
HLS backfill job. Re-running the script is safe: folders already referenced by a catalog row are skipped.

Dry run by default; pass --apply to write.

Example (EKS; Postgres is in-cluster, so port-forward both catalog shards first):
    kubectl -n itube port-forward svc/postgres 15432:5432
    kubectl -n itube port-forward svc/postgres-shard1 15433:5432
    pip install boto3 psycopg2-binary
    python deploy/scripts/recover_catalog_from_s3.py \
        --bucket itube-media-<account-id> --region eu-west-2 \
        --shard "postgresql://itube:<password>@localhost:15432/itube" \
        --shard "postgresql://itube:<password>@localhost:15433/itube" \
        --user-id 1 --apply

Local docker compose (SeaweedFS): add --endpoint http://localhost:8333 --access-key itube --secret-key itube-dev-password
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone

try:
    import boto3
    from botocore.config import Config
    import psycopg2
except ImportError:
    sys.exit("Missing dependencies: pip install boto3 psycopg2-binary")

# --- Mirrors ITubeBackend.sharding.ShardIds / ShardMap -------------------------------------------------------------
LOGICAL_SHARDS = 4096
SEQUENCE_BITS = 40
MASK64 = (1 << 64) - 1

HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
QUALITY_ORDER = {"360p": 1, "720p": 2}
FOLDER_RE = re.compile(r"^videos/([0-9a-fA-F-]{36})/$")
EXTINF_RE = re.compile(r"^#EXTINF:([0-9.]+)")


def compose_id(logical_shard: int, sequence: int) -> int:
    if not 0 < sequence < (1 << SEQUENCE_BITS):
        raise ValueError(f"sequence out of range: {sequence}")
    return (logical_shard << SEQUENCE_BITS) | sequence


def logical_shard_of(entity_id: int) -> int:
    return entity_id >> SEQUENCE_BITS


def logical_shard_for_new_owner(user_id: int) -> int:
    """ShardIds.logicalShardForNewOwner, with Java's 64-bit overflow and >>> semantics."""
    mixed = (user_id * 0x9E3779B97F4A7C15) & MASK64
    mixed ^= mixed >> 31
    return mixed % LOGICAL_SHARDS  # floorMod by a power of two == low bits


class ShardMap:
    """Default even split, like ShardMap without first-logical-shard overrides."""

    def __init__(self, count: int, first_logical: list[int] | None = None):
        self.first = first_logical or [i * LOGICAL_SHARDS // count for i in range(count)]
        if len(self.first) != count or self.first[0] != 0:
            raise SystemExit("--first-logical must have one entry per --shard, starting at 0")

    def physical_of(self, logical: int) -> int:
        physical = 0
        for i in range(1, len(self.first)):
            if self.first[i] <= logical:
                physical = i
        return physical


# --- Bucket scan --------------------------------------------------------------------------------------------------
@dataclass
class Rendition:
    quality: str
    key: str
    content_type: str
    size: int


@dataclass
class Folder:
    prefix: str
    uuid: str
    source: dict | None = None
    thumbnail: dict | None = None
    hls: dict[str, dict] = field(default_factory=dict)          # quality -> playlist object
    hls_sizes: dict[str, int] = field(default_factory=dict)     # quality -> total segment bytes
    mp4: dict[str, dict] = field(default_factory=dict)          # quality -> object

    def renditions(self) -> list[Rendition]:
        out = [Rendition(q, o["Key"], HLS_CONTENT_TYPE, self.hls_sizes.get(q, 0)) for q, o in self.hls.items()]
        out += [Rendition(q, o["Key"], "video/mp4", o["Size"]) for q, o in self.mp4.items() if q not in self.hls]
        return sorted(out, key=lambda r: QUALITY_ORDER.get(r.quality, 3))


def list_folders(s3, bucket: str) -> list[str]:
    folders = []
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix="videos/", Delimiter="/"):
        folders += [p["Prefix"] for p in page.get("CommonPrefixes", []) if FOLDER_RE.match(p["Prefix"])]
    return folders


def scan_folder(s3, bucket: str, prefix: str) -> Folder:
    folder = Folder(prefix, FOLDER_RE.match(prefix).group(1).lower())
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            rest = obj["Key"][len(prefix):]
            parts = rest.split("/")
            if rest == "source":
                folder.source = obj
            elif rest.startswith("thumbnail-") and "/" not in rest:
                if folder.thumbnail is None or obj["LastModified"] > folder.thumbnail["LastModified"]:
                    folder.thumbnail = obj
            elif len(parts) == 3 and parts[0] == "hls":
                quality, name = parts[1], parts[2]
                if name == "index.m3u8":
                    folder.hls[quality] = obj
                else:
                    folder.hls_sizes[quality] = folder.hls_sizes.get(quality, 0) + obj["Size"]
            elif len(parts) == 2 and parts[0] == "renditions" and parts[1].endswith(".mp4"):
                folder.mp4[parts[1][:-4]] = obj
    return folder


def head_content_type(s3, bucket: str, key: str, default: str) -> str:
    try:
        return s3.head_object(Bucket=bucket, Key=key).get("ContentType") or default
    except Exception:  # noqa: BLE001 - best effort
        return default


def duration_seconds(s3, bucket: str, folder: Folder) -> float | None:
    """Sum of the HLS playlist's segment lengths (lowest quality is smallest to read)."""
    for quality in sorted(folder.hls, key=lambda q: QUALITY_ORDER.get(q, 3)):
        try:
            body = s3.get_object(Bucket=bucket, Key=folder.hls[quality]["Key"])["Body"].read().decode("utf-8")
        except Exception:  # noqa: BLE001
            continue
        total = sum(float(m.group(1)) for line in body.splitlines() if (m := EXTINF_RE.match(line.strip())))
        if total > 0:
            return total
    return None


def to_time(seconds: float | None) -> str:
    s = max(0, min(int(round(seconds or 0)), 24 * 3600 - 1))  # column is TIME
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


# --- Catalog -----------------------------------------------------------------------------------------------------
def referenced_folders(conns) -> set[str]:
    """Every videos/{uuid}/ folder any catalog row on any shard already points at."""
    seen = set()
    for conn in conns:
        with conn.cursor() as cur:
            cur.execute("SELECT video_key FROM catalog.video WHERE video_key IS NOT NULL "
                        "UNION SELECT thumbnail_key FROM catalog.video WHERE thumbnail_key IS NOT NULL "
                        "UNION SELECT storage_key FROM catalog.video_rendition WHERE storage_key IS NOT NULL "
                        "UNION SELECT storage_key FROM catalog.upload_session")
            for (key,) in cur.fetchall():
                m = re.match(r"^(videos/[^/]+/)", key)
                if m:
                    seen.add(m.group(1).lower())
    return seen


def resolve_channel(args, conns, shard_map: ShardMap) -> tuple[int, str]:
    """Returns (channel_id, description). Creates the user's channel only with --apply."""
    if args.channel_id:
        physical = shard_map.physical_of(logical_shard_of(args.channel_id))
        with conns[physical].cursor() as cur:
            cur.execute("SELECT channel_name FROM catalog.channel WHERE channel_id = %s", (args.channel_id,))
            row = cur.fetchone()
        if not row:
            raise SystemExit(f"Channel {args.channel_id} not found on physical shard {physical}")
        return args.channel_id, f"existing channel {args.channel_id} ({row[0]!r})"

    user_id, name = args.user_id, args.channel_name
    with conns[0].cursor() as cur:
        cur.execute("SELECT logical_shard FROM catalog.owner_shard WHERE user_id = %s", (user_id,))
        row = cur.fetchone()
    logical = row[0] if row else logical_shard_for_new_owner(user_id)
    physical = shard_map.physical_of(logical)
    conn = conns[physical]
    with conn.cursor() as cur:
        cur.execute("SELECT channel_id FROM catalog.channel WHERE user_id = %s AND channel_name = %s "
                    "ORDER BY channel_id LIMIT 1", (user_id, name))
        found = cur.fetchone()
    if found:
        return found[0], f"existing channel {found[0]} ({name!r}) of user {user_id}"
    if not args.apply:
        return -1, f"NEW channel {name!r} for user {user_id} on logical shard {logical} (created with --apply)"
    if not row:
        with conns[0].cursor() as cur:
            cur.execute("INSERT INTO catalog.owner_shard (user_id, logical_shard) VALUES (%s, %s) "
                        "ON CONFLICT (user_id) DO NOTHING", (user_id, logical))
        conns[0].commit()
    with conn.cursor() as cur:
        cur.execute("SELECT nextval(pg_get_serial_sequence('catalog.channel', 'channel_id'))")
        channel_id = compose_id(logical, cur.fetchone()[0])
        cur.execute("INSERT INTO catalog.channel (channel_id, user_id, channel_name) VALUES (%s, %s, %s)",
                    (channel_id, user_id, name))
    conn.commit()
    return channel_id, f"created channel {channel_id} ({name!r}) for user {user_id}"


def insert_video(conn, logical: int, channel_id: int, folder: Folder, meta: dict) -> int:
    renditions = folder.renditions()
    with conn.cursor() as cur:
        cur.execute("SELECT nextval(pg_get_serial_sequence('catalog.video', 'video_id'))")
        video_id = compose_id(logical, cur.fetchone()[0])
        cur.execute(
            """INSERT INTO catalog.video (video_id, channel_id, video_key, video_size_bytes, video_content_type,
                   thumbnail_key, thumbnail_content_type, title, description, duration, processing_status, visibility,
                   required_tier_level, video_type, ads_enabled, ad_count, created_at, transcode_attempts,
                   transcode_requested_at)
               VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NULL, 'VIDEO', FALSE, 1, %s, 0, NULL)""",
            (video_id, channel_id, folder.source["Key"], folder.source["Size"], meta["content_type"],
             folder.thumbnail["Key"] if folder.thumbnail else None, meta["thumbnail_content_type"],
             meta["title"], meta["description"], meta["duration"],
             "READY" if renditions else "PROCESSING", meta["visibility"], folder.source["LastModified"]))
        for r in renditions:
            cur.execute("INSERT INTO catalog.video_rendition (video_id, quality, content_type, storage_key, size_bytes) "
                        "VALUES (%s, %s, %s, %s, %s)", (video_id, r.quality, r.content_type, r.key, r.size))
    conn.commit()
    return video_id


def load_titles(path: str | None) -> dict[str, tuple[str, str | None]]:
    if not path:
        return {}
    titles = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) >= 2 and FOLDER_RE.match(f"videos/{row[0].strip()}/"):
                titles[row[0].strip().lower()] = (row[1].strip()[:255], (row[2].strip()[:5000] or None) if len(row) > 2 else None)
    return titles


def main() -> None:
    p = argparse.ArgumentParser(description="Re-link orphaned videos in the media bucket to the catalog.")
    p.add_argument("--bucket", required=True)
    p.add_argument("--region", default=None)
    p.add_argument("--endpoint", default=None, help="S3 endpoint (SeaweedFS locally); omit for AWS")
    p.add_argument("--access-key", default=None)
    p.add_argument("--secret-key", default=None)
    p.add_argument("--shard", action="append", required=True,
                   help="Catalog database DSN, in itube.sharding.shards order (shard 0 first). Repeat per shard.")
    p.add_argument("--first-logical", type=int, nargs="*", default=None,
                   help="Only if itube.sharding.shards[*].first-logical-shard is overridden")
    owner = p.add_mutually_exclusive_group(required=True)
    owner.add_argument("--channel-id", type=int, help="Attach recovered videos to this existing channel")
    owner.add_argument("--user-id", type=int, help="Attach to this account's --channel-name channel (created if missing)")
    p.add_argument("--channel-name", default="Recovered videos")
    p.add_argument("--visibility", default="PUBLIC", choices=["PUBLIC", "UNLISTED", "PRIVATE"])
    p.add_argument("--titles", help="CSV: folder uuid,title[,description]")
    p.add_argument("--include-without-source", action="store_true",
                   help="Also recover folders whose source file is gone (uses the best rendition as video_key)")
    p.add_argument("--limit", type=int, default=0, help="Recover at most this many (0 = all)")
    p.add_argument("--apply", action="store_true", help="Write to the database (default: dry run)")
    args = p.parse_args()

    s3 = boto3.client("s3", region_name=args.region, endpoint_url=args.endpoint,
                      aws_access_key_id=args.access_key, aws_secret_access_key=args.secret_key,
                      config=Config(s3={"addressing_style": "path"} if args.endpoint else {}))
    conns = [psycopg2.connect(dsn) for dsn in args.shard]
    shard_map = ShardMap(len(conns), args.first_logical)
    titles = load_titles(args.titles)

    channel_id, channel_desc = resolve_channel(args, conns, shard_map)
    logical = logical_shard_of(channel_id) if channel_id > 0 else None
    target = conns[shard_map.physical_of(logical)] if logical is not None else None

    known = referenced_folders(conns)
    folders = list_folders(s3, args.bucket)
    print(f"{'APPLY' if args.apply else 'DRY RUN'}: {len(folders)} video folders in s3://{args.bucket}, "
          f"{len(known)} already in the catalog. Target: {channel_desc}")

    recovered = skipped = failed = 0
    for prefix in folders:
        if prefix.lower() in known:
            continue
        if args.limit and recovered >= args.limit:
            break
        folder = scan_folder(s3, args.bucket, prefix)
        if folder.source is None:
            best = next(iter(reversed(folder.renditions())), None)
            if not args.include_without_source or best is None:
                print(f"  skip {prefix}: no source file" + ("" if best else " and no renditions"))
                skipped += 1
                continue
            folder.source = {"Key": best.key, "Size": best.size,
                             "LastModified": (folder.hls.get(best.quality) or folder.mp4[best.quality])["LastModified"]}
        title, description = titles.get(folder.uuid, (None, None))
        created = folder.source["LastModified"].astimezone(timezone.utc)
        meta = {
            "title": title or f"Recovered video {created:%Y-%m-%d %H:%M} ({folder.uuid[:8]})",
            "description": description,
            "duration": to_time(duration_seconds(s3, args.bucket, folder)),
            "visibility": args.visibility,
            "content_type": head_content_type(s3, args.bucket, folder.source["Key"], "video/mp4"),
            "thumbnail_content_type": head_content_type(s3, args.bucket, folder.thumbnail["Key"], "image/jpeg")
            if folder.thumbnail else None,
        }
        qualities = ",".join(f"{r.quality}:{'hls' if r.content_type == HLS_CONTENT_TYPE else 'mp4'}"
                             for r in folder.renditions()) or "none -> PROCESSING (sweeper re-transcodes)"
        print(f"  {prefix}  title={meta['title']!r} duration={meta['duration']} "
              f"thumb={'yes' if folder.thumbnail else 'no'} renditions={qualities}")
        if args.apply:
            try:
                video_id = insert_video(target, logical, channel_id, folder, meta)
                print(f"    -> video {video_id}")
            except Exception as exc:  # noqa: BLE001 - keep going with the next folder
                target.rollback()
                print(f"    !! failed: {exc}")
                failed += 1
                continue
        recovered += 1

    print(f"{'Recovered' if args.apply else 'Would recover'} {recovered}, skipped {skipped}, failed {failed}.")
    if not args.apply:
        print("Nothing written. Re-run with --apply.")
    for conn in conns:
        conn.close()


if __name__ == "__main__":
    main()
